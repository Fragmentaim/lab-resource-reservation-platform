package com.fragment.labbooking.service.reservation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fragment.labbooking.common.constants.ReservationStatusConstants;
import com.fragment.labbooking.common.constants.ResourceSlotStatusConstants;
import com.fragment.labbooking.common.constants.ResourceSlotTypeConstants;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.common.redis.HotReservationRedisService;
import com.fragment.labbooking.common.redis.ReservationRateLimiter;
import com.fragment.labbooking.common.reservation.ReservationAutoCancelService;
import com.fragment.labbooking.common.reservation.ReservationPersistenceHelper;
import com.fragment.labbooking.dto.ReservationCancelDTO;
import com.fragment.labbooking.dto.ReservationCreateDTO;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.entity.Resource;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.mapper.ReservationMapper;
import com.fragment.labbooking.service.ReservationReminderTaskService;
import com.fragment.labbooking.service.ResourceService;
import com.fragment.labbooking.service.ResourceSlotService;
import com.fragment.labbooking.vo.ReservationSubmitVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 预约命令入口
 */
@Service
public class ReservationCommandService {

    private final ReservationMapper reservationMapper;
    private final ResourceService resourceService;
    private final ResourceSlotService resourceSlotService;
    private final HotReservationRedisService hotRedis;
    private final ReservationRateLimiter rateLimiter;
    private final ReservationPersistenceHelper persistenceHelper;
    private final ReservationReminderTaskService reminderService;
    private final ReservationAutoCancelService autoCancelService;
    private final HotReservationRequestService hotRequestService;
    private final TransactionTemplate transactionTemplate;
    private final long checkInBeforeStartMinutes;

    public ReservationCommandService(ReservationMapper reservationMapper,
                                     ResourceService resourceService,
                                     ResourceSlotService resourceSlotService,
                                     HotReservationRedisService hotRedis,
                                     ReservationRateLimiter rateLimiter,
                                     ReservationPersistenceHelper persistenceHelper,
                                     ReservationReminderTaskService reminderService,
                                     ReservationAutoCancelService autoCancelService,
                                     HotReservationRequestService hotRequestService,
                                     PlatformTransactionManager transactionManager,
                                     @Value("${app.reservation.auto-cancel.check-in-before-start-minutes:30}") long checkInBeforeStartMinutes) {
        this.reservationMapper = reservationMapper;
        this.resourceService = resourceService;
        this.resourceSlotService = resourceSlotService;
        this.hotRedis = hotRedis;
        this.rateLimiter = rateLimiter;
        this.persistenceHelper = persistenceHelper;
        this.reminderService = reminderService;
        this.autoCancelService = autoCancelService;
        this.hotRequestService = hotRequestService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.checkInBeforeStartMinutes = checkInBeforeStartMinutes;
    }

    /**
     * 预约统一入口：
     */
    public ReservationSubmitVO create(Long userId, String idempotencyKey, ReservationCreateDTO dto) {
        // 归一化 Idempotency-Key → 稳定 UUID。
        String requestId = hotRequestService.normalizeRequestId(idempotencyKey);

        if (hotRedis.isPreheatedHotSlot(dto.getSlotId())) {
            // 热门预约先限流
            rateLimiter.checkCreateReservationLimit(userId, ResourceSlotTypeConstants.HOT);
            // 直接交给热门受理器
            return hotRequestService.accept(requestId, userId, dto.getResourceId(), dto.getSlotId());
        }

        // 普通通道--前置校验
        ResourceSlot routeSlot = resourceSlotService.getById(dto.getSlotId());
        if (routeSlot == null) {
            throw new BusinessException("时段不存在");
        }
        if (!dto.getResourceId().equals(routeSlot.getResourceId())) {
            throw new BusinessException("时段不属于当前资源");
        }

        if (ResourceSlotTypeConstants.HOT.equals(routeSlot.getSlotType())) {
            // 预热还没完成，拒绝，
            throw new BusinessException(503, "热门时段尚未完成预热，请稍后重试");
        }
        // 普通预约也限流（阈值不同），然后进入真正的 MySQL 事务扣减。
        rateLimiter.checkCreateReservationLimit(userId, ResourceSlotTypeConstants.NORMAL);
        // transactionTemplate.execute：手工包一个事务，事务内做"校验+扣库存+落库"，全成功才提交。
        return Objects.requireNonNull(transactionTemplate.execute(status ->
                createNormalInTransaction(userId, requestId, dto)));
    }

