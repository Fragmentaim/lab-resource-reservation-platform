package com.fragment.labbooking.reservation.service;

import com.fragment.labbooking.common.constants.ResourceSlotTypeConstants;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.reservation.redis.HotReservationRedisService;
import com.fragment.labbooking.reservation.redis.ReservationRateLimiter;
import com.fragment.labbooking.reservation.reminder.ReservationAutoCancelService;
import com.fragment.labbooking.reservation.api.dto.ReservationCreateDTO;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.reservation.persistence.ReservationMapper;
import com.fragment.labbooking.reservation.reminder.ReservationReminderTaskService;
import com.fragment.labbooking.service.ResourceSlotService;
import com.fragment.labbooking.reservation.api.vo.ReservationSubmitVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationCommandServiceTest {

    private static final String REQUEST_ID = "33c8aa68-9fe0-4d30-afd5-6e62de86bd6c";

    @Mock ReservationMapper reservationMapper;
    @Mock ResourceSlotService slotService;
    @Mock HotReservationRedisService hotRedis;
    @Mock ReservationRateLimiter rateLimiter;
    @Mock ReservationReminderTaskService reminderService;
    @Mock ReservationAutoCancelService autoCancelService;
    @Mock HotReservationRequestService hotRequestService;
    @Mock NormalReservationService normalReservationService;

    private ReservationCommandService service;

    @BeforeEach
    void setUp() {
        lenient().when(hotRequestService.normalizeRequestId(REQUEST_ID)).thenReturn(REQUEST_ID);
        service = new ReservationCommandService(
                reservationMapper, slotService, hotRedis, rateLimiter,
                reminderService, autoCancelService, hotRequestService,
                normalReservationService, 30);
    }

    @Test
    void normalSlotShouldRemainSynchronous() {
        ResourceSlot slot = slot(ResourceSlotTypeConstants.NORMAL);
        ReservationSubmitVO confirmed = new ReservationSubmitVO();
        confirmed.setRequestId(REQUEST_ID);
        confirmed.setStatus("CONFIRMED");
        confirmed.setReservationId(88L);

        when(hotRedis.isPreheatedHotSlot(10L)).thenReturn(false);
        when(slotService.getById(10L)).thenReturn(slot);
        when(normalReservationService.create(7L, REQUEST_ID, dto())).thenReturn(confirmed);

        ReservationSubmitVO result = service.create(7L, REQUEST_ID, dto());

        assertThat(result.getRequestId()).isEqualTo(REQUEST_ID);
        assertThat(result.getStatus()).isEqualTo("CONFIRMED");
        assertThat(result.getReservationId()).isEqualTo(88L);
        verify(normalReservationService).create(7L, REQUEST_ID, dto());
    }

    @Test
    void preheatedHotSlotShouldBeAcceptedWithoutDatabaseAccess() {
        ReservationSubmitVO pending = new ReservationSubmitVO();
        pending.setRequestId(REQUEST_ID);
        pending.setStatus("PENDING");
        when(hotRedis.isPreheatedHotSlot(10L)).thenReturn(true);
        when(hotRequestService.accept(REQUEST_ID, 7L, 1L, 10L)).thenReturn(pending);

        ReservationSubmitVO result = service.create(7L, REQUEST_ID, dto());

        assertThat(result.getStatus()).isEqualTo("PENDING");
        verify(rateLimiter).checkCreateReservationLimit(7L, ResourceSlotTypeConstants.HOT);
        verifyNoInteractions(reservationMapper, normalReservationService);
        verify(slotService, never()).getById(any());
    }

    @Test
    void missingHotSnapshotShouldFailClosed() {
        when(hotRedis.isPreheatedHotSlot(10L)).thenReturn(false);
        when(slotService.getById(10L)).thenReturn(slot(ResourceSlotTypeConstants.HOT));

        assertThatThrownBy(() -> service.create(7L, REQUEST_ID, dto()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(503));

        verifyNoInteractions(reservationMapper, normalReservationService);
    }

    @Test
    void malformedIdempotencyKeyShouldFailBeforeRouting() {
        when(hotRequestService.normalizeRequestId("bad"))
                .thenThrow(new BusinessException(400, "Idempotency-Key 必须是 UUID"));

        assertThatThrownBy(() -> service.create(7L, "bad", dto()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("UUID");

        verifyNoInteractions(hotRedis, slotService, reservationMapper, normalReservationService);
    }

    private ReservationCreateDTO dto() {
        ReservationCreateDTO dto = new ReservationCreateDTO();
        dto.setResourceId(1L);
        dto.setSlotId(10L);
        return dto;
    }

    private ResourceSlot slot(String type) {
        ResourceSlot slot = new ResourceSlot();
        slot.setId(10L);
        slot.setResourceId(1L);
        slot.setSlotType(type);
        slot.setStatus("OPEN");
        slot.setOpenTime(LocalDateTime.now().minusMinutes(1));
        slot.setStartDatetime(LocalDateTime.now().plusHours(1));
        slot.setEndDatetime(LocalDateTime.now().plusHours(2));
        return slot;
    }
}
