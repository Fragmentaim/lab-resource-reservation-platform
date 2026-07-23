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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Component
public class HotReservationRedisService {

    private static final Logger log = LoggerFactory.getLogger(HotReservationRedisService.class);

    private static final String STOCK_KEY_PREFIX = "reservation:hot:stock:";
    private static final String USERS_KEY_PREFIX = "reservation:hot:users:";
    private static final String LOADED_KEY_PREFIX = "reservation:hot:loaded:";
    private static final String SNAPSHOT_KEY_PREFIX = "reservation:hot:snapshot:";
    private static final String INIT_LOCK_KEY_PREFIX = "reservation:hot:init-lock:";

    private static final long LUA_RESERVE_SUCCESS = 0L;
    private static final long LUA_RESERVE_OUT_OF_STOCK = 1L;
    private static final long LUA_RESERVE_DUPLICATE = 2L;

    private static final long LUA_FAST_PATH_NOT_LOADED = 10L;
    private static final long LUA_FAST_PATH_RESOURCE_MISMATCH = 11L;
    private static final long LUA_FAST_PATH_NOT_BOOKABLE = 12L;
    private static final long LUA_FAST_PATH_OUT_OF_STOCK = 13L;
    private static final long LUA_FAST_PATH_DUPLICATE = 14L;

    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final ReservationMapper reservationMapper;
    private final ResourceSlotMapper resourceSlotMapper;
    private final boolean enabled;
    private final long initWaitMillis;

    private final DefaultRedisScript<Long> reserveScript;
    private final DefaultRedisScript<Long> reservePreheatedScript;
    private final DefaultRedisScript<Long> releaseScript;