    /**
     * 按 requestId 查询本人预约请求的结果 委托给热门受理器
     */
    public ReservationSubmitVO getRequest(Long userId, String requestId) {
        return hotRequestService.getRequest(userId, requestId);
    }

    /**
     * 查资源→查时段→可预约校验→查重→扣 MySQL 名额→落预约表→建提醒→排自动取消。
     */
    private ReservationSubmitVO createNormalInTransaction(Long userId, String requestId,
                                                           ReservationCreateDTO dto) {
        // 资源和时段都必须存在，且时段真的属于这个资源。
        Resource resource = resourceService.getById(dto.getResourceId());
        if (resource == null) {
            throw new BusinessException("资源不存在");
        }
        ResourceSlot slot = resourceSlotService.getById(dto.getSlotId());
        if (slot == null) {
            throw new BusinessException("时段不存在");
        }
        if (!dto.getResourceId().equals(slot.getResourceId())) {
            throw new BusinessException("时段不属于当前资源");
        }

        // 可预约性校验。
        validateBookable(slot);
        // 热门必须走 Redis 通道。
        if (ResourceSlotTypeConstants.HOT.equals(slot.getSlotType())) {
            throw new BusinessException(503, "热门时段必须通过 Redis 预约通道");
        }
        // 同一个用户不能 BOOKED 状态下重复预约同一时段。
        checkDuplicate(userId, dto.getSlotId());

        // 条件扣减 MySQL 名额
        resourceSlotService.deductQuotaIfAvailable(dto.getSlotId());
        // 组装一条 Reservation 实体并落库（saveWithRetry：偶发失败会自动重试）。
        Reservation reservation = persistenceHelper.buildReservation(
                userId, dto.getResourceId(), dto.getSlotId(), resource, slot);
        persistenceHelper.saveWithRetry(reservation);
        // 建一条"开课前提醒"定时任务。
        reminderService.createBeforeStartReminder(reservation);
        // 安排"未签到自动取消"任务。
        autoCancelService.schedule(reservation);
        // 同步返回 CONFIRMED
        return syncResult(requestId, reservation);
    }

    /**
     * 签到：用条件更新保证并发安全。
     */
    @Transactional(rollbackFor = Exception.class)
    public void checkIn(Long userId, Long id) {
        // 查预约，必须存在且属于自己。
        if (id == null) {
            throw new BusinessException("预约ID不能为空");
        }
        Reservation reservation = requireReservation(id);
        if (!reservation.getUserId().equals(userId)) {
            throw new BusinessException(403, "不能签到他人的预约");
        }
        // 已经签过就直接返回（幂等）。
        if (reservation.getCheckedInAt() != null) {
            return;
        }
        // 必须还是 BOOKED 状态才允许签到。
        if (!ReservationStatusConstants.BOOKED.equals(reservation.getStatus())) {
            throw new BusinessException("当前预约状态不允许签到");
        }
        // 签到时间窗口：不能太早（开始前 N 分钟才放），也不能太晚（过了自动取消截止）。
        validateCheckInWindow(reservation);

        LocalDateTime now = LocalDateTime.now();
        // 条件更新：只有"仍 BOOKED 且未签到"才能改成已签到，防止并发重复签到。
        int updated = reservationMapper.update(null, new LambdaUpdateWrapper<Reservation>()
                .eq(Reservation::getId, id)
                .eq(Reservation::getUserId, userId)
                .eq(Reservation::getStatus, ReservationStatusConstants.BOOKED)
                .isNull(Reservation::getCheckedInAt)
                .set(Reservation::getCheckedInAt, now)
                .set(Reservation::getUpdatedAt, now));
        if (updated <= 0) {
            // 更新 0 行：可能已被其他线程签到。再读一次确认，避免误报。
            Reservation latest = reservationMapper.selectById(id);
            if (latest != null && latest.getCheckedInAt() != null) {
                return;
            }
            throw new BusinessException("当前预约状态不允许签到");
        }
    }

