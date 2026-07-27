package com.fragment.labbooking.common.redis;

import com.fragment.labbooking.vo.ResourceVO;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Cache boundary for the public resource catalog.
 *
 * <p>Only the unfiltered home-page list is cached. Resource details and slot
 * availability are intentionally read from MySQL because they either change
 * frequently or are not hot enough to justify another consistency boundary.</p>
 */
@Component
public class ResourceCatalogCache {

    public static final String RESOURCE_HOME_CACHE = "resourceHome";
    private static final String RESOURCE_HOME_KEY = "all";

    private final CacheManager cacheManager;
    private final long delayedEvictionMillis;
    private final ScheduledExecutorService delayedEvictionExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "resource-catalog-cache-evict");
                thread.setDaemon(true);
                return thread;
            });

    public ResourceCatalogCache(CacheManager cacheManager,
                                @Value("${app.resource-cache.double-delete-delay-millis:500}")
                                long delayedEvictionMillis) {
        this.cacheManager = cacheManager;
        this.delayedEvictionMillis = Math.max(0, delayedEvictionMillis);
    }

    @Cacheable(cacheNames = RESOURCE_HOME_CACHE, key = "'" + RESOURCE_HOME_KEY + "'", sync = true)
    public List<ResourceVO> getHomeResourceList(Supplier<List<ResourceVO>> loader) {
        return loader.get();
    }

    @CacheEvict(cacheNames = RESOURCE_HOME_CACHE, allEntries = true)
    public void invalidateHomeResourceList() {
        runAfterCommit(this::scheduleDelayedEviction);
    }

    @PreDestroy
    public void shutdown() {
        delayedEvictionExecutor.shutdownNow();
    }

    private void scheduleDelayedEviction() {
        if (delayedEvictionMillis <= 0) {
            return;
        }
        delayedEvictionExecutor.schedule(this::evictHomeCatalog,
                delayedEvictionMillis, TimeUnit.MILLISECONDS);
    }

    private void evictHomeCatalog() {
        Cache cache = cacheManager.getCache(RESOURCE_HOME_CACHE);
        if (cache != null) {
            cache.evict(RESOURCE_HOME_KEY);
        }
    }

    private void runAfterCommit(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                task.run();
            }
        });
    }
}
