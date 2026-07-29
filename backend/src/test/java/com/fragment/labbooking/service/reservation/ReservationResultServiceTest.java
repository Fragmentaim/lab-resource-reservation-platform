package com.fragment.labbooking.service.reservation;

import com.fragment.labbooking.common.redis.HotReservationRedisService;
import com.fragment.labbooking.common.reservation.ReservationAutoCancelService;
import com.fragment.labbooking.common.reservation.ReservationResultEvent;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.mapper.ReservationMapper;
import com.fragment.labbooking.service.ReservationReminderTaskService;
import com.fragment.labbooking.service.UserNotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationResultServiceTest {

    @Mock HotReservationRedisService hotRedis;
    @Mock ReservationMapper reservationMapper;
    @Mock ReservationReminderTaskService reminderService;
    @Mock ReservationAutoCancelService autoCancelService;
    @Mock UserNotificationService notificationService;

    private ReservationResultService service;

    @BeforeEach
    void setUp() {
        service = new ReservationResultService(
                hotRedis, reservationMapper, reminderService, autoCancelService, notificationService);
    }

    @Test
    void confirmedResultShouldScheduleFollowUpOnlyAfterReservationExists() {
        Reservation reservation = new Reservation();
        reservation.setId(88L);
        ReservationResultEvent event = event("CONFIRMED", 88L);
        when(reservationMapper.selectById(88L)).thenReturn(reservation);

        service.handle("event-1", event);

        verify(hotRedis).complete(event);
        verify(notificationService).createReservationResultNotification("event-1", event);
        verify(reminderService).createBeforeStartReminder(reservation);
        verify(autoCancelService).schedule(reservation);
    }

    @Test
    void rejectedResultShouldReleaseThroughRedisWithoutSchedulingSuccessTasks() {
        ReservationResultEvent event = event("REJECTED", null);

        service.handle("event-2", event);

        verify(hotRedis).complete(event);
        verify(notificationService).createReservationResultNotification("event-2", event);
        verify(reservationMapper, never()).selectById(org.mockito.ArgumentMatchers.any());
        verify(reminderService, never()).createBeforeStartReminder(org.mockito.ArgumentMatchers.any());
        verify(autoCancelService, never()).schedule(org.mockito.ArgumentMatchers.any());
    }

    private ReservationResultEvent event(String status, Long reservationId) {
        return new ReservationResultEvent(
                "33c8aa68-9fe0-4d30-afd5-6e62de86bd6c",
                7L, 1L, 10L, status, reservationId,
                reservationId == null ? null : "RES-88",
                reservationId == null ? "OUT_OF_STOCK" : null,
                reservationId == null ? "时段余量不足" : null);
    }
}
