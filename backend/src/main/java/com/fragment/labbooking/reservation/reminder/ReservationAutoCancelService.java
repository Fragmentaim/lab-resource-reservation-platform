package com.fragment.labbooking.reservation.reminder;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fragment.labbooking.reservation.model.ReservationStatusConstants;
import com.fragment.labbooking.reservation.reminder.messaging.DelayMessageEventTypes;
import com.fragment.labbooking.reservation.reminder.messaging.DelayMessageTags;
import com.fragment.labbooking.reservation.reminder.messaging.ReservationAutoCancelDelayPayload;
import com.fragment.labbooking.common.outbox.MessageOutboxService;
import com.fragment.labbooking.reservation.redis.HotReservationRedisService;
import com.fragment.labbooking.reservation.model.Reservation;
import com.fragment.labbooking.reservation.persistence.ReservationMapper;
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
    private final UserNotificationService userNotificationService;
    private final MessageOutboxService messageOutboxService;
    private final String delayTopic;
    private final boolean enabled;
    private final long graceMinutes;

    public ReservationAutoCancelService(ReservationMapper reservationMapper,
                                        ResourceSlotService resourceSlotService,
                                        HotReservationRedisService hotReservationRedisService,
                                        UserNotificationService userNotificationService,
                                        MessageOutboxService messageOutboxService,
                                        @Value("${app.message-outbox.delay-topic:reservation-delay}") String delayTopic,
                                        @Value("${app.reservation.auto-cancel.enabled:true}") boolean enabled,
                                        @Value("${app.reservation.auto-cancel.grace-minutes:15}") long graceMinutes) {
        this.reservationMapper = reservationMapper;
        this.resourceSlotService = resourceSlotService;
        this.hotReservationRedisService = hotReservationRedisService;
        this.userNotificationService = userNotificationService;
        this.messageOutboxService = messageOutboxService;
        this.delayTopic = delayTopic;
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

        messageOutboxService.enqueue(
                "RESERVATION",
                String.valueOf(reservation.getId()),
                DelayMessageEventTypes.RESERVATION_AUTO_CANCEL,
                delayTopic,
                DelayMessageTags.tagFor(DelayMessageEventTypes.RESERVATION_AUTO_CANCEL),
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
        hotReservationRedisService.releaseAfterSuccessfulCancellation(
                reservation.getSourceType(),
                reservation.getSlotId(),
                reservation.getUserId()
        );
        userNotificationService.createAutoCancelNotification(reservation);
        return true;
    }
}
