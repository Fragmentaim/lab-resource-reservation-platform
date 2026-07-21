package com.fragment.labbooking.common.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.vo.ResourceVO;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
@Slf4j
public class ResourceRedisCacheService {

    private static final String RESOURCE_DETAIL_PREFIX = "resource:detail:";
    private static final String RESOURCE_LIST_PREFIX = "resource:list:";
    private static final String RESOURCE_SLOT_LIST_PREFIX = "resource:slots:";
    private static final String RESOURCE_LIST_INDEX_KEY = "resource:list:index";
    private static final String NULL_MARKER = "__NULL__";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final Duration ttl;
    private final Duration nullTtl;
    private final long ttlJitterSeconds;
    private final boolean doubleDeleteEnabled;
    private final long doubleDeleteDelayMillis;
    private final ScheduledExecutorService delayedDeleteExecutor;

    public ResourceRedisCacheService(StringRedisTemplate stringRedisTemplate,
                                     ObjectMapper objectMapper,
                                     @Value("${app.resource-cache.enabled:true}") boolean enabled,
                                     @Value("${app.resource-cache.ttl-seconds:300}") long ttlSeconds,
                                     @Value("${app.resource-cache.null-ttl-seconds:60}") long nullTtlSeconds,
                                     @Value("${app.resource-cache.ttl-jitter-seconds:60}") long ttlJitterSeconds,
                                     @Value("${app.resource-cache.double-delete-enabled:true}") boolean doubleDeleteEnabled,
                                     @Value("${app.resource-cache.double-delete-delay-millis:500}") long doubleDeleteDelayMillis) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.nullTtl = Duration.ofSeconds(Math.max(nullTtlSeconds, 1));
        this.ttlJitterSeconds = Math.max(ttlJitterSeconds, 0);
        this.doubleDeleteEnabled = doubleDeleteEnabled;
        this.doubleDeleteDelayMillis = Math.max(doubleDeleteDelayMillis, 0);
        this.delayedDeleteExecutor = Executors.newSingleThreadScheduledExecutor(new CacheThreadFactory());
    }

    public ResourceVO getResourceDetail(Long resourceId, Supplier<ResourceVO> loader) {
        if (!enabled || resourceId == null) {
            return loader.get();
        }

        String key = RESOURCE_DETAIL_PREFIX + resourceId;
        CacheLookup<ResourceVO> lookup = readValue(key, ResourceVO.class);
        if (lookup.hit()) {
            return lookup.value();
        }

        ResourceVO loaded = loader.get();
        writeDetailValue(key, loaded);
        return loaded;
    }

    public List<ResourceVO> getResourceList(String queryKey, Supplier<List<ResourceVO>> loader) {
        if (!enabled) {
            return loader.get();
        }

        String key = RESOURCE_LIST_PREFIX + queryKey;
        List<ResourceVO> cached = readList(key, new TypeReference<List<ResourceVO>>() {});
        if (cached != null) {
            return cached;
        }

        List<ResourceVO> loaded = loader.get();
        writeListValue(key, loaded);
        return loaded;
    }

    public List<ResourceSlot> getResourceSlots(Long resourceId, Supplier<List<ResourceSlot>> loader) {
        if (!enabled || resourceId == null) {
            return loader.get();
        }

        String key = RESOURCE_SLOT_LIST_PREFIX + resourceId;
        List<ResourceSlot> cached = readList(key, new TypeReference<List<ResourceSlot>>() {});
        if (cached != null) {
            return cached;
        }

        List<ResourceSlot> loaded = loader.get();
        writeValue(key, loaded, nextTtl());
        return loaded;
    }

    public void invalidateResourceCaches(Long resourceId) {
        if (!enabled) {
            return;
        }

        deleteResourceCachesNow(resourceId);
        scheduleDelayedDelete(() -> deleteResourceCachesNow(resourceId));
    }

    public void invalidateResourceSlotList(Long resourceId) {
        if (!enabled || resourceId == null) {
            return;
        }

        deleteResourceSlotListNow(resourceId);
        scheduleDelayedDelete(() -> deleteResourceSlotListNow(resourceId));
    }

    public String buildResourceListQueryKey(String name, String type, String status) {
        String raw = normalize(name) + "|" + normalize(type) + "|" + normalize(status);
        return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
    }

    @PreDestroy
    public void shutdown() {
        delayedDeleteExecutor.shutdownNow();
    }

    private <T> CacheLookup<T> readValue(String key, Class<T> clazz) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null) {
            return CacheLookup.miss();
        }

        if (NULL_MARKER.equals(json)) {
            return CacheLookup.hit(null);
        }

        try {
            return CacheLookup.hit(objectMapper.readValue(json, clazz));
        } catch (JsonProcessingException ex) {
            log.warn("Failed to deserialize resource cache value for key {}", key, ex);
            stringRedisTemplate.delete(key);
            return CacheLookup.miss();
        }
    }

    private <T> List<T> readList(String key, TypeReference<List<T>> typeReference) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null) {
            return null;
        }

        try {
            return objectMapper.readValue(json, typeReference);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to deserialize resource cache list for key {}", key, ex);
            stringRedisTemplate.delete(key);
            return null;
        }
    }

    private void writeValue(String key, Object value, Duration duration) {
        try {
            String json = objectMapper.writeValueAsString(value);
            stringRedisTemplate.opsForValue().set(key, json, duration);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize resource cache value for key {}", key, ex);
        }
    }

    private void writeDetailValue(String key, ResourceVO value) {
        if (value == null) {
            stringRedisTemplate.opsForValue().set(key, NULL_MARKER, nextNullTtl());
            return;
        }
        writeValue(key, value, nextTtl());
    }

    private void writeListValue(String key, List<ResourceVO> value) {
        if (value == null) {
            return;
        }

        writeValue(key, value, nextTtl());
        stringRedisTemplate.opsForSet().add(RESOURCE_LIST_INDEX_KEY, key);
    }

    private void deleteResourceCachesNow(Long resourceId) {
        if (resourceId != null) {
            stringRedisTemplate.delete(RESOURCE_DETAIL_PREFIX + resourceId);
            stringRedisTemplate.delete(RESOURCE_SLOT_LIST_PREFIX + resourceId);
        }
        deleteIndexedResourceListCaches();
    }

    private void deleteResourceSlotListNow(Long resourceId) {
        stringRedisTemplate.delete(RESOURCE_SLOT_LIST_PREFIX + resourceId);
    }

    private void deleteIndexedResourceListCaches() {
        Set<String> keys = stringRedisTemplate.opsForSet().members(RESOURCE_LIST_INDEX_KEY);
        if (keys == null || keys.isEmpty()) {
            return;
        }
        stringRedisTemplate.delete(keys);
        stringRedisTemplate.delete(RESOURCE_LIST_INDEX_KEY);
    }

    private void scheduleDelayedDelete(Runnable deleteTask) {
        if (!doubleDeleteEnabled || doubleDeleteDelayMillis <= 0) {
            return;
        }

        delayedDeleteExecutor.schedule(() -> {
            try {
                deleteTask.run();
            } catch (Exception exception) {
                log.warn("Failed to perform delayed cache delete", exception);
            }
        }, doubleDeleteDelayMillis, TimeUnit.MILLISECONDS);
    }

    private Duration nextTtl() {
        if (ttlJitterSeconds <= 0) {
            return ttl;
        }
        long jitter = ThreadLocalRandom.current().nextLong(ttlJitterSeconds + 1);
        return ttl.plusSeconds(jitter);
    }

    private Duration nextNullTtl() {
        if (ttlJitterSeconds <= 0) {
            return nullTtl;
        }
        long jitter = ThreadLocalRandom.current().nextLong(Math.min(ttlJitterSeconds, Math.max(nullTtl.getSeconds(), 1)) + 1);
        return nullTtl.plusSeconds(jitter);
    }

    private String normalize(String value) {
        return Objects.toString(value, "").trim();
    }

    private record CacheLookup<T>(boolean hit, T value) {
        private static <T> CacheLookup<T> miss() {
            return new CacheLookup<>(false, null);
        }

        private static <T> CacheLookup<T> hit(T value) {
            return new CacheLookup<>(true, value);
        }
    }

    private static final class CacheThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "resource-cache-double-delete");
            thread.setDaemon(true);
            return thread;
        }
    }
}
