package com.fragment.labbooking.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.common.audit.AdminAuditLogEvent;
import com.fragment.labbooking.reservation.reminder.messaging.DelayMessageEventTypes;
import com.fragment.labbooking.reservation.reminder.messaging.ReservationReminderDelayPayload;
import com.fragment.labbooking.reservation.reminder.ReservationReminderDeliveryService;
import com.fragment.labbooking.reservation.reminder.ReservationAutoCancelService;
import com.fragment.labbooking.entity.AdminAuditLog;
import com.fragment.labbooking.mapper.AdminAuditLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.support.MessageBuilder;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OutboxStreamConsumersTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void delayConsumerShouldDeliverReminder() throws Exception {
        ReservationReminderDeliveryService reminders = mock(ReservationReminderDeliveryService.class);
        OutboxStreamConsumers consumers = consumers(reminders,
                mock(ReservationAutoCancelService.class), mock(AdminAuditLogMapper.class));

        consumers.reservationDelayConsumer().accept(message(DelayMessageEventTypes.RESERVATION_REMINDER,
                new ReservationReminderDelayPayload(99L)));

        verify(reminders).deliver(99L);
    }

    @Test
    void auditConsumerShouldTreatDuplicateWriteAsSuccess() throws Exception {
        AdminAuditLogMapper adminAuditLogMapper = mock(AdminAuditLogMapper.class);
        doThrow(new DuplicateKeyException("duplicate")).when(adminAuditLogMapper).insert(any(AdminAuditLog.class));
        OutboxStreamConsumers consumers = consumers(mock(ReservationReminderDeliveryService.class),
                mock(ReservationAutoCancelService.class), adminAuditLogMapper);
        AdminAuditLogEvent event = new AdminAuditLogEvent();
        event.setEventId("AUDIT-300");
        event.setCreatedAt(LocalDateTime.now());

        consumers.adminAuditConsumer().accept(message("ADMIN_AUDIT_LOG", event));

        verify(adminAuditLogMapper).insert(any(AdminAuditLog.class));
    }

    @Test
    void invalidEnvelopeShouldFailSoBinderCanRetryIt() {
        OutboxStreamConsumers consumers = consumers(mock(ReservationReminderDeliveryService.class), mock(ReservationAutoCancelService.class),
                mock(AdminAuditLogMapper.class));
        MessageOutboxEnvelope invalid = new MessageOutboxEnvelope();
        invalid.setEventType("RESERVATION_REMINDER");

        assertThatThrownBy(() -> consumers.reservationDelayConsumer().accept(
                MessageBuilder.withPayload(invalid).build())).isInstanceOf(IllegalArgumentException.class);
    }

    private OutboxStreamConsumers consumers(ReservationReminderDeliveryService reminders,
                                            ReservationAutoCancelService autoCancel,
                                            AdminAuditLogMapper adminAuditLogMapper) {
        return new OutboxStreamConsumers(objectMapper, reminders, autoCancel, adminAuditLogMapper);
    }

    private org.springframework.messaging.Message<MessageOutboxEnvelope> message(String eventType, Object payload)
            throws Exception {
        MessageOutboxEnvelope envelope = new MessageOutboxEnvelope();
        envelope.setEventId("EVT-1");
        envelope.setEventType(eventType);
        envelope.setPayload(objectMapper.writeValueAsString(payload));
        return MessageBuilder.withPayload(envelope).build();
    }
}
