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
import java.util.concurrent.TimeUnit;

@Component
public class HotReservationRedisService {

    private static final Logger log = LoggerFactory.getLogger(HotReservationRedisService.class);

    private static final String SNAPSHOT_KEY_PREFIX = "reservation:hot:snapshot:";
    private static final String STOCK_KEY_PREFIX = "reservation:hot:stock:";
    private static final String USERS_KEY_PREFIX = "reservation:hot:users:";
    private static final String INIT_LOCK_KEY_PREFIX = "lock:reservation:hot:init:";

    private static final long RESERVE_SUCCESS = 0L;
    private static final long RESERVE_OUT_OF_STOCK = 1L;
    private static final long RESERVE_DUPLICATE = 2L;
    private static final long RESERVE_NOT_READY = 3L;
    private static final long RESERVE_RESOURCE_MISMATCH = 4L;
    private static final long RESERVE_NOT_BOOKABLE = 5L;
    private static final long MIN_CACHE_TTL_MILLIS = 60_000L;

    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final ReservationMapper reservationMapper;
    private final ResourceSlotMapper resourceSlotMapper;
    private final boolean enabled;
    private final long initWaitMillis;
    private final Duration retentionAfterSlotEnd;
    private final DefaultRedisScript<Long> initializeScript = buildInitializeScript();
    private final DefaultRedisScript<Long> reserveScript = buildReserveScript();
    private final DefaultRedisScript<Long> releaseScript = buildReleaseScript();

    public HotReservationRedisService(StringRedisTemplate redisTemplate,
                                      RedissonClient redissonClient,
                                      ReservationMapper reservationMapper,
                                      ResourceSlotMapper resourceSlotMapper,
                                      @Value("${app.reservation.hot-redis-enabled:true}") boolean enabled,
                                      @Value("${app.reservation.hot-init-wait-millis:1000}") long initWaitMillis,
                                      @Value("${app.reservation.hot-cache-retention-hours:24}") long retentionHours) {
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.reservationMapper = reservationMapper;
        this.resourceSlotMapper = resourceSlotMapper;
        this.enabled = enabled;
        this.initWaitMillis = initWaitMillis;
        this.retentionAfterSlotEnd = Duration.ofHours(Math.max(0, retentionHours));
    }

    /**
     * Fast path used before opening a JDBC transaction. A false result only means that this
     * slot has not been preheated; all authoritative rejection results are raised here.
     */
    public boolean reserveIfPreheated(Long resourceId, Long slotId, Long userId) {
        if (!enabled || resourceId == null || slotId == null || userId == null) {
            return false;
        }
        long result = reserve(resourceId, slotId, userId);
        if (result == RESERVE_NOT_READY) {
            return false;
        }
        assertReservationResult(result);
        return true;
    }

    public boolean isPreheatedHotSlot(Long slotId) {
        return enabled && slotId != null && isCacheReady(slotId);
    }

    public boolean reserveAndRegisterRollback(ResourceSlot slot, Long userId) {
        if (!shouldUseRedis(slot)) {
            return false;
        }
        assertReservationResult(reserve(slot.getResourceId(), slot.getId(), userId));
        registerRollbackCompensation(slot.getId(), userId);
        return true;
    }

    public void registerPreheatedReservationRollback(Long slotId, Long userId) {
        if (enabled && slotId != null && userId != null) {
            registerRollbackCompensation(slotId, userId);
        }
    }

