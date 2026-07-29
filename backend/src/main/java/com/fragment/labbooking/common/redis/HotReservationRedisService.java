package com.fragment.labbooking.common.redis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fragment.labbooking.common.constants.ReservationStatusConstants;
import com.fragment.labbooking.common.constants.ResourceSlotStatusConstants;
import com.fragment.labbooking.common.constants.ResourceSlotTypeConstants;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.common.reservation.ReservationResultEvent;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.mapper.ReservationMapper;
import com.fragment.labbooking.mapper.ResourceSlotMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Owns the Redis state machine for preheated hot slots.
 *
 * <p>Request threads never rebuild a missing hot snapshot. Startup and committed
 * administrator changes prepare the snapshot; Lua is the only reservation
 * mutation path.</p>
 */
@Component
@Slf4j
public class HotReservationRedisService {

    private static final String SNAPSHOT_PREFIX = "reservation:hot:v2:snapshot:";
    private static final String STOCK_PREFIX = "reservation:hot:v2:stock:";
    private static final String USERS_PREFIX = "reservation:hot:v2:users:";
    private static final String REQUEST_PREFIX = "reservation:request:v2:";
    private static final String PENDING_KEY = "reservation:pending:v2";
    private static final String INIT_LOCK_PREFIX = "lock:reservation:hot:v2:init:";

    private static final long ACCEPTED = 0L;
    private static final long OUT_OF_STOCK = 1L;
    private static final long DUPLICATE_USER = 2L;
    private static final long NOT_READY = 3L;
    private static final long RESOURCE_MISMATCH = 4L;
    private static final long NOT_BOOKABLE = 5L;
    private static final long IDEMPOTENCY_CONFLICT = 6L;
    private static final long IDEMPOTENT_REPLAY = 10L;
    private static final long MIN_CACHE_TTL_MILLIS = 60_000L;

    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final ReservationMapper reservationMapper;
    private final ResourceSlotMapper resourceSlotMapper;
    private final boolean enabled;
    private final long initWaitMillis;
    private final Duration retentionAfterSlotEnd;
    private final Duration requestRetention;
    private final DefaultRedisScript<Long> initializeScript = initializeScript();
    private final DefaultRedisScript<Long> acceptScript = acceptScript();
    private final DefaultRedisScript<Long> publishAttemptScript = publishAttemptScript();
    private final DefaultRedisScript<Long> completeScript = completeScript();
    private final DefaultRedisScript<Long> releaseConfirmedScript = releaseConfirmedScript();

    public HotReservationRedisService(StringRedisTemplate redisTemplate,
                                      RedissonClient redissonClient,
                                      ReservationMapper reservationMapper,
                                      ResourceSlotMapper resourceSlotMapper,
                                      @Value("${app.reservation.hot-redis-enabled:true}") boolean enabled,
                                      @Value("${app.reservation.hot-init-wait-millis:1000}") long initWaitMillis,
                                      @Value("${app.reservation.hot-cache-retention-hours:24}") long retentionHours,
                                      @Value("${app.reservation.hot-request-retention-hours:24}") long requestRetentionHours) {
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.reservationMapper = reservationMapper;
        this.resourceSlotMapper = resourceSlotMapper;
        this.enabled = enabled;
        this.initWaitMillis = initWaitMillis;
        this.retentionAfterSlotEnd = Duration.ofHours(Math.max(0, retentionHours));
        this.requestRetention = Duration.ofHours(Math.max(1, requestRetentionHours));
    }

