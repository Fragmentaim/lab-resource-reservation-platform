package com.fragment.labbooking.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.time.Duration;

@Configuration
public class ResourceCacheConfig {

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                     @Value("${app.resource-cache.enabled:false}") boolean enabled,
                                     @Value("${app.resource-cache.ttl-seconds:300}") long ttlSeconds) {
        if (!enabled) {
            return new NoOpCacheManager();
        }

        RedisCacheConfiguration configuration = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(Math.max(1, ttlSeconds)))
                .disableCachingNullValues()
                .prefixCacheNameWith("lab-booking::");

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(configuration)
                .transactionAware()
                .build();
    }
}
