package com.fragment.labbooking.reservation.service;

import com.fragment.labbooking.common.constants.ResourceSlotTypeConstants;
import com.fragment.labbooking.entity.Resource;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.reservation.api.dto.ReservationCreateDTO;
import com.fragment.labbooking.reservation.api.vo.ReservationSubmitVO;
import com.fragment.labbooking.reservation.model.Reservation;
import com.fragment.labbooking.reservation.reminder.ReservationAutoCancelService;
import com.fragment.labbooking.reservation.reminder.ReservationReminderTaskService;
import com.fragment.labbooking.reservation.support.ReservationPersistenceHelper;
import com.fragment.labbooking.service.ResourceService;
import com.fragment.labbooking.service.ResourceSlotService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NormalReservationServiceTest {

    @Mock ResourceService resourceService;
    @Mock ResourceSlotService slotService;
    @Mock ReservationPersistenceHelper persistenceHelper;
    @Mock ReservationReminderTaskService reminderService;
    @Mock ReservationAutoCancelService autoCancelService;
    @InjectMocks NormalReservationService service;

    @Test
    void shouldCreateReservationSynchronously() {
        Resource resource = new Resource();
        resource.setId(1L);
        resource.setResourceName("实验室");
        ResourceSlot slot = new ResourceSlot();
        slot.setId(10L);
        slot.setResourceId(1L);
        slot.setSlotType(ResourceSlotTypeConstants.NORMAL);
        slot.setStatus("OPEN");
        slot.setEndDatetime(LocalDateTime.now().plusHours(1));
        Reservation reservation = new Reservation();
        reservation.setId(88L);
        reservation.setReservationNo("RES-88");

        when(resourceService.getById(1L)).thenReturn(resource);
        when(slotService.getById(10L)).thenReturn(slot);
        when(persistenceHelper.buildReservation(7L, 1L, 10L, resource, slot)).thenReturn(reservation);

        ReservationSubmitVO result = service.create(7L, "request-id", dto());

        assertThat(result.getStatus()).isEqualTo("CONFIRMED");
        assertThat(result.getReservationId()).isEqualTo(88L);
        verify(slotService).deductQuotaIfAvailable(10L);
        verify(persistenceHelper).saveWithRetry(reservation);
        verify(reminderService).createBeforeStartReminder(reservation);
        verify(autoCancelService).schedule(reservation);
    }

    private ReservationCreateDTO dto() {
        ReservationCreateDTO dto = new ReservationCreateDTO();
        dto.setResourceId(1L);
        dto.setSlotId(10L);
        return dto;
    }
}