    public boolean isPreheatedHotSlot(Long slotId) {
        if (!enabled || slotId == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(snapshotKey(slotId)));
        } catch (RuntimeException exception) {
            // The caller will query the slot type once. NORMAL slots can still
            // use MySQL; HOT slots fail closed instead of bypassing Redis.
            log.debug("Unable to inspect HOT slot snapshot. slotId={}, reason={}",
                    slotId, exception.getMessage());
            return false;
        }
    }

    public HotRequestState accept(String requestId, Long userId, Long resourceId, Long slotId,
                                  long expiresAtEpochMillis) {
        long now = System.currentTimeMillis();
        long requestTtl = Math.max(requestRetention.toMillis(), expiresAtEpochMillis - now + requestRetention.toMillis());
        Long result;
        try {
            result = redisTemplate.execute(
                    acceptScript,
                    List.of(snapshotKey(slotId), stockKey(slotId), usersKey(slotId),
                            requestKey(requestId), PENDING_KEY),
                    requestId,
                    String.valueOf(userId),
                    String.valueOf(resourceId),
                    String.valueOf(slotId),
                    String.valueOf(now),
                    String.valueOf(expiresAtEpochMillis),
                    String.valueOf(requestTtl)
            );
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        assertAccepted(result == null ? NOT_READY : result);
        HotRequestState state = getRequest(requestId);
        if (state == null) {
            throw unavailable(null);
        }
        return state;
    }

    public void recordPublishAttempt(String requestId, boolean published) {
        redisTemplate.execute(
                publishAttemptScript,
                List.of(requestKey(requestId)),
                published ? "1" : "0",
                String.valueOf(System.currentTimeMillis())
        );
    }

    public HotRequestState getRequest(String requestId) {
        Map<Object, Object> values = redisTemplate.opsForHash().entries(requestKey(requestId));
        if (values == null || values.isEmpty()) {
            return null;
        }
        return new HotRequestState(
                text(values, "requestId"),
                number(values, "userId"),
                number(values, "resourceId"),
                number(values, "slotId"),
                text(values, "status"),
                longValue(values, "expiresAt", 0L),
                integer(values, "publishAttempts", 0),
                longValue(values, "lastPublishedAt", 0L),
                number(values, "reservationId"),
                text(values, "reservationNo"),
                text(values, "rejectCode"),
                text(values, "rejectReason")
        );
    }

    public List<String> findPendingRequestIds(int limit) {
        Set<String> ids = redisTemplate.opsForZSet().range(PENDING_KEY, 0, Math.max(1, limit) - 1L);
        return ids == null ? List.of() : List.copyOf(ids);
    }

    public void removePendingRequest(String requestId) {
        if (requestId != null) {
            redisTemplate.opsForZSet().remove(PENDING_KEY, requestId);
        }
    }

    public void complete(ReservationResultEvent event) {
        Long result = redisTemplate.execute(
                completeScript,
                List.of(stockKey(event.slotId()), usersKey(event.slotId()),
                        requestKey(event.requestId()), PENDING_KEY),
                String.valueOf(event.userId()),
                event.requestId(),
                event.status(),
                value(event.reservationId()),
                value(event.reservationNo()),
                value(event.rejectCode()),
                value(event.rejectReason()),
                String.valueOf(event.resourceId()),
                String.valueOf(event.slotId()),
                String.valueOf(requestRetention.toMillis())
        );
        if (result == null) {
            throw unavailable(null);
        }
    }

    public void releaseAfterSuccessfulCancellation(String slotType, Long slotId, Long userId) {
        if (!enabled || !ResourceSlotTypeConstants.HOT.equals(slotType) || slotId == null || userId == null) {
            return;
        }
        runAfterCommit(() -> redisTemplate.execute(
                releaseConfirmedScript,
                List.of(stockKey(slotId), usersKey(slotId)),
                String.valueOf(userId)
        ));
    }

    public void syncSlotCache(ResourceSlot slot) {
        if (slot == null || slot.getId() == null) {
            return;
        }
        runAfterCommit(() -> {
            deleteSlotCache(slot.getId());
            if (isOpenHotSlot(slot)) {
                initializeIfAbsent(slot);
            }
        });
    }

    public void invalidateSlotCache(Long slotId) {
        if (slotId != null) {
            runAfterCommit(() -> deleteSlotCache(slotId));
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void preheatOpenHotSlots() {
        if (!enabled) {
            return;
        }
        List<ResourceSlot> slots = resourceSlotMapper.selectList(new LambdaQueryWrapper<ResourceSlot>()
                .eq(ResourceSlot::getSlotType, ResourceSlotTypeConstants.HOT)
                .eq(ResourceSlot::getStatus, ResourceSlotStatusConstants.OPEN)
                .gt(ResourceSlot::getEndDatetime, LocalDateTime.now()));
        slots.forEach(slot -> {
            try {
                initializeIfAbsent(slot);
            } catch (RuntimeException exception) {
                log.warn("Failed to preheat HOT slot cache. slotId={}", slot.getId(), exception);
            }
        });
    }

    private void initializeIfAbsent(ResourceSlot slot) {
        if (!isOpenHotSlot(slot) || isPreheatedHotSlot(slot.getId())) {
            return;
        }
        RLock lock = redissonClient.getLock(INIT_LOCK_PREFIX + slot.getId());
        boolean acquired;
        try {
            acquired = lock.tryLock(initWaitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable(exception);
        }
        if (!acquired) {
            if (!isPreheatedHotSlot(slot.getId())) {
                throw unavailable(null);
            }
            return;
        }
        try {
            if (!isPreheatedHotSlot(slot.getId())) {
                loadCache(slot);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void loadCache(ResourceSlot slot) {
        List<Reservation> reservations = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .select(Reservation::getUserId)
                .eq(Reservation::getSlotId, slot.getId())
                .eq(Reservation::getStatus, ReservationStatusConstants.BOOKED));

        List<String> args = new ArrayList<>();
        args.add(String.valueOf(slot.getResourceId()));
        args.add(slot.getStatus());
        args.add(String.valueOf(epochMillis(slot.getOpenTime())));
        args.add(String.valueOf(epochMillis(slot.getEndDatetime())));
        args.add(String.valueOf(slot.getRemainQuota()));
        args.add(String.valueOf(cacheTtlMillis(slot)));
        reservations.stream().map(Reservation::getUserId).map(String::valueOf).forEach(args::add);

        Long result = redisTemplate.execute(
                initializeScript,
                List.of(snapshotKey(slot.getId()), stockKey(slot.getId()), usersKey(slot.getId())),
                args.toArray()
        );
        if (result == null) {
            throw unavailable(null);
        }
    }

    private void assertAccepted(long result) {
        if (result == ACCEPTED || result == IDEMPOTENT_REPLAY) {
            return;
        }
        if (result == OUT_OF_STOCK) {
            throw new BusinessException(409, "热门时段余量不足");
        }
        if (result == DUPLICATE_USER) {
            throw new BusinessException(409, "当前用户已提交该时段的预约");
        }
        if (result == RESOURCE_MISMATCH) {
            throw new BusinessException(400, "时段不属于当前资源");
        }
        if (result == NOT_BOOKABLE) {
            throw new BusinessException(409, "热门时段当前不可预约");
        }
        if (result == IDEMPOTENCY_CONFLICT) {
            throw new BusinessException(409, "Idempotency-Key 已用于其他预约请求");
        }
        throw unavailable(null);
    }

    private boolean isOpenHotSlot(ResourceSlot slot) {
        return slot != null
                && ResourceSlotTypeConstants.HOT.equals(slot.getSlotType())
                && ResourceSlotStatusConstants.OPEN.equals(slot.getStatus())
                && slot.getEndDatetime() != null
                && slot.getEndDatetime().isAfter(LocalDateTime.now());
    }

    private long cacheTtlMillis(ResourceSlot slot) {
        long expiry = epochMillis(slot.getEndDatetime()) + retentionAfterSlotEnd.toMillis();
        return Math.max(expiry - System.currentTimeMillis(), MIN_CACHE_TTL_MILLIS);
    }

    private long epochMillis(LocalDateTime value) {
        return value == null ? -1L : value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private void deleteSlotCache(Long slotId) {
        redisTemplate.delete(List.of(snapshotKey(slotId), stockKey(slotId), usersKey(slotId)));
    }

    private void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private BusinessException unavailable(Throwable cause) {
        BusinessException exception = new BusinessException(503, "热门预约通道暂时不可用，请稍后重试");
        if (cause != null) {
            exception.initCause(cause);
        }
        return exception;
    }

    private String snapshotKey(Long slotId) {
        return SNAPSHOT_PREFIX + slotId;
    }

    private String stockKey(Long slotId) {
        return STOCK_PREFIX + slotId;
    }

    private String usersKey(Long slotId) {
        return USERS_PREFIX + slotId;
    }

    private String requestKey(String requestId) {
        return REQUEST_PREFIX + requestId;
    }

    private String text(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private Long number(Map<Object, Object> values, String key) {
        String value = text(values, key);
        return value == null ? null : Long.valueOf(value);
    }

    private long longValue(Map<Object, Object> values, String key, long fallback) {
        Long value = number(values, key);
        return value == null ? fallback : value;
    }

    private int integer(Map<Object, Object> values, String key, int fallback) {
        Long value = number(values, key);
        return value == null ? fallback : value.intValue();
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private DefaultRedisScript<Long> initializeScript() {
        return script("""
                if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
                redis.call('DEL', KEYS[2], KEYS[3])
                redis.call('HSET', KEYS[1],
                    'resourceId', ARGV[1], 'status', ARGV[2],
                    'openAtMillis', ARGV[3], 'endAtMillis', ARGV[4])
                redis.call('PEXPIRE', KEYS[1], ARGV[6])
                redis.call('SET', KEYS[2], ARGV[5], 'PX', ARGV[6])
                for index = 7, #ARGV do
                    redis.call('HSET', KEYS[3], ARGV[index], 'BOOKED')
                end
                if #ARGV >= 7 then redis.call('PEXPIRE', KEYS[3], ARGV[6]) end
                return 1
                """);
    }

    private DefaultRedisScript<Long> acceptScript() {
        return script("""
                if redis.call('EXISTS', KEYS[4]) == 1 then
                    if redis.call('HGET', KEYS[4], 'userId') ~= ARGV[2]
                        or redis.call('HGET', KEYS[4], 'resourceId') ~= ARGV[3]
                        or redis.call('HGET', KEYS[4], 'slotId') ~= ARGV[4] then
                        return 6
                    end
                    return 10
                end
                if redis.call('EXISTS', KEYS[1]) == 0 or redis.call('EXISTS', KEYS[2]) == 0 then
                    return 3
                end
                if redis.call('HGET', KEYS[1], 'resourceId') ~= ARGV[3] then return 4 end
                if redis.call('HGET', KEYS[1], 'status') ~= 'OPEN' then return 5 end
                local now = tonumber(ARGV[5])
                local openAt = tonumber(redis.call('HGET', KEYS[1], 'openAtMillis') or '-1')
                local endAt = tonumber(redis.call('HGET', KEYS[1], 'endAtMillis') or '-1')
                if openAt < 0 or endAt < 0 or now < openAt or now >= endAt then return 5 end
                if redis.call('HEXISTS', KEYS[3], ARGV[2]) == 1 then return 2 end
                local stock = tonumber(redis.call('GET', KEYS[2]) or '-1')
                if stock <= 0 then return 1 end

                redis.call('DECR', KEYS[2])
                redis.call('HSET', KEYS[3], ARGV[2], ARGV[1])
                local stockTtl = redis.call('PTTL', KEYS[2])
                if stockTtl > 0 then redis.call('PEXPIRE', KEYS[3], stockTtl) end
                redis.call('HSET', KEYS[4],
                    'requestId', ARGV[1], 'userId', ARGV[2],
                    'resourceId', ARGV[3], 'slotId', ARGV[4],
                    'status', 'PRE_RESERVED', 'createdAt', ARGV[5],
                    'expiresAt', ARGV[6], 'publishAttempts', '0',
                    'lastPublishedAt', '0')
                redis.call('PEXPIRE', KEYS[4], ARGV[7])
                redis.call('ZADD', KEYS[5], ARGV[6], ARGV[1])
                return 0
                """);
    }

    private DefaultRedisScript<Long> publishAttemptScript() {
        return script("""
                if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end
                redis.call('HINCRBY', KEYS[1], 'publishAttempts', 1)
                redis.call('HSET', KEYS[1], 'lastPublishedAt', ARGV[2])
                if ARGV[1] == '1' then redis.call('HSET', KEYS[1], 'status', 'PUBLISHED') end
                return 1
                """);
    }

    private DefaultRedisScript<Long> completeScript() {
        return script("""
                local current = redis.call('HGET', KEYS[3], 'status')
                if current == 'CONFIRMED' or current == 'REJECTED' or current == 'EXPIRED' then
                    redis.call('ZREM', KEYS[4], ARGV[2])
                    return 0
                end
                if ARGV[3] ~= 'CONFIRMED' then
                    local ownerRequest = redis.call('HGET', KEYS[2], ARGV[1])
                    if ownerRequest == ARGV[2] then
                        redis.call('HDEL', KEYS[2], ARGV[1])
                        if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('INCR', KEYS[1]) end
                    end
                end
                redis.call('HSET', KEYS[3], 'status', ARGV[3],
                    'requestId', ARGV[2], 'userId', ARGV[1],
                    'resourceId', ARGV[8], 'slotId', ARGV[9],
                    'reservationId', ARGV[4], 'reservationNo', ARGV[5],
                    'rejectCode', ARGV[6], 'rejectReason', ARGV[7])
                redis.call('PEXPIRE', KEYS[3], ARGV[10])
                redis.call('ZREM', KEYS[4], ARGV[2])
                return 1
                """);
    }

    private DefaultRedisScript<Long> releaseConfirmedScript() {
        return script("""
                if redis.call('HDEL', KEYS[2], ARGV[1]) == 1 then
                    if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('INCR', KEYS[1]) end
                    return 1
                end
                return 0
                """);
    }

    private DefaultRedisScript<Long> script(String text) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText(text);
        return script;
    }

    public record HotRequestState(
            String requestId,
            Long userId,
            Long resourceId,
            Long slotId,
            String status,
            long expiresAtEpochMillis,
            int publishAttempts,
            long lastPublishedAtEpochMillis,
            Long reservationId,
            String reservationNo,
            String rejectCode,
            String rejectReason
    ) {
    }
}
