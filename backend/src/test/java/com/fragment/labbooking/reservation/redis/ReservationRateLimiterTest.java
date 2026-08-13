package com.fragment.labbooking.reservation.redis;

import com.fragment.labbooking.common.constants.ResourceSlotTypeConstants;
import com.fragment.labbooking.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReservationRateLimiterTest {

    @Test
    void shouldUsePerUserHotLimiterAndRejectWhenNoPermitIsAvailable() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RRateLimiter limiter = mock(RRateLimiter.class);
        when(redissonClient.getRateLimiter("reservation:rate-limit:7:HOT:3:10")).thenReturn(limiter);
        when(limiter.tryAcquire()).thenReturn(false);
        ReservationRateLimiter rateLimiter = new ReservationRateLimiter(
                redissonClient, true, 5, 10, 3, 10);

        assertThatThrownBy(() -> rateLimiter.checkCreateReservationLimit(7L, ResourceSlotTypeConstants.HOT))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(429);

        verify(limiter).trySetRate(RateType.OVERALL, 3, Duration.ofSeconds(10), Duration.ofMinutes(1));
        verify(limiter).tryAcquire();
    }

    @Test
    void shouldSkipRedisWhenRateLimitIsDisabled() {
        RedissonClient redissonClient = mock(RedissonClient.class);
        ReservationRateLimiter rateLimiter = new ReservationRateLimiter(
                redissonClient, false, 5, 10, 3, 10);

        rateLimiter.checkCreateReservationLimit(7L, ResourceSlotTypeConstants.HOT);

        verifyNoInteractions(redissonClient);
    }
}
