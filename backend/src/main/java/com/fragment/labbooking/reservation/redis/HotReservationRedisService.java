package com.fragment.labbooking.reservation.redis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fragment.labbooking.reservation.model.ReservationStatusConstants;
import com.fragment.labbooking.common.constants.ResourceSlotStatusConstants;
import com.fragment.labbooking.common.constants.ResourceSlotTypeConstants;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.reservation.messaging.ReservationResultEvent;
import com.fragment.labbooking.reservation.model.Reservation;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.reservation.persistence.ReservationMapper;
import com.fragment.labbooking.mapper.ResourceSlotMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
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
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Owns the Redis state machine for preheated hot slots.
 *
 * <p>Request threads never rebuild a missing hot snapshot. Startup and committed
 * administrator changes prepare the snapshot; Lua is the only reservation
 * mutation path.</p>
 */
@Component
@Slf4j
public class HotReservationRedisService {

    private static final String SNAPSHOT_PREFIX = "reservation:hot:v2:snapshot:";
    private static final String STOCK_PREFIX = "reservation:hot:v2:stock:";
    private static final String USERS_PREFIX = "reservation:hot:v2:users:";
    private static final String REQUEST_PREFIX = "reservation:request:v2:";
    private static final String INIT_LOCK_PREFIX = "lock:reservation:hot:v2:init:";

    private static final long ACCEPTED = 0L;
    private static final long OUT_OF_STOCK = 1L;
    private static final long DUPLICATE_USER = 2L;
    private static final long NOT_READY = 3L;
    private static final long RESOURCE_MISMATCH = 4L;
    private static final long NOT_BOOKABLE = 5L;
    private static final long IDEMPOTENCY_CONFLICT = 6L;
    private static final long IDEMPOTENT_REPLAY = 10L;
    private static final long MIN_CACHE_TTL_MILLIS = 60_000L;

    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final ReservationMapper reservationMapper;
    private final ResourceSlotMapper resourceSlotMapper;
    private final boolean enabled;
    private final long initWaitMillis;
    private final Duration retentionAfterSlotEnd;
    private final Duration requestRetention;
    private final DefaultRedisScript<Long> initializeScript = initializeScript();
    private final DefaultRedisScript<Long> acceptScript = acceptScript();
    private final DefaultRedisScript<Long> completeScript = completeScript();
    private final DefaultRedisScript<Long> releaseConfirmedScript = releaseConfirmedScript();

    public HotReservationRedisService(StringRedisTemplate redisTemplate,
                                      RedissonClient redissonClient,
                                      ReservationMapper reservationMapper,
                                      ResourceSlotMapper resourceSlotMapper,
                                      @Value("${app.reservation.hot-redis-enabled:true}") boolean enabled,
                                      @Value("${app.reservation.hot-init-wait-millis:1000}") long initWaitMillis,
                                      @Value("${app.reservation.hot-cache-retention-hours:24}") long retentionHours,
                                      @Value("${app.reservation.hot-request-retention-hours:24}") long requestRetentionHours) {
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.reservationMapper = reservationMapper;
        this.resourceSlotMapper = resourceSlotMapper;
        this.enabled = enabled;
        this.initWaitMillis = initWaitMillis;
        this.retentionAfterSlotEnd = Duration.ofHours(Math.max(0, retentionHours));
        this.requestRetention = Duration.ofHours(Math.max(1, requestRetentionHours));
    }