    private void registerRollbackCompensation(Long slotId, Long userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    release(slotId, userId);
                }
            }
        });
    }

    public void releaseAfterCommit(String slotType, Long slotId, Long userId) {
        if (!enabled || !ResourceSlotTypeConstants.HOT.equals(slotType)) {
            return;
        }
        runAfterCommit(() -> release(slotId, userId));
    }

    public void releaseAfterSuccessfulCancellation(String slotType, Long slotId, Long userId) {
        releaseAfterCommit(slotType, slotId, userId);
    }

    /** Refreshes a slot only after its database transaction commits. */
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
        for (ResourceSlot slot : slots) {
            try {
                initializeIfAbsent(slot);
            } catch (RuntimeException exception) {
                log.warn("Failed to preheat HOT slot cache for slotId={}", slot.getId(), exception);
            }
        }
    }

    private long reserve(Long resourceId, Long slotId, Long userId) {
        try {
            Long result = redisTemplate.execute(
                    reserveScript,
                    List.of(snapshotKey(slotId), stockKey(slotId), usersKey(slotId)),
                    String.valueOf(resourceId),
                    String.valueOf(userId),
                    String.valueOf(System.currentTimeMillis())
            );
            return result == null ? RESERVE_NOT_READY : result;
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private void assertReservationResult(long result) {
        if (result == RESERVE_SUCCESS) {
            return;
        }
        if (result == RESERVE_OUT_OF_STOCK) {
            throw new BusinessException(409, "热门时段余量不足");
        }
        if (result == RESERVE_DUPLICATE) {
            throw new BusinessException(409, "当前用户已预约该时段");
        }
        if (result == RESERVE_RESOURCE_MISMATCH) {
            throw new BusinessException("时段不属于当前资源");
        }
        if (result == RESERVE_NOT_BOOKABLE) {
            throw new BusinessException(409, "热门时段当前不可预约");
        }
        throw unavailable(null);
    }

    /** Cache construction is restricted to startup and committed admin changes. */
    private void initializeIfAbsent(ResourceSlot slot) {
        if (!isOpenHotSlot(slot) || isCacheReady(slot.getId())) {
            return;
        }
        RLock lock = redissonClient.getLock(initLockKey(slot.getId()));
        boolean acquired;
        try {
            acquired = lock.tryLock(initWaitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable(exception);
        }
        if (!acquired) {
            if (!isCacheReady(slot.getId())) {
                throw unavailable(null);
            }
            return;
        }
        try {
            if (!isCacheReady(slot.getId())) {
                loadCacheFromDatabase(slot);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void loadCacheFromDatabase(ResourceSlot slot) {
        List<Reservation> reservations = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .select(Reservation::getUserId)
                .eq(Reservation::getSlotId, slot.getId())
                .eq(Reservation::getStatus, ReservationStatusConstants.BOOKED));

        List<String> arguments = new ArrayList<>();
        arguments.add(String.valueOf(slot.getResourceId()));
        arguments.add(slot.getStatus());
        arguments.add(String.valueOf(toEpochMillis(slot.getOpenTime())));
        arguments.add(String.valueOf(toEpochMillis(slot.getEndDatetime())));
        arguments.add(String.valueOf(slot.getRemainQuota()));
        arguments.add(String.valueOf(cacheTtlMillis(slot)));
        reservations.stream().map(Reservation::getUserId).map(String::valueOf).forEach(arguments::add);

        Long result = redisTemplate.execute(
                initializeScript,
                List.of(snapshotKey(slot.getId()), stockKey(slot.getId()), usersKey(slot.getId())),
                arguments.toArray()
        );
        if (result == null) {
            throw unavailable(null);
        }
    }

    private void release(Long slotId, Long userId) {
        if (slotId == null || userId == null) {
            return;
        }
        try {
            redisTemplate.execute(
                    releaseScript,
                    List.of(stockKey(slotId), usersKey(slotId)),
                    String.valueOf(userId)
            );
        } catch (RuntimeException exception) {
            log.error("Failed to compensate HOT reservation. slotId={}, userId={}", slotId, userId, exception);
        }
    }

    private boolean shouldUseRedis(ResourceSlot slot) {
        return enabled && slot != null && ResourceSlotTypeConstants.HOT.equals(slot.getSlotType());
    }

    private boolean isOpenHotSlot(ResourceSlot slot) {
        return shouldUseRedis(slot)
                && ResourceSlotStatusConstants.OPEN.equals(slot.getStatus())
                && slot.getEndDatetime() != null
                && slot.getEndDatetime().isAfter(LocalDateTime.now());
    }

    private boolean isCacheReady(Long slotId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(snapshotKey(slotId)));
    }

    private long cacheTtlMillis(ResourceSlot slot) {
        long retentionMillis = retentionAfterSlotEnd.toMillis();
        if (slot.getEndDatetime() == null) {
            return Math.max(retentionMillis, MIN_CACHE_TTL_MILLIS);
        }
        long expiry = slot.getEndDatetime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                + retentionMillis;
        return Math.max(expiry - System.currentTimeMillis(), MIN_CACHE_TTL_MILLIS);
    }

    private long toEpochMillis(LocalDateTime value) {
        return value == null ? -1L : value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private void deleteSlotCache(Long slotId) {
        redisTemplate.delete(List.of(snapshotKey(slotId), stockKey(slotId), usersKey(slotId)));
    }

    private void runAfterCommit(Runnable task) {
        Runnable safeTask = () -> {
            try {
                task.run();
            } catch (RuntimeException exception) {
                log.error("Failed to synchronize HOT reservation cache after database commit", exception);
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            safeTask.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safeTask.run();
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
        return SNAPSHOT_KEY_PREFIX + slotId;
    }

    private String stockKey(Long slotId) {
        return STOCK_KEY_PREFIX + slotId;
    }

    private String usersKey(Long slotId) {
        return USERS_KEY_PREFIX + slotId;
    }

    private String initLockKey(Long slotId) {
        return INIT_LOCK_KEY_PREFIX + slotId;
    }

    private DefaultRedisScript<Long> buildInitializeScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
                if redis.call('EXISTS', KEYS[1]) == 1 then
                    return 0
                end
                redis.call('DEL', KEYS[2], KEYS[3])
                redis.call('HSET', KEYS[1],
                    'resourceId', ARGV[1],
                    'status', ARGV[2],
                    'openAtMillis', ARGV[3],
                    'endAtMillis', ARGV[4])
                redis.call('PEXPIRE', KEYS[1], ARGV[6])
                redis.call('SET', KEYS[2], ARGV[5], 'PX', ARGV[6])
                if #ARGV > 6 then
                    for index = 7, #ARGV do
                        redis.call('SADD', KEYS[3], ARGV[index])
                    end
                    redis.call('PEXPIRE', KEYS[3], ARGV[6])
                end
                return 1
                """);
        return script;
    }

    private DefaultRedisScript<Long> buildReserveScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
                if redis.call('EXISTS', KEYS[1]) == 0 or redis.call('EXISTS', KEYS[2]) == 0 then
                    return 3
                end
                if redis.call('HGET', KEYS[1], 'resourceId') ~= ARGV[1] then
                    return 4
                end
                if redis.call('HGET', KEYS[1], 'status') ~= 'OPEN' then
                    return 5
                end
                local nowMillis = tonumber(ARGV[3])
                local openAtMillis = tonumber(redis.call('HGET', KEYS[1], 'openAtMillis') or '-1')
                local endAtMillis = tonumber(redis.call('HGET', KEYS[1], 'endAtMillis') or '-1')
                if openAtMillis < 0 or endAtMillis < 0 or nowMillis < openAtMillis or nowMillis >= endAtMillis then
                    return 5
                end
                if redis.call('SISMEMBER', KEYS[3], ARGV[2]) == 1 then
                    return 2
                end
                local stock = tonumber(redis.call('GET', KEYS[2]) or '-1')
                if stock <= 0 then
                    return 1
                end
                redis.call('DECR', KEYS[2])
                redis.call('SADD', KEYS[3], ARGV[2])
                local ttl = redis.call('PTTL', KEYS[2])
                if ttl > 0 then
                    redis.call('PEXPIRE', KEYS[3], ttl)
                end
                return 0
                """);
        return script;
    }

    private DefaultRedisScript<Long> buildReleaseScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText("""
                if redis.call('EXISTS', KEYS[1]) == 1 and redis.call('SREM', KEYS[2], ARGV[1]) == 1 then
                    redis.call('INCR', KEYS[1])
                    return 1
                end
                return 0
                """);
        return script;
    }
}