    /**
     * 取消预约
     * 成功后：还 MySQL 名额、取消提醒任务、最后回补 Redis 热门时段的占位和库存。
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long userId, Long id, ReservationCancelDTO dto) {
        // 必须是自己的、存在的预约。
        Reservation reservation = requireReservation(id);
        if (!reservation.getUserId().equals(userId)) {
            throw new BusinessException("不能取消他人的预约");
        }
        // 可选的取消原因。
        String reason = StringUtils.hasText(dto.getCancelReason()) ? dto.getCancelReason().trim() : null;
        // 条件更新：只有 BOOKED 才允许取消，返回 0 行说明状态已变（如已签到/已取消）。
        int updated = reservationMapper.update(null, new LambdaUpdateWrapper<Reservation>()
                .eq(Reservation::getId, id)
                .eq(Reservation::getUserId, userId)
                .eq(Reservation::getStatus, ReservationStatusConstants.BOOKED)
                .set(Reservation::getStatus, ReservationStatusConstants.CANCELLED)
                .set(Reservation::getCancelReason, reason)
                .setSql("is_active = NULL"));
        if (updated <= 0) {
            throw new BusinessException("当前预约状态不允许取消");
        }
        // 还 MySQL 名额
        resourceSlotService.restoreQuota(reservation.getSlotId());
        // 取消待触发的提醒任务。
        reminderService.cancelPendingByReservationId(reservation.getId());
        // 如果是热门时段的预约：回补 Redis 里被占的位和库存
        hotRedis.releaseAfterSuccessfulCancellation(
                reservation.getSourceType(), reservation.getSlotId(), reservation.getUserId());
    }

    private Reservation requireReservation(Long id) {
        Reservation reservation = reservationMapper.selectById(id);
        if (reservation == null) {
            throw new BusinessException("预约记录不存在");
        }
        return reservation;
    }

    private void validateBookable(ResourceSlot slot) {
        if (!ResourceSlotStatusConstants.OPEN.equals(slot.getStatus())) {
            throw new BusinessException("当前时段不可预约");
        }
        if (slot.getEndDatetime() == null || !LocalDateTime.now().isBefore(slot.getEndDatetime())) {
            throw new BusinessException("当前时段已结束");
        }
        if (ResourceSlotTypeConstants.HOT.equals(slot.getSlotType())) {
            if (slot.getOpenTime() == null) {
                throw new BusinessException("热门时段未配置开放预约时间");
            }
            if (LocalDateTime.now().isBefore(slot.getOpenTime())) {
                throw new BusinessException("热门时段尚未开放预约");
            }
        }
    }

    private void checkDuplicate(Long userId, Long slotId) {
        Long count = reservationMapper.selectCount(new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getUserId, userId)
                .eq(Reservation::getSlotId, slotId)
                .eq(Reservation::getStatus, ReservationStatusConstants.BOOKED));
        if (count != null && count > 0) {
            throw new BusinessException(409, "当前用户已预约该时段");
        }
    }

    private void validateCheckInWindow(Reservation reservation) {
        LocalDateTime start = reservation.getSlotStartDatetime();
        LocalDateTime deadline = autoCancelService.resolveAutoCancelDeadline(reservation);
        if (start == null || deadline == null) {
            throw new BusinessException("预约时间不完整，无法签到");
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(start.minusMinutes(Math.max(checkInBeforeStartMinutes, 0)))) {
            throw new BusinessException("未到签到时间");
        }
        if (now.isAfter(deadline)) {
            throw new BusinessException("签到已超时");
        }
    }

    private ReservationSubmitVO syncResult(String requestId, Reservation reservation) {
        ReservationSubmitVO result = new ReservationSubmitVO();
        result.setRequestId(requestId);
        result.setStatus(ReservationRequestStatus.CONFIRMED.name());
        result.setReservationId(reservation.getId());
        result.setReservationNo(reservation.getReservationNo());
        result.setMessage("预约成功");
        return result;
    }
}