    public boolean isPreheatedHotSlot(Long slotId) {
        if (!enabled || slotId == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(snapshotKey(slotId)));
        } catch (RuntimeException exception) {
            // The caller will query the slot type once. NORMAL slots can still
            // use MySQL; HOT slots fail closed instead of bypassing Redis.
            log.debug("Unable to inspect HOT slot snapshot. slotId={}, reason={}",
                    slotId, exception.getMessage());
            return false;
        }
    }

    /**
     * 预占入口：发半消息后由事务监听器调用，把「校验+扣库存+占用户+写请求」原子做完。
     */
    public HotRequestState accept(String requestId, Long userId, Long resourceId, Long slotId) {
        long now = System.currentTimeMillis();
        Long result;
        try {
            result = redisTemplate.execute(
                    acceptScript,
                    List.of(snapshotKey(slotId), stockKey(slotId), usersKey(slotId), requestKey(requestId)),
                    requestId,
                    String.valueOf(userId),
                    String.valueOf(resourceId),
                    String.valueOf(slotId),
                    String.valueOf(now),
                    String.valueOf(requestRetention.toMillis())
            );
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        long outcome = result == null ? NOT_READY : result;
        if (outcome == ACCEPTED) {
            // Lua 已原子写入 PRE_RESERVED；首次受理直接使用已知参数返回，避免再次 HGETALL。
            return HotRequestState.preReserved(requestId, userId, resourceId, slotId);
        }
        if (outcome == IDEMPOTENT_REPLAY) {
            // 重放时状态可能已被异步链路推进到终态，因此必须读取现有请求记录。
            HotRequestState state = getRequest(requestId);
            if (state != null) {
                return state;
            }
            throw unavailable(null);
        }
        throw rejection(outcome);
    }

    public HotRequestState getRequest(String requestId) {
        Map<Object, Object> values = redisTemplate.opsForHash().entries(requestKey(requestId));
        if (values == null || values.isEmpty()) {
            return null;
        }
        return new HotRequestState(
                text(values, "requestId"),
                number(values, "userId"),
                number(values, "resourceId"),
                number(values, "slotId"),
                text(values, "status"),
                number(values, "reservationId"),
                text(values, "reservationNo"),
                text(values, "rejectCode"),
                text(values, "rejectReason")
        );
    }

    public void complete(ReservationResultEvent event) {
        Long result = redisTemplate.execute(
                completeScript,
                List.of(stockKey(event.slotId()), usersKey(event.slotId()), requestKey(event.requestId())),
                String.valueOf(event.userId()),
                event.requestId(),
                event.status(),
                value(event.reservationId()),
                value(event.reservationNo()),
                value(event.rejectCode()),
                value(event.rejectReason()),
                String.valueOf(event.resourceId()),
                String.valueOf(event.slotId()),
                String.valueOf(requestRetention.toMillis())
        );
        if (result == null) {
            throw unavailable(null);
        }
    }

    public void releaseAfterSuccessfulCancellation(String slotType, Long slotId, Long userId) {
        if (!enabled || !ResourceSlotTypeConstants.HOT.equals(slotType) || slotId == null || userId == null) {
            return;
        }
        runAfterCommit(() -> redisTemplate.execute(
                releaseConfirmedScript,
                List.of(stockKey(slotId), usersKey(slotId)),
                String.valueOf(userId)
        ));
    }

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
        slots.forEach(slot -> {
            try {
                initializeIfAbsent(slot);
            } catch (RuntimeException exception) {
                log.warn("Failed to preheat HOT slot cache. slotId={}", slot.getId(), exception);
            }
        });
    }

    private void initializeIfAbsent(ResourceSlot slot) {
        if (!isOpenHotSlot(slot) || isPreheatedHotSlot(slot.getId())) {
            return;
        }
        RLock lock = redissonClient.getLock(INIT_LOCK_PREFIX + slot.getId());
        boolean acquired;
        try {
            acquired = lock.tryLock(initWaitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable(exception);
        }
        if (!acquired) {
            if (!isPreheatedHotSlot(slot.getId())) {
                throw unavailable(null);
            }
            return;
        }
        try {
            if (!isPreheatedHotSlot(slot.getId())) {
                loadCache(slot);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void loadCache(ResourceSlot slot) {
        List<Reservation> reservations = reservationMapper.selectList(new LambdaQueryWrapper<Reservation>()
                .select(Reservation::getUserId)
                .eq(Reservation::getSlotId, slot.getId())
                .eq(Reservation::getStatus, ReservationStatusConstants.BOOKED));

        List<String> args = new ArrayList<>();
        args.add(String.valueOf(slot.getResourceId()));
        args.add(slot.getStatus());
        args.add(String.valueOf(epochMillis(slot.getOpenTime())));
        args.add(String.valueOf(epochMillis(slot.getEndDatetime())));
        args.add(String.valueOf(slot.getRemainQuota()));
        args.add(String.valueOf(cacheTtlMillis(slot)));
        reservations.stream().map(Reservation::getUserId).map(String::valueOf).forEach(args::add);

        Long result = redisTemplate.execute(
                initializeScript,
                List.of(snapshotKey(slot.getId()), stockKey(slot.getId()), usersKey(slot.getId())),
                args.toArray()
        );
        if (result == null) {
            throw unavailable(null);
        }
    }

    /** 把 Lua 的失败状态码翻译成接口业务异常。 */
    private BusinessException rejection(long result) {
        if (result == OUT_OF_STOCK) {
            return new BusinessException(409, "热门时段余量不足");
        }
        if (result == DUPLICATE_USER) {
            return new BusinessException(409, "当前用户已提交该时段的预约");
        }
        if (result == RESOURCE_MISMATCH) {
            return new BusinessException(400, "时段不属于当前资源");
        }
        if (result == NOT_BOOKABLE) {
            return new BusinessException(409, "热门时段当前不可预约");
        }
        if (result == IDEMPOTENCY_CONFLICT) {
            return new BusinessException(409, "Idempotency-Key 已用于其他预约请求");
        }
        return unavailable(null);
    }

    private boolean isOpenHotSlot(ResourceSlot slot) {
        return slot != null
                && ResourceSlotTypeConstants.HOT.equals(slot.getSlotType())
                && ResourceSlotStatusConstants.OPEN.equals(slot.getStatus())
                && slot.getEndDatetime() != null
                && slot.getEndDatetime().isAfter(LocalDateTime.now());
    }

    private long cacheTtlMillis(ResourceSlot slot) {
        long expiry = epochMillis(slot.getEndDatetime()) + retentionAfterSlotEnd.toMillis();
        return Math.max(expiry - System.currentTimeMillis(), MIN_CACHE_TTL_MILLIS);
    }

    private long epochMillis(LocalDateTime value) {
        return value == null ? -1L : value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private void deleteSlotCache(Long slotId) {
        redisTemplate.delete(List.of(snapshotKey(slotId), stockKey(slotId), usersKey(slotId)));
    }

    private void runAfterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
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
        return SNAPSHOT_PREFIX + slotId;
    }

    private String stockKey(Long slotId) {
        return STOCK_PREFIX + slotId;
    }

    private String usersKey(Long slotId) {
        return USERS_PREFIX + slotId;
    }

    private String requestKey(String requestId) {
        return REQUEST_PREFIX + requestId;
    }

    private String text(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private Long number(Map<Object, Object> values, String key) {
        String value = text(values, key);
        return value == null ? null : Long.valueOf(value);
    }

    private String value(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * 预热脚本：把 MySQL 里的热门时段名额，初始化成 Redis 里的三把 key。
     * 只在应用启动或管理员改时段后调用（见 loadCache()）。
     *
     * 参数约定：
     *   KEYS[1] = snapshot   KEYS[2] = stock   KEYS[3] = users
     *   ARGV[1] = resourceId  ARGV[2] = status(OPEN)  ARGV[3] = openAt毫秒
     *   ARGV[4] = endAt毫秒   ARGV[5] = 剩余名额   ARGV[6] = 缓存存活毫秒
     *   ARGV[7..] = 已 BOOKED 的 userId 列表（来自 MySQL，逐个回填）
     */
    private DefaultRedisScript<Long> initializeScript() {
        return script("""
                -- 快照已存在 → 说明已预热过，直接返回，绝不覆盖当前库存（防止把正在抢的库存重置）
                if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
                redis.call('DEL', KEYS[2], KEYS[3])   -- 先清掉旧库存和用户集合，防止上一次的残留
                redis.call('HSET', KEYS[1],           -- 写时段快照：这个时段属于哪个资源、开放/结束时间
                    'resourceId', ARGV[1], 'status', ARGV[2],
                    'openAtMillis', ARGV[3], 'endAtMillis', ARGV[4])
                redis.call('PEXPIRE', KEYS[1], ARGV[6])   -- 快照带过期时间
                redis.call('SET', KEYS[2], ARGV[5], 'PX', ARGV[6])  -- 库存 = MySQL 剩余名额，带过期
                for index = 7, #ARGV do
                    -- 把 MySQL 里已 BOOKED 的用户回填进 users 集合，防止「已被占的名额」被重复抢
                    redis.call('HSET', KEYS[3], ARGV[index], 'BOOKED')
                end
                if #ARGV >= 7 then redis.call('PEXPIRE', KEYS[3], ARGV[6]) end
                return 1  -- 预热成功
                """);
    }

    /**
     * 预占脚本：一次原子调用完成「校验 + 扣库存 + 占用户位 + 写请求记录」。
     *
     * 参数约定（看 Java 侧 accept() 调用顺序）：
     *   KEYS[1] = snapshot（时段快照 hash，如 reservation:hot:v2:snapshot:5）
     *   KEYS[2] = stock   （剩余库存 string）
     *   KEYS[3] = users   （已占用用户 hash，key=userId, value=requestId）
     *   KEYS[4] = request （本次请求 hash，如 reservation:request:v2:<requestId>）
     *   ARGV[1] = requestId   ARGV[2] = userId    ARGV[3] = resourceId
     *   ARGV[4] = slotId      ARGV[5] = now
     *   ARGV[6] = requestTtl（请求记录存活时长，仅用于缓存清理）
     *
     * 返回值（对照类顶部常量）：
     *   0=成功占位  1=库存不足  2=该用户已占过  3=快照没预热/已失效
     *   4=时段不属于该资源  5=不可预约(未开放/已结束/非OPEN)  6=幂等键冲突
     *   10=完全相同的请求重放(幂等，按成功处理)
     */
    private DefaultRedisScript<Long> acceptScript() {
        return script("""
                -- ① 幂等检查：这个 requestId 之前处理过吗？
                if redis.call('EXISTS', KEYS[4]) == 1 then
                    -- 已存在，但用户/资源/时段对不上 → 同一个幂等键被不同请求复用 → 冲突(6)
                    if redis.call('HGET', KEYS[4], 'userId') ~= ARGV[2]
                        or redis.call('HGET', KEYS[4], 'resourceId') ~= ARGV[3]
                        or redis.call('HGET', KEYS[4], 'slotId') ~= ARGV[4] then
                        return 6
                    end
                    -- 完全一致 → 用户用同一个 Idempotency-Key 重试 → 幂等重放(10)，Java 侧按成功处理
                    return 10
                end
                -- ② 快照或库存不存在 → 时段还没预热，或缓存已过期 → 通道不可用(3)
                if redis.call('EXISTS', KEYS[1]) == 0 or redis.call('EXISTS', KEYS[2]) == 0 then
                    return 3
                end
                -- ③ 快照里登记的 resourceId 和请求对不上 → 时段不属于该资源(4)
                if redis.call('HGET', KEYS[1], 'resourceId') ~= ARGV[3] then return 4 end
                -- ④ 时段状态不是 OPEN（可能被管理员关闭）→ 不可预约(5)
                if redis.call('HGET', KEYS[1], 'status') ~= 'OPEN' then return 5 end
                -- ⑤ 时间窗口：现在必须落在 [openAt, endAt) 内，否则不可预约(5)
                local now = tonumber(ARGV[5])
                local openAt = tonumber(redis.call('HGET', KEYS[1], 'openAtMillis') or '-1')
                local endAt = tonumber(redis.call('HGET', KEYS[1], 'endAtMillis') or '-1')
                if openAt < 0 or endAt < 0 or now < openAt or now >= endAt then return 5 end
                -- ⑥ 该 userId 已经在 users 集合里 → 这个用户已占过该时段 → 重复预约(2)
                if redis.call('HEXISTS', KEYS[3], ARGV[2]) == 1 then return 2 end
                -- ⑦ 库存检查：剩余名额 <= 0 → 抢光了(1)
                local stock = tonumber(redis.call('GET', KEYS[2]) or '-1')
                if stock <= 0 then return 1 end

                -- ⑧ 全部校验通过，开始「占位」（这几步必须原子，缺一步就会超卖/漏占）：
                redis.call('DECR', KEYS[2])                                  -- 库存 -1
                redis.call('HSET', KEYS[3], ARGV[2], ARGV[1])                -- 记录用户占位：userId -> requestId
                local stockTtl = redis.call('PTTL', KEYS[2])                 -- 让 users 集合跟库存一起过期，防脏数据残留
                if stockTtl > 0 then redis.call('PEXPIRE', KEYS[3], stockTtl) end
                redis.call('HSET', KEYS[4],                                  -- 写请求记录，标记 PRE_RESERVED(预占成功，等异步确认)
                    'requestId', ARGV[1], 'userId', ARGV[2],
                    'resourceId', ARGV[3], 'slotId', ARGV[4],
                    'status', 'PRE_RESERVED', 'createdAt', ARGV[5])
                redis.call('PEXPIRE', KEYS[4], ARGV[6])
                return 0  -- 成功
                """);
    }

    /**
     * 最终确认脚本：消费端落库完成后调用（见 complete()），把请求 hash
     * 从 PRE_RESERVED 收敛成最终态（CONFIRMED / REJECTED）。
     * 只有非 CONFIRMED 的终态才会释放占位并回补库存。
     *
     * 参数约定：
     *   KEYS[1] = stock   KEYS[2] = users   KEYS[3] = request
     *   ARGV[1] = userId      ARGV[2] = requestId   ARGV[3] = 最终状态
     *   ARGV[4] = reservationId  ARGV[5] = reservationNo  ARGV[6] = rejectCode
     *   ARGV[7] = rejectReason   ARGV[8] = resourceId   ARGV[9] = slotId
     *   ARGV[10] = 请求记录存活时长
     */
    private DefaultRedisScript<Long> completeScript() {
        return script("""
                -- 已是终态 → 幂等返回，不重复处理（消息可能被投递多次）
                local current = redis.call('HGET', KEYS[3], 'status')
                if current == 'CONFIRMED' or current == 'REJECTED' then
                    return 0
                end
                -- REJECTED → 释放占位、回补库存：
                if ARGV[3] ~= 'CONFIRMED' then
                    local ownerRequest = redis.call('HGET', KEYS[2], ARGV[1])  -- 这个用户占位记录里的 requestId
                    if ownerRequest == ARGV[2] then                            -- 确实是本次请求占的位才释放
                        redis.call('HDEL', KEYS[2], ARGV[1])                   -- 移除用户占位
                        if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('INCR', KEYS[1]) end  -- 库存 +1 归还
                    end
                end
                -- 把请求 hash 更新成最终状态，供 GET /reservation/requests 查询
                redis.call('HSET', KEYS[3], 'status', ARGV[3],
                    'requestId', ARGV[2], 'userId', ARGV[1],
                    'resourceId', ARGV[8], 'slotId', ARGV[9],
                    'reservationId', ARGV[4], 'reservationNo', ARGV[5],
                    'rejectCode', ARGV[6], 'rejectReason', ARGV[7])
                redis.call('PEXPIRE', KEYS[3], ARGV[10])
                return 1
                """);
    }

    /**
     * 取消回补脚本：用户主动取消预约后调用（见 releaseAfterSuccessfulCancellation()），
     * 把该用户从 users 集合移除，并给库存 +1。
     */
    private DefaultRedisScript<Long> releaseConfirmedScript() {
        return script("""
                -- 只有真的删掉了占位记录才回补库存（HDEL 返回 1 = 删掉了）
                if redis.call('HDEL', KEYS[2], ARGV[1]) == 1 then
                    if redis.call('EXISTS', KEYS[1]) == 1 then redis.call('INCR', KEYS[1]) end
                    return 1
                end
                return 0
                """);
    }

    private DefaultRedisScript<Long> script(String text) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setResultType(Long.class);
        script.setScriptText(text);
        return script;
    }

    public record HotRequestState(
            String requestId,
            Long userId,
            Long resourceId,
            Long slotId,
            String status,
            Long reservationId,
            String reservationNo,
            String rejectCode,
            String rejectReason
    ) {
        static HotRequestState preReserved(String requestId, Long userId, Long resourceId, Long slotId) {
            return new HotRequestState(
                    requestId, userId, resourceId, slotId,
                    "PRE_RESERVED", null, null, null, null);
        }
    }
}
