package com.fragment.labbooking.service.reservation;

import com.fragment.labbooking.common.redis.HotReservationRedisService;
import com.fragment.labbooking.common.reservation.ReservationAutoCancelService;
import com.fragment.labbooking.common.reservation.ReservationResultEvent;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.mapper.ReservationMapper;
import com.fragment.labbooking.service.ReservationReminderTaskService;
import com.fragment.labbooking.service.UserNotificationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationResultService {

    private final HotReservationRedisService hotRedis;
    private final ReservationMapper reservationMapper;
    private final ReservationReminderTaskService reminderService;
    private final ReservationAutoCancelService autoCancelService;
    private final UserNotificationService notificationService;

    public ReservationResultService(HotReservationRedisService hotRedis,
                                    ReservationMapper reservationMapper,
                                    ReservationReminderTaskService reminderService,
                                    ReservationAutoCancelService autoCancelService,
                                    UserNotificationService notificationService) {
        this.hotRedis = hotRedis;
        this.reservationMapper = reservationMapper;
        this.reminderService = reminderService;
        this.autoCancelService = autoCancelService;
        this.notificationService = notificationService;
    }

    /**
     * Applies one durable result event. Redis completion and all downstream
     * database writes are idempotent, so RocketMQ redelivery is safe.
     */
    @Transactional(rollbackFor = Exception.class)
    public void handle(String eventId, ReservationResultEvent event) {
        validate(eventId, event);
        hotRedis.complete(event);
        notificationService.createReservationResultNotification(eventId, event);

        if (!ReservationRequestStatus.CONFIRMED.name().equals(event.status())) {
            return;
        }
        Reservation reservation = reservationMapper.selectById(event.reservationId());
        if (reservation == null) {
            throw new IllegalStateException("Confirmed reservation does not exist: " + event.reservationId());
        }
        reminderService.createBeforeStartReminder(reservation);
        autoCancelService.schedule(reservation);
    }

    private void validate(String eventId, ReservationResultEvent event) {
        if (eventId == null || event == null || event.requestId() == null
                || event.userId() == null || event.slotId() == null || event.status() == null) {
            throw new IllegalArgumentException("Invalid reservation result event");
        }
    }
}
