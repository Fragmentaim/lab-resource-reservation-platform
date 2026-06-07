package com.fragment.labbooking.common.reservation;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fragment.labbooking.common.constants.ReservationStatusConstants;
import com.fragment.labbooking.common.constants.ResourceSlotTypeConstants;
import com.fragment.labbooking.common.delay.DelayMessageEventTypes;
import com.fragment.labbooking.common.delay.DelayMessageOutboxService;
import com.fragment.labbooking.common.delay.ReservationAutoCancelDelayPayload;
import com.fragment.labbooking.common.redis.HotReservationRedisService;
import com.fragment.labbooking.common.redis.ResourceRedisCacheService;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.mapper.ReservationMapper;
import com.fragment.labbooking.service.ResourceSlotService;
import com.fragment.labbooking.service.UserNotificationService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationAutoCancelServiceTest {

    @Mock
    private ReservationMapper reservationMapper;
    @Mock
    private ResourceSlotService resourceSlotService;
    @Mock
    private HotReservationRedisService hotReservationRedisService;
    @Mock
    private ResourceRedisCacheService resourceRedisCacheService;
    @Mock
    private UserNotificationService userNotificationService;
    @Mock
    private DelayMessageOutboxService delayMessageOutboxService;

    private ReservationAutoCancelService autoCancelService;

    @BeforeEach
    void setUp() {
        initTableInfo(Reservation.class);
        autoCancelService = new ReservationAutoCancelService(
                reservationMapper,
                resourceSlotService,
                hotReservationRedisService,
                resourceRedisCacheService,
                userNotificationService,
                delayMessageOutboxService,
                true,
                15
        );
    }

    @Test
    void fillDeadlineAndScheduleShouldEnqueueAutoCancelDelayMessage() {
        LocalDateTime start = LocalDateTime.now().plusHours(1);
        Reservation reservation = new Reservation();
        reservation.setId(88L);
        reservation.setSlotStartDatetime(start);

        autoCancelService.fillAutoCancelDeadline(reservation);
        autoCancelService.schedule(reservation);

        assertThat(reservation.getAutoCancelDeadline()).isEqualTo(start.plusMinutes(15));
        ArgumentCaptor<ReservationAutoCancelDelayPayload> payloadCaptor =
                ArgumentCaptor.forClass(ReservationAutoCancelDelayPayload.class);
        verify(delayMessageOutboxService).enqueue(
                eq(DelayMessageEventTypes.RESERVATION_AUTO_CANCEL),
                eq("88"),
                eq(start.plusMinutes(15)),
                payloadCaptor.capture()
        );
        assertThat(payloadCaptor.getValue().getReservationId()).isEqualTo(88L);
    }

    @Test
    void autoCancelShouldCancelUncheckedBookedReservationAndReleaseQuota() {
        Reservation reservation = bookedReservation();
        when(reservationMapper.selectById(88L)).thenReturn(reservation);
        when(reservationMapper.update(eq(null), any())).thenReturn(1);

        boolean cancelled = autoCancelService.autoCancel(88L);

        assertThat(cancelled).isTrue();
        verify(resourceSlotService).restoreQuota(10L);
        verify(resourceRedisCacheService).invalidateResourceSlotList(1L);
        verify(hotReservationRedisService).releaseAfterSuccessfulCancellation(ResourceSlotTypeConstants.HOT, 10L, 7L);
        verify(userNotificationService).createAutoCancelNotification(reservation);
    }

    @Test
    void autoCancelShouldSkipWhenReservationAlreadyCheckedIn() {
        Reservation reservation = bookedReservation();
        reservation.setCheckedInAt(LocalDateTime.now().minusMinutes(1));
        when(reservationMapper.selectById(88L)).thenReturn(reservation);

        boolean cancelled = autoCancelService.autoCancel(88L);

        assertThat(cancelled).isFalse();
        verify(reservationMapper, never()).update(eq(null), any());
        verify(resourceSlotService, never()).restoreQuota(any());
        verify(userNotificationService, never()).createAutoCancelNotification(any());
    }

    @Test
    void autoCancelShouldSkipWhenDeadlineIsNotReached() {
        Reservation reservation = bookedReservation();
        reservation.setAutoCancelDeadline(LocalDateTime.now().plusMinutes(5));
        when(reservationMapper.selectById(88L)).thenReturn(reservation);

        boolean cancelled = autoCancelService.autoCancel(88L);

        assertThat(cancelled).isFalse();
        verify(reservationMapper, never()).update(eq(null), any());
        verify(resourceSlotService, never()).restoreQuota(any());
        verify(userNotificationService, never()).createAutoCancelNotification(any());
    }

    @Test
    void autoCancelShouldSkipWhenReservationAlreadyCancelled() {
        Reservation reservation = bookedReservation();
        reservation.setStatus(ReservationStatusConstants.CANCELLED);
        when(reservationMapper.selectById(88L)).thenReturn(reservation);

        boolean cancelled = autoCancelService.autoCancel(88L);

        assertThat(cancelled).isFalse();
        verify(reservationMapper, never()).update(eq(null), any());
        verify(resourceSlotService, never()).restoreQuota(any());
        verify(userNotificationService, never()).createAutoCancelNotification(any());
    }

    private Reservation bookedReservation() {
        Reservation reservation = new Reservation();
        reservation.setId(88L);
        reservation.setUserId(7L);
        reservation.setResourceId(1L);
        reservation.setSlotId(10L);
        reservation.setStatus(ReservationStatusConstants.BOOKED);
        reservation.setSourceType(ResourceSlotTypeConstants.HOT);
        reservation.setSlotStartDatetime(LocalDateTime.now().minusMinutes(20));
        reservation.setAutoCancelDeadline(LocalDateTime.now().minusMinutes(5));
        return reservation;
    }

    private void initTableInfo(Class<?> entityClass) {
        if (TableInfoHelper.getTableInfo(entityClass) != null) {
            return;
        }

        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace(entityClass.getName());
        TableInfoHelper.initTableInfo(assistant, entityClass);
    }
}