    public HotReservationRedisService(StringRedisTemplate stringRedisTemplate,
                                      RedissonClient redissonClient,
                                      ReservationMapper reservationMapper,
                                      ResourceSlotMapper resourceSlotMapper,
                                      @Value("${app.reservation.hot-redis-enabled:true}") boolean enabled,
                                      @Value("${app.reservation.hot-init-wait-millis:1000}") long initWaitMillis) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
        this.reservationMapper = reservationMapper;
        this.resourceSlotMapper = resourceSlotMapper;
        this.enabled = enabled;
        this.initWaitMillis = initWaitMillis;
        this.reserveScript = buildReserveScript();
        this.reservePreheatedScript = buildReservePreheatedScript();
        this.releaseScript = buildReleaseScript();
    }

    /**
     * Attempts a reservation directly from a preheated HOT-slot snapshot.
     *
     * <p>A {@code false} result means this slot is not currently preheated and callers should
     * continue with the normal database-backed flow. Once a snapshot exists, all rejection
     * paths stay in Redis so sold-out traffic does not consume database connections.</p>
     */
    public boolean reserveIfPreheated(Long resourceId, Long slotId, Long userId) {
        if (!enabled || resourceId == null || slotId == null || userId == null || !isCacheLoaded(slotId)) {
            return false;
        }

        Long result = stringRedisTemplate.execute(
                reservePreheatedScript,
                List.of(loadedKey(slotId), snapshotKey(slotId), stockKey(slotId), usersKey(slotId)),
                String.valueOf(resourceId),
                String.valueOf(userId),
                String.valueOf(System.currentTimeMillis())
        );

        if (result == null) {
            throw new BusinessException("热门时段预约失败，请重试");
        }
        if (result == LUA_RESERVE_SUCCESS) {
            return true;
        }
        if (result == LUA_FAST_PATH_NOT_LOADED) {
            return false;
        }
        if (result == LUA_FAST_PATH_RESOURCE_MISMATCH) {
            throw new BusinessException("时段不属于当前资源");
        }
        if (result == LUA_FAST_PATH_NOT_BOOKABLE) {
            throw new BusinessException(409, "热门时段当前不可预约");
        }
        if (result == LUA_FAST_PATH_OUT_OF_STOCK) {
            throw new BusinessException(409, "热门时段余量不足");
        }
        if (result == LUA_FAST_PATH_DUPLICATE) {
            throw new BusinessException(409, "当前用户已预约该时段");
        }
        throw new BusinessException("热门时段预约失败，请重试");
    }

    public boolean isPreheatedHotSlot(Long slotId) {
        return enabled && slotId != null && isCacheLoaded(slotId);
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
            throw new BusinessException(409, "热门时段余量不足");
        }
        if (result == LUA_RESERVE_DUPLICATE) {
            throw new BusinessException(409, "当前用户已预约该时段");
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

        registerHotRollbackCompensation(slot.getId(), userId);
    }

    /**
     * Registers compensation after the caller has entered the persistence transaction.
     * The Redis fast path itself intentionally runs before a JDBC transaction is opened.
     */
    public void registerPreheatedReservationRollback(Long slotId, Long userId) {
        if (!enabled || slotId == null || userId == null) {
            return;
        }
        registerHotRollbackCompensation(slotId, userId);
    }

    private void registerHotRollbackCompensation(Long slotId, Long userId) {

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }

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
                snapshotKey(slotId)
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

        RLock initLock = redissonClient.getLock(initLockKey(slotId));
        // Omitting leaseTime lets Redisson's watchdog renew the lock until this thread unlocks it.
        if (!initLock.tryLock()) {
            return false;
        }

        try {
            if (forceRefresh || !isCacheLoaded(slotId)) {
                loadCacheFromDatabase(slot);
            }
            return true;
        } finally {
            if (initLock.isHeldByCurrentThread()) {
                initLock.unlock();
            }
        }
    }

    private void rebuildSlotCache(Long slotId) {
        RLock initLock = redissonClient.getLock(initLockKey(slotId));
        // Never bypass the initializer that may still be rebuilding this slot.
        if (!initLock.tryLock()) {
            throw new BusinessException("系统繁忙，请稍后重试");
        }

        try {
            if (isCacheLoaded(slotId)) {
                return;
            }

            ResourceSlot latestSlot = resourceSlotMapper.selectById(slotId);
            if (latestSlot == null) {
                throw new BusinessException("热门时段不存在");
            }

            if (!shouldUseRedis(latestSlot) || !ResourceSlotStatusConstants.OPEN.equals(latestSlot.getStatus())) {
                invalidateSlotCache(slotId);
                return;
            }

            loadCacheFromDatabase(latestSlot);
        } finally {
            if (initLock.isHeldByCurrentThread()) {
                initLock.unlock();
            }
        }
    }

    private void loadCacheFromDatabase(ResourceSlot slot) {
        Long slotId = slot.getId();
        List<Reservation> bookedReservations = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .select(Reservation::getUserId)
                .eq(Reservation::getSlotId, slotId)
                .eq(Reservation::getStatus, ReservationStatusConstants.BOOKED));

        stringRedisTemplate.delete(List.of(
                loadedKey(slotId),
                snapshotKey(slotId),
                stockKey(slotId),
                usersKey(slotId)
        ));

        stringRedisTemplate.opsForHash().putAll(snapshotKey(slotId), Map.of(
                "resourceId", String.valueOf(slot.getResourceId()),
                "status", slot.getStatus(),
                "openAtMillis", String.valueOf(toEpochMillis(slot.getOpenTime())),
                "endAtMillis", String.valueOf(toEpochMillis(slot.getEndDatetime()))
        ));

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

    private String snapshotKey(Long slotId) {
        return SNAPSHOT_KEY_PREFIX + slotId;
    }

    private String initLockKey(Long slotId) {
        return INIT_LOCK_KEY_PREFIX + slotId;
    }

    private long toEpochMillis(LocalDateTime value) {
        if (value == null) {
            return -1L;
        }
        return value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
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

    private DefaultRedisScript<Long> buildReservePreheatedScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
                if redis.call('GET', KEYS[1]) ~= '1' then
                    return 10
                end
                if redis.call('HGET', KEYS[2], 'resourceId') ~= ARGV[1] then
                    return 11
                end
                if redis.call('HGET', KEYS[2], 'status') ~= 'OPEN' then
                    return 12
                end
                local openAtMillis = tonumber(redis.call('HGET', KEYS[2], 'openAtMillis') or '-1')
                local endAtMillis = tonumber(redis.call('HGET', KEYS[2], 'endAtMillis') or '-1')
                local nowMillis = tonumber(ARGV[3])
                if openAtMillis < 0 or endAtMillis < 0 or nowMillis < openAtMillis or nowMillis >= endAtMillis then
                    return 12
                end
                if redis.call('SISMEMBER', KEYS[4], ARGV[2]) == 1 then
                    return 14
                end
                local stock = tonumber(redis.call('GET', KEYS[3]) or '-1')
                if stock <= 0 then
                    return 13
                end
                redis.call('DECR', KEYS[3])
                redis.call('SADD', KEYS[4], ARGV[2])
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
