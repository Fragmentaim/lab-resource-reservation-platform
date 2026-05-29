package com.fragment.labbooking.common.redis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fragment.labbooking.common.constants.ReservationStatusConstants;
import com.fragment.labbooking.common.constants.ResourceSlotStatusConstants;
import com.fragment.labbooking.common.constants.ResourceSlotTypeConstants;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.mapper.ReservationMapper;
import com.fragment.labbooking.mapper.ResourceSlotMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class HotReservationRedisService {

    private static final Logger log = LoggerFactory.getLogger(HotReservationRedisService.class);

    private static final String STOCK_KEY_PREFIX = "reservation:hot:stock:";
    private static final String USERS_KEY_PREFIX = "reservation:hot:users:";
    private static final String LOADED_KEY_PREFIX = "reservation:hot:loaded:";
    private static final String INIT_LOCK_KEY_PREFIX = "reservation:hot:init-lock:";

    private static final long LUA_RESERVE_SUCCESS = 0L;
    private static final long LUA_RESERVE_OUT_OF_STOCK = 1L;
    private static final long LUA_RESERVE_DUPLICATE = 2L;

    private final StringRedisTemplate stringRedisTemplate;
    private final ReservationMapper reservationMapper;
    private final ResourceSlotMapper resourceSlotMapper;
    private final boolean enabled;
    private final long initWaitMillis;

    private final DefaultRedisScript<Long> reserveScript;
    private final DefaultRedisScript<Long> releaseScript;

    public HotReservationRedisService(StringRedisTemplate stringRedisTemplate,
                                      ReservationMapper reservationMapper,
                                      ResourceSlotMapper resourceSlotMapper,
                                      @Value("${app.reservation.hot-redis-enabled:true}") boolean enabled,
                                      @Value("${app.reservation.hot-init-wait-millis:1000}") long initWaitMillis) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.reservationMapper = reservationMapper;
        this.resourceSlotMapper = resourceSlotMapper;
        this.enabled = enabled;
        this.initWaitMillis = initWaitMillis;
        this.reserveScript = buildReserveScript();
        this.releaseScript = buildReleaseScript();
    }

    public boolean tryReserve(ResourceSlot slot, Long userId) {
        if (!shouldUseRedis(slot)) {
            return false;
        }

        ensureHotSlotCacheLoaded(slot);

        Long result = stringRedisTemplate.execute(
                reserveScript,
                List.of(stockKey(slot.getId()), usersKey(slot.getId())),
                String.valueOf(userId)
        );

        if (result == null) {
            throw new BusinessException("热门时段预约失败，请重试");
        }
        if (result == LUA_RESERVE_SUCCESS) {
            return true;
        }
        if (result == LUA_RESERVE_OUT_OF_STOCK) {
            throw new BusinessException("热门时段余量不足");
        }
        if (result == LUA_RESERVE_DUPLICATE) {
            throw new BusinessException("当前用户已预约该时段");
        }
        throw new BusinessException("热门时段预约失败，请重试");
    }

    public boolean reserveAndRegisterRollback(ResourceSlot slot, Long userId) {
        boolean reserved = tryReserve(slot, userId);
        if (reserved) {
            registerRollbackCompensation(slot, userId);
        }
        return reserved;
    }

    public void registerRollbackCompensation(ResourceSlot slot, Long userId) {
        if (!shouldUseRedis(slot)) {
            return;
        }

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }

        Long slotId = slot.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    releaseIfLoaded(slotId, userId);
                }
            }
        });
    }

    public void releaseAfterCommit(String slotType, Long slotId, Long userId) {
        if (!enabled || !ResourceSlotTypeConstants.HOT.equals(slotType)) {
            return;
        }

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            releaseIfLoaded(slotId, userId);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                releaseIfLoaded(slotId, userId);
            }
        });
    }

    public void releaseAfterSuccessfulCancellation(String slotType, Long slotId, Long userId) {
        releaseAfterCommit(slotType, slotId, userId);
    }

    public void syncSlotCache(ResourceSlot slot) {
        if (slot == null || slot.getId() == null) {
            return;
        }

        invalidateSlotCache(slot.getId());
        if (shouldUseRedis(slot) && ResourceSlotStatusConstants.OPEN.equals(slot.getStatus())) {
            preheatSlotCache(slot);
        }
    }

    public void preheatOpenHotSlots() {
        if (!enabled) {
            return;
        }

        List<ResourceSlot> hotSlots = resourceSlotMapper.selectList(new LambdaQueryWrapper<ResourceSlot>()
                .eq(ResourceSlot::getSlotType, ResourceSlotTypeConstants.HOT)
                .eq(ResourceSlot::getStatus, ResourceSlotStatusConstants.OPEN)
                .gt(ResourceSlot::getEndDatetime, LocalDateTime.now()));

        for (ResourceSlot hotSlot : hotSlots) {
            try {
                preheatSlotCache(hotSlot);
            } catch (Exception exception) {
                log.warn("Failed to preheat HOT slot cache for slotId={}", hotSlot.getId(), exception);
            }
        }
    }

    public void invalidateSlotCache(Long slotId) {
        stringRedisTemplate.delete(List.of(
                stockKey(slotId),
                usersKey(slotId),
                loadedKey(slotId),
                initLockKey(slotId)
        ));
    }

    private boolean shouldUseRedis(ResourceSlot slot) {
        return enabled && slot != null && ResourceSlotTypeConstants.HOT.equals(slot.getSlotType());
    }

    private void preheatSlotCache(ResourceSlot slot) {
        loadSlotCache(slot, true);
    }

    private void ensureHotSlotCacheLoaded(ResourceSlot slot) {
        Long slotId = slot.getId();
        if (isCacheLoaded(slotId)) {
            return;
        }

        if (loadSlotCache(slot, false)) {
            return;
        }

        if (waitForCacheInitialization(slotId)) {
            return;
        }

        log.warn("HOT slot cache wait timed out, fallback to direct rebuild, slotId={}", slotId);
        try {
            rebuildSlotCache(slotId);
        } catch (RuntimeException exception) {
            log.warn("Failed to rebuild HOT slot cache after wait timeout, slotId={}", slotId, exception);
            throw new BusinessException("系统繁忙，请稍后重试");
        }
    }

    private boolean waitForCacheInitialization(Long slotId) {
        long deadline = System.currentTimeMillis() + initWaitMillis;
        while (System.currentTimeMillis() < deadline) {
            if (isCacheLoaded(slotId)) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new BusinessException("系统繁忙，请稍后重试");
            }
        }
        return isCacheLoaded(slotId);
    }

    private boolean loadSlotCache(ResourceSlot slot, boolean forceRefresh) {
        if (!shouldUseRedis(slot)) {
            return false;
        }

        Long slotId = slot.getId();
        if (!forceRefresh && isCacheLoaded(slotId)) {
            return true;
        }

        String initLockKey = initLockKey(slotId);
        Boolean locked = stringRedisTemplate.opsForValue()
                .setIfAbsent(initLockKey, "1", Duration.ofSeconds(10));

        if (!Boolean.TRUE.equals(locked)) {
            return false;
        }

        try {
            if (forceRefresh || !isCacheLoaded(slotId)) {
                loadCacheFromDatabase(slot);
            }
            return true;
        } finally {
            stringRedisTemplate.delete(initLockKey);
        }
    }

    private void rebuildSlotCache(Long slotId) {
        ResourceSlot latestSlot = resourceSlotMapper.selectById(slotId);
        if (latestSlot == null) {
            throw new BusinessException("热门时段不存在");
        }

        if (!shouldUseRedis(latestSlot) || !ResourceSlotStatusConstants.OPEN.equals(latestSlot.getStatus())) {
            invalidateSlotCache(slotId);
            return;
        }

        loadCacheFromDatabase(latestSlot);
    }

    private void loadCacheFromDatabase(ResourceSlot slot) {
        Long slotId = slot.getId();
        List<Reservation> bookedReservations = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .select(Reservation::getUserId)
                .eq(Reservation::getSlotId, slotId)
                .eq(Reservation::getStatus, ReservationStatusConstants.BOOKED));

        stringRedisTemplate.delete(stockKey(slotId));
        stringRedisTemplate.delete(usersKey(slotId));

        stringRedisTemplate.opsForValue().set(stockKey(slotId), String.valueOf(slot.getRemainQuota()));

        if (!bookedReservations.isEmpty()) {
            String[] members = bookedReservations.stream()
                    .map(Reservation::getUserId)
                    .map(String::valueOf)
                    .toArray(String[]::new);
            stringRedisTemplate.opsForSet().add(usersKey(slotId), members);
        }

        stringRedisTemplate.opsForValue().set(loadedKey(slotId), "1");
    }

    private void releaseIfLoaded(Long slotId, Long userId) {
        if (!isCacheLoaded(slotId)) {
            return;
        }
        stringRedisTemplate.execute(
                releaseScript,
                List.of(stockKey(slotId), usersKey(slotId)),
                String.valueOf(userId)
        );
    }

    private boolean isCacheLoaded(Long slotId) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(loadedKey(slotId)));
    }

    private String stockKey(Long slotId) {
        return STOCK_KEY_PREFIX + slotId;
    }

    private String usersKey(Long slotId) {
        return USERS_KEY_PREFIX + slotId;
    }

    private String loadedKey(Long slotId) {
        return LOADED_KEY_PREFIX + slotId;
    }

    private String initLockKey(Long slotId) {
        return INIT_LOCK_KEY_PREFIX + slotId;
    }

    private DefaultRedisScript<Long> buildReserveScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
                if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
                    return 2
                end
                local stock = tonumber(redis.call('GET', KEYS[1]) or '-1')
                if stock <= 0 then
                    return 1
                end
                redis.call('DECR', KEYS[1])
                redis.call('SADD', KEYS[2], ARGV[1])
                return 0
                """);
        return script;
    }

    private DefaultRedisScript<Long> buildReleaseScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
                if redis.call('SREM', KEYS[2], ARGV[1]) == 1 then
                    redis.call('INCR', KEYS[1])
                    return 1
                end
                return 0
                """);
        return script;
    }
}
