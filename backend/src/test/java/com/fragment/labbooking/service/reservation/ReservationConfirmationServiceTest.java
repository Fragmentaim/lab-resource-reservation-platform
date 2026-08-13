package com.fragment.labbooking.service.reservation;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fragment.labbooking.common.outbox.MessageOutboxService;
import com.fragment.labbooking.common.reservation.ReservationCreateCommand;
import com.fragment.labbooking.common.reservation.ReservationPersistenceHelper;
import com.fragment.labbooking.common.reservation.ReservationResultEvent;
import com.fragment.labbooking.entity.Reservation;
import com.fragment.labbooking.entity.ReservationRequest;
import com.fragment.labbooking.entity.Resource;
import com.fragment.labbooking.entity.ResourceSlot;
import com.fragment.labbooking.mapper.ReservationMapper;
import com.fragment.labbooking.mapper.ReservationRequestMapper;
import com.fragment.labbooking.mapper.ResourceMapper;
import com.fragment.labbooking.mapper.ResourceSlotMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationConfirmationServiceTest {

    @Mock ReservationRequestMapper requestMapper;
    @Mock ReservationMapper reservationMapper;
    @Mock ResourceMapper resourceMapper;
    @Mock ResourceSlotMapper slotMapper;
    @Mock ReservationPersistenceHelper persistenceHelper;
    @Mock MessageOutboxService outboxService;

    private ReservationConfirmationService service;

    @BeforeEach
    void setUp() {
        initTableInfo(ReservationRequest.class);
        service = new ReservationConfirmationService(
                requestMapper, reservationMapper, resourceMapper, slotMapper,
                persistenceHelper, outboxService, "reservation-result");
    }

    @Test
    void successfulConfirmationShouldWriteOneTerminalLedgerAndResultOutbox() {
        ReservationCreateCommand command = command();
        Resource resource = resource();
        ResourceSlot slot = slot();
        Reservation reservation = reservation();
        when(requestMapper.insertProcessingIgnore(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, ReservationRequest.class).setId(1L);
            return 1;
        });
        when(resourceMapper.selectById(1L)).thenReturn(resource);
        when(slotMapper.selectById(10L)).thenReturn(slot);
        when(persistenceHelper.buildReservation(7L, 1L, 10L, resource, slot)).thenReturn(reservation);
        when(persistenceHelper.saveIfNoActiveConflict(reservation))
                .thenReturn(ReservationPersistenceHelper.SaveOutcome.CREATED);
        when(slotMapper.deductQuotaIfAvailable(10L)).thenReturn(1);
        when(requestMapper.updateById(any(ReservationRequest.class))).thenReturn(1);

        service.confirm(command);

        ArgumentCaptor<ReservationRequest> ledger = ArgumentCaptor.forClass(ReservationRequest.class);
        verify(requestMapper).updateById(ledger.capture());
        assertThat(ledger.getValue().getStatus()).isEqualTo("CONFIRMED");
        assertThat(ledger.getValue().getReservationId()).isEqualTo(88L);
        verify(outboxService).enqueue(
                eq("RESERVATION_REQUEST"), eq(command.requestId()), eq("RESERVATION_RESULT"),
                eq("reservation-result"), eq("RESULT"), eq(command.requestId()), any(),
                any(ReservationResultEvent.class));
    }

    @Test
    void duplicateDeliveryAfterTerminalStateShouldAckWithoutNewWrites() {
        ReservationCreateCommand command = command();
        ReservationRequest existing = ledger("CONFIRMED");
        when(requestMapper.insertProcessingIgnore(any())).thenReturn(0);
        when(requestMapper.selectOne(any())).thenReturn(existing);

        service.confirm(command);

        verifyNoInteractions(resourceMapper, slotMapper, persistenceHelper, outboxService);
        verify(requestMapper, never()).updateById(any(ReservationRequest.class));
    }

    @Test
    void exhaustedMysqlQuotaShouldRemoveTentativeReservationAndCommitRejection() {
        ReservationCreateCommand command = command();
        Resource resource = resource();
        ResourceSlot slot = slot();
        Reservation reservation = reservation();
        when(requestMapper.insertProcessingIgnore(any())).thenAnswer(invocation -> {
            invocation.getArgument(0, ReservationRequest.class).setId(1L);
            return 1;
        });
        when(resourceMapper.selectById(1L)).thenReturn(resource);
        when(slotMapper.selectById(10L)).thenReturn(slot);
        when(persistenceHelper.buildReservation(7L, 1L, 10L, resource, slot)).thenReturn(reservation);
        when(persistenceHelper.saveIfNoActiveConflict(reservation))
                .thenReturn(ReservationPersistenceHelper.SaveOutcome.CREATED);
        when(slotMapper.deductQuotaIfAvailable(10L)).thenReturn(0);
        when(requestMapper.updateById(any(ReservationRequest.class))).thenReturn(1);

        service.confirm(command);

        verify(reservationMapper).deleteById(88L);
        ArgumentCaptor<ReservationRequest> ledger = ArgumentCaptor.forClass(ReservationRequest.class);
        verify(requestMapper).updateById(ledger.capture());
        assertThat(ledger.getValue().getStatus()).isEqualTo("REJECTED");
        assertThat(ledger.getValue().getRejectCode()).isEqualTo("OUT_OF_STOCK");
    }

    @Test
    void technicalFailureShouldEscapeForRocketMqRetry() {
        ReservationCreateCommand command = command();
        when(requestMapper.insertProcessingIgnore(any())).thenReturn(1);
        when(resourceMapper.selectById(1L)).thenThrow(new IllegalStateException("database timeout"));

        assertThatThrownBy(() -> service.confirm(command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database timeout");

        verify(requestMapper, never()).updateById(any(ReservationRequest.class));
        verifyNoInteractions(outboxService);
    }

    private ReservationCreateCommand command() {
        return new ReservationCreateCommand(
                "33c8aa68-9fe0-4d30-afd5-6e62de86bd6c", 7L, 1L, 10L);
    }

    private ReservationRequest ledger(String status) {
        ReservationRequest request = new ReservationRequest();
        request.setRequestId(command().requestId());
        request.setUserId(7L);
        request.setResourceId(1L);
        request.setSlotId(10L);
        request.setStatus(status);
        return request;
    }

    private Resource resource() {
        Resource resource = new Resource();
        resource.setId(1L);
        resource.setResourceName("实验室");
        return resource;
    }

    private ResourceSlot slot() {
        ResourceSlot slot = new ResourceSlot();
        slot.setId(10L);
        slot.setResourceId(1L);
        slot.setSlotType("HOT");
        slot.setStatus("OPEN");
        slot.setOpenTime(LocalDateTime.now().minusMinutes(1));
        slot.setEndDatetime(LocalDateTime.now().plusHours(1));
        return slot;
    }

    private Reservation reservation() {
        Reservation reservation = new Reservation();
        reservation.setId(88L);
        reservation.setReservationNo("RES-88");
        return reservation;
    }

    private void initTableInfo(Class<?> type) {
        if (TableInfoHelper.getTableInfo(type) != null) {
            return;
        }
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace(type.getName());
        TableInfoHelper.initTableInfo(assistant, type);
    }
}
