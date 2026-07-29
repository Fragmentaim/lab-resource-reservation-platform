package com.fragment.labbooking.service.reservation;

import com.fragment.labbooking.common.constants.ResourceSlotTypeConstants;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.common.redis.HotReservationRedisService;
import com.fragment.labbooking.common.redis.ReservationRateLimiter;
import com.fragment.labbooking.common.reservation.ReservationAutoCancelService;
import com.fragment.labbooking.common.reservation.ReservationPersistenceHelper;
import com.fragment.labbooking.dto.ReservationCreateDTO;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.entity.Resource;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.mapper.ReservationMapper;
import com.fragment.labbooking.service.ReservationReminderTaskService;
import com.fragment.labbooking.service.ResourceService;
import com.fragment.labbooking.service.ResourceSlotService;
import com.fragment.labbooking.vo.ReservationSubmitVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationCommandServiceTest {

    private static final String REQUEST_ID = "33c8aa68-9fe0-4d30-afd5-6e62de86bd6c";

    @Mock ReservationMapper reservationMapper;
    @Mock ResourceService resourceService;
    @Mock ResourceSlotService slotService;
    @Mock HotReservationRedisService hotRedis;
    @Mock ReservationRateLimiter rateLimiter;
    @Mock ReservationPersistenceHelper persistenceHelper;
    @Mock ReservationReminderTaskService reminderService;
    @Mock ReservationAutoCancelService autoCancelService;
    @Mock HotReservationRequestService hotRequestService;
    @Mock PlatformTransactionManager transactionManager;

    private ReservationCommandService service;

    @BeforeEach
    void setUp() {
        lenient().when(hotRequestService.normalizeRequestId(REQUEST_ID)).thenReturn(REQUEST_ID);
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        service = new ReservationCommandService(
                reservationMapper, resourceService, slotService, hotRedis, rateLimiter,
                persistenceHelper, reminderService, autoCancelService, hotRequestService,
                transactionManager, 30);
    }

    @Test
    void normalSlotShouldRemainSynchronous() {
        ResourceSlot slot = slot(ResourceSlotTypeConstants.NORMAL);
        Resource resource = resource();
        Reservation reservation = new Reservation();
        reservation.setId(88L);
        reservation.setReservationNo("RES-88");

        when(hotRedis.isPreheatedHotSlot(10L)).thenReturn(false);
        when(slotService.getById(10L)).thenReturn(slot);
        when(resourceService.getById(1L)).thenReturn(resource);
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        when(persistenceHelper.buildReservation(7L, 1L, 10L, resource, slot)).thenReturn(reservation);

        ReservationSubmitVO result = service.create(7L, REQUEST_ID, dto());

        assertThat(result.getRequestId()).isEqualTo(REQUEST_ID);
        assertThat(result.getStatus()).isEqualTo("CONFIRMED");
        assertThat(result.getReservationId()).isEqualTo(88L);
        verify(slotService).deductQuotaIfAvailable(10L);
        verify(persistenceHelper).saveWithRetry(reservation);
        verify(reminderService).createBeforeStartReminder(reservation);
        verify(autoCancelService).schedule(reservation);
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
        verifyNoInteractions(resourceService, reservationMapper);
        verify(slotService, never()).getById(any());
    }

    @Test
    void missingHotSnapshotShouldFailClosed() {
        when(hotRedis.isPreheatedHotSlot(10L)).thenReturn(false);
        when(slotService.getById(10L)).thenReturn(slot(ResourceSlotTypeConstants.HOT));

        assertThatThrownBy(() -> service.create(7L, REQUEST_ID, dto()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(503));

        verifyNoInteractions(resourceService, reservationMapper);
    }

    @Test
    void malformedIdempotencyKeyShouldFailBeforeRouting() {
        when(hotRequestService.normalizeRequestId("bad"))
                .thenThrow(new BusinessException(400, "Idempotency-Key 必须是 UUID"));

        assertThatThrownBy(() -> service.create(7L, "bad", dto()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("UUID");

        verifyNoInteractions(hotRedis, slotService, resourceService, reservationMapper);
    }

    private ReservationCreateDTO dto() {
        ReservationCreateDTO dto = new ReservationCreateDTO();
        dto.setResourceId(1L);
        dto.setSlotId(10L);
        return dto;
    }

    private Resource resource() {
        Resource resource = new Resource();
        resource.setId(1L);
        resource.setResourceCode("LAB-1");
        resource.setResourceName("实验室");
        return resource;
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
