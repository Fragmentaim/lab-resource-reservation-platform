package com.fragment.labbooking.common.redis;

import com.fragment.labbooking.common.constants.ResourceSlotTypeConstants;
import com.fragment.labbooking.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class ReservationRateLimiter {

    private static final String KEY_PREFIX = "reservation:rate-limit:";

    private final StringRedisTemplate stringRedisTemplate;
    private final boolean enabled;
    private final int normalMaxRequests;
    private final int normalWindowSeconds;
    private final int hotMaxRequests;
    private final int hotWindowSeconds;

    public ReservationRateLimiter(StringRedisTemplate stringRedisTemplate,
                                  @Value("${app.reservation.rate-limit.enabled:true}") boolean enabled,
                                  @Value("${app.reservation.rate-limit.normal-max-requests:5}") int normalMaxRequests,
                                  @Value("${app.reservation.rate-limit.normal-window-seconds:10}") int normalWindowSeconds,
                                  @Value("${app.reservation.rate-limit.hot-max-requests:3}") int hotMaxRequests,
                                  @Value("${app.reservation.rate-limit.hot-window-seconds:10}") int hotWindowSeconds) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.enabled = enabled;
        this.normalMaxRequests = normalMaxRequests;
        this.normalWindowSeconds = normalWindowSeconds;
        this.hotMaxRequests = hotMaxRequests;
        this.hotWindowSeconds = hotWindowSeconds;
    }

    public void checkCreateReservationLimit(Long userId, String slotType) {
        if (!enabled || userId == null) {
            return;
        }

        boolean hotSlot = ResourceSlotTypeConstants.HOT.equals(slotType);
        int maxRequests = hotSlot ? hotMaxRequests : normalMaxRequests;
        int windowSeconds = hotSlot ? hotWindowSeconds : normalWindowSeconds;

        long windowIndex = System.currentTimeMillis() / (windowSeconds * 1000L);
        String key = KEY_PREFIX + userId + ":" + (hotSlot ? "HOT" : "NORMAL") + ":" + windowIndex;

        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            stringRedisTemplate.expire(key, Duration.ofSeconds(windowSeconds));
        }

        if (count != null && count > maxRequests) {
            throw new BusinessException(429, hotSlot
                    ? "热门预约请求过于频繁，请稍后再试"
                    : "预约请求过于频繁，请稍后再试");
        }
    }
}
