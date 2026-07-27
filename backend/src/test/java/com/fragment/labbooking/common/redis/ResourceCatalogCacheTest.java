package com.fragment.labbooking.common.redis;

import com.fragment.labbooking.vo.ResourceVO;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceCatalogCacheTest {

    @Test
    void cachesOnlyTheHomeCatalogAndCanEvictIt() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(TestConfig.class)) {
            ResourceCatalogCache cacheService = context.getBean(ResourceCatalogCache.class);
            AtomicInteger loads = new AtomicInteger();

            List<ResourceVO> first = cacheService.getHomeResourceList(() -> catalog(loads.incrementAndGet()));
            List<ResourceVO> second = cacheService.getHomeResourceList(() -> catalog(loads.incrementAndGet()));

            assertThat(first.get(0).getResourceName()).isEqualTo("资源版本-1");
            assertThat(second.get(0).getResourceName()).isEqualTo("资源版本-1");
            assertThat(loads).hasValue(1);

            cacheService.invalidateHomeResourceList();
            List<ResourceVO> afterEviction =
                    cacheService.getHomeResourceList(() -> catalog(loads.incrementAndGet()));

            assertThat(afterEviction.get(0).getResourceName()).isEqualTo("资源版本-2");
            assertThat(loads).hasValue(2);
        }
    }

    @Test
    void delayedEvictionRemovesAStaleValueWrittenDuringTheRaceWindow() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(DelayedTestConfig.class)) {
            ResourceCatalogCache cacheService = context.getBean(ResourceCatalogCache.class);
            AtomicInteger loads = new AtomicInteger();

            cacheService.getHomeResourceList(() -> catalog(loads.incrementAndGet()));
            cacheService.invalidateHomeResourceList();
            cacheService.getHomeResourceList(() -> catalog(loads.incrementAndGet()));
            Thread.sleep(100);

            List<ResourceVO> afterSecondEviction =
                    cacheService.getHomeResourceList(() -> catalog(loads.incrementAndGet()));

            assertThat(afterSecondEviction.get(0).getResourceName()).isEqualTo("资源版本-3");
            assertThat(loads).hasValue(3);
        }
    }

    private List<ResourceVO> catalog(int version) {
        ResourceVO resource = new ResourceVO();
        resource.setId(1L);
        resource.setResourceName("资源版本-" + version);
        return List.of(resource);
    }

    @Configuration
    @EnableCaching
    static class TestConfig {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(ResourceCatalogCache.RESOURCE_HOME_CACHE);
        }

        @Bean
        ResourceCatalogCache resourceCatalogCache(CacheManager cacheManager) {
            return new ResourceCatalogCache(cacheManager, 0);
        }
    }

    @Configuration
    @EnableCaching
    static class DelayedTestConfig {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(ResourceCatalogCache.RESOURCE_HOME_CACHE);
        }

        @Bean
        ResourceCatalogCache resourceCatalogCache(CacheManager cacheManager) {
            return new ResourceCatalogCache(cacheManager, 30);
        }
    }
}
