package com.fragment.labbooking.common.reservation;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fragment.labbooking.common.constants.ReservationStatusConstants;
import com.fragment.labbooking.common.delay.DelayMessageEventTypes;
import com.fragment.labbooking.common.delay.DelayMessageOutboxService;
import com.fragment.labbooking.common.delay.ReservationAutoCancelDelayPayload;
import com.fragment.labbooking.common.redis.HotReservationRedisService;
import com.fragment.labbooking.common.redis.ResourceRedisCacheService;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.mapper.ReservationMapper;
import com.fragment.labbooking.service.ResourceSlotService;
import com.fragment.labbooking.service.UserNotificationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class ReservationAutoCancelService {

    public static final String AUTO_CANCEL_REASON = "超时未签到自动取消";

    private final ReservationMapper reservationMapper;
    private final ResourceSlotService resourceSlotService;
    private final HotReservationRedisService hotReservationRedisService;
    private final ResourceRedisCacheService resourceRedisCacheService;
    private final UserNotificationService userNotificationService;
    private final DelayMessageOutboxService delayMessageOutboxService;
    private final boolean enabled;
    private final long graceMinutes;

    public ReservationAutoCancelService(ReservationMapper reservationMapper,
                                        ResourceSlotService resourceSlotService,
                                        HotReservationRedisService hotReservationRedisService,
                                        ResourceRedisCacheService resourceRedisCacheService,
                                        UserNotificationService userNotificationService,
                                        DelayMessageOutboxService delayMessageOutboxService,
                                        @Value("${app.reservation.auto-cancel.enabled:true}") boolean enabled,
                                        @Value("${app.reservation.auto-cancel.grace-minutes:15}") long graceMinutes) {
        this.reservationMapper = reservationMapper;
        this.resourceSlotService = resourceSlotService;
        this.hotReservationRedisService = hotReservationRedisService;
        this.resourceRedisCacheService = resourceRedisCacheService;
        this.userNotificationService = userNotificationService;
        this.delayMessageOutboxService = delayMessageOutboxService;
        this.enabled = enabled;
        this.graceMinutes = Math.max(graceMinutes, 0);
    }

    public void fillAutoCancelDeadline(Reservation reservation) {
        if (!enabled || reservation == null || reservation.getSlotStartDatetime() == null) {
            return;
        }
        reservation.setAutoCancelDeadline(resolveAutoCancelDeadline(reservation));
    }

    public void schedule(Reservation reservation) {
        if (!enabled
                || reservation == null
                || reservation.getId() == null
                || reservation.getAutoCancelDeadline() == null) {
            return;
        }

        delayMessageOutboxService.enqueue(
                DelayMessageEventTypes.RESERVATION_AUTO_CANCEL,
                String.valueOf(reservation.getId()),
                reservation.getAutoCancelDeadline(),
                new ReservationAutoCancelDelayPayload(reservation.getId())
        );
    }

    public LocalDateTime resolveAutoCancelDeadline(Reservation reservation) {
        if (reservation == null) {
            return null;
        }
        if (reservation.getAutoCancelDeadline() != null) {
            return reservation.getAutoCancelDeadline();
        }
        if (reservation.getSlotStartDatetime() == null) {
            return null;
        }
        return reservation.getSlotStartDatetime().plusMinutes(graceMinutes);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean autoCancel(Long reservationId) {
        if (!enabled || reservationId == null) {
            return false;
        }

        Reservation reservation = reservationMapper.selectById(reservationId);
        if (reservation == null
                || !ReservationStatusConstants.BOOKED.equals(reservation.getStatus())
                || reservation.getCheckedInAt() != null) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = resolveAutoCancelDeadline(reservation);
        if (deadline != null && now.isBefore(deadline)) {
            return false;
        }

        int updatedRows = reservationMapper.update(null, new LambdaUpdateWrapper<Reservation>()
                .eq(Reservation::getId, reservationId)
                .eq(Reservation::getStatus, ReservationStatusConstants.BOOKED)
                .isNull(Reservation::getCheckedInAt)
                .set(Reservation::getStatus, ReservationStatusConstants.CANCELLED)
                .set(Reservation::getCancelReason, AUTO_CANCEL_REASON)
                .set(Reservation::getUpdatedAt, now)
                .setSql("is_active = NULL"));
        if (updatedRows <= 0) {
            return false;
        }

        resourceSlotService.restoreQuota(reservation.getSlotId());
        resourceRedisCacheService.invalidateResourceSlotList(reservation.getResourceId());
        hotReservationRedisService.releaseAfterSuccessfulCancellation(
                reservation.getSourceType(),
                reservation.getSlotId(),
                reservation.getUserId()
        );
        userNotificationService.createAutoCancelNotification(reservation);
        return true;
    }
}
