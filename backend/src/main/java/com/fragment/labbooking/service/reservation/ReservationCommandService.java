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
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.checkInBeforeStartMinutes = checkInBeforeStartMinutes;
    }

    public ReservationSubmitVO create(Long userId, ReservationCreateDTO dto) {
        boolean preheated = hotRedis.isPreheatedHotSlot(dto.getSlotId());
        if (preheated) {
            rateLimiter.checkCreateReservationLimit(userId, ResourceSlotTypeConstants.HOT);
        }
        boolean reservedByFastPath = hotRedis.reserveIfPreheated(dto.getResourceId(), dto.getSlotId(), userId);
        return Objects.requireNonNull(transactionTemplate.execute(status ->
                createInTransaction(userId, dto, preheated, reservedByFastPath)));
    }

    private ReservationSubmitVO createInTransaction(Long userId, ReservationCreateDTO dto,
                                                      boolean preheated, boolean reservedByFastPath) {
        if (reservedByFastPath) {
            hotRedis.registerPreheatedReservationRollback(dto.getSlotId(), userId);
        }
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
        validateBookable(slot);
        if (!preheated) {
            rateLimiter.checkCreateReservationLimit(userId, slot.getSlotType());
        }
        if (!reservedByFastPath) {
            hotRedis.reserveAndRegisterRollback(slot, userId);
        }
        checkDuplicate(userId, dto.getSlotId());

        resourceSlotService.deductQuotaIfAvailable(dto.getSlotId());
        Reservation reservation = persistenceHelper.buildReservation(
                userId, dto.getResourceId(), dto.getSlotId(), resource, slot);
        persistenceHelper.saveWithRetry(reservation);
        reminderService.createBeforeStartReminder(reservation);
        autoCancelService.schedule(reservation);
        return syncResult(reservation);
    }

    @Transactional(rollbackFor = Exception.class)
    public void checkIn(Long userId, Long id) {
        if (id == null) {
            throw new BusinessException("预约ID不能为空");
        }
        Reservation reservation = requireReservation(id);
        if (!reservation.getUserId().equals(userId)) {
            throw new BusinessException(403, "不能签到他人的预约");
        }
        if (reservation.getCheckedInAt() != null) {
            return;
        }
        if (!ReservationStatusConstants.BOOKED.equals(reservation.getStatus())) {
            throw new BusinessException("当前预约状态不允许签到");
        }
        validateCheckInWindow(reservation);

        LocalDateTime now = LocalDateTime.now();
        int updated = reservationMapper.update(null, new LambdaUpdateWrapper<Reservation>()
                .eq(Reservation::getId, id)
                .eq(Reservation::getUserId, userId)
                .eq(Reservation::getStatus, ReservationStatusConstants.BOOKED)
                .isNull(Reservation::getCheckedInAt)
                .set(Reservation::getCheckedInAt, now)
                .set(Reservation::getUpdatedAt, now));
        if (updated <= 0) {
            Reservation latest = reservationMapper.selectById(id);
            if (latest != null && latest.getCheckedInAt() != null) {
                return;
            }
            throw new BusinessException("当前预约状态不允许签到");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long userId, Long id, ReservationCancelDTO dto) {
        Reservation reservation = requireReservation(id);
        if (!reservation.getUserId().equals(userId)) {
            throw new BusinessException("不能取消他人的预约");
        }
        String reason = StringUtils.hasText(dto.getCancelReason()) ? dto.getCancelReason().trim() : null;
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
        resourceSlotService.restoreQuota(reservation.getSlotId());
        reminderService.cancelPendingByReservationId(reservation.getId());
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

    private ReservationSubmitVO syncResult(Reservation reservation) {
        ReservationSubmitVO result = new ReservationSubmitVO();
        result.setReservationId(reservation.getId());
        result.setReservationNo(reservation.getReservationNo());
        result.setMessage("预约成功");
        return result;
    }
}
