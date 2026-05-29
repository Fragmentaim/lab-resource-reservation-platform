package com.fragment.labbooking.common.redis;

import com.fragment.labbooking.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

@Component
public class ReservationSubmitGuard {

    private static final String KEY_PREFIX = "reservation:submit:";
    private static final String PROCESSING_VALUE = "PROCESSING";
    private static final String DONE_VALUE = "DONE";

    private final StringRedisTemplate stringRedisTemplate;
    private final boolean enabled;
    private final Duration ttl;

    public ReservationSubmitGuard(StringRedisTemplate stringRedisTemplate,
                                  @Value("${app.reservation.submit-guard-enabled:false}") boolean enabled,
                                  @Value("${app.reservation.submit-guard-ttl-seconds:5}") long ttlSeconds) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.enabled = enabled;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public String acquire(Long userId, Long slotId) {
        if (!enabled) {
            return null;
        }

        String key = KEY_PREFIX + userId + ":" + slotId;
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(key, PROCESSING_VALUE, ttl);
        if (Boolean.TRUE.equals(acquired)) {
            return key;
        }
        throw new BusinessException("请勿重复提交预约请求");
    }

    public void completeAfterTransaction(String key) {
        if (key == null) {
            return;
        }

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            markDone(key);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                markDone(key);
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    release(key);
                }
            }
        });
    }

    public void release(String key) {
        if (key == null) {
            return;
        }
        stringRedisTemplate.delete(key);
    }

    private void markDone(String key) {
        stringRedisTemplate.opsForValue().set(key, DONE_VALUE, ttl);
    }
}
