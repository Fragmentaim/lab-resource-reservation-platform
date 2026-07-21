package com.fragment.labbooking.common.redis;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class HotSlotCacheWarmupRunner {

    private final HotReservationRedisService hotReservationRedisService;

    public HotSlotCacheWarmupRunner(HotReservationRedisService hotReservationRedisService) {
        this.hotReservationRedisService = hotReservationRedisService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmupHotSlotCache() {
        hotReservationRedisService.preheatOpenHotSlots();
    }
}
