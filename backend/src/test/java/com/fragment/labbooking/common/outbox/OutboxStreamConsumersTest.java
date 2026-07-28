package com.fragment.labbooking.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.common.audit.AdminAuditLogEvent;
import com.fragment.labbooking.common.audit.AdminAuditLogWriter;
import com.fragment.labbooking.common.delay.DelayMessageEventTypes;
import com.fragment.labbooking.common.delay.ReservationReminderDelayPayload;
import com.fragment.labbooking.common.reminder.ReservationReminderDeliveryService;
import com.fragment.labbooking.common.reservation.ReservationAutoCancelService;
import com.fragment.labbooking.common.reservation.ReservationCreateEvent;
import com.fragment.labbooking.knowledge.service.KbDocumentService;
import com.fragment.labbooking.service.ReservationRequestService;
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
    void reservationConsumerShouldProcessOutboxPayload() throws Exception {
        ReservationRequestService requests = mock(ReservationRequestService.class);
        OutboxStreamConsumers consumers = consumers(requests, mock(ReservationReminderDeliveryService.class),
                mock(ReservationAutoCancelService.class), mock(AdminAuditLogWriter.class));
        ReservationCreateEvent event = new ReservationCreateEvent();
        event.setRequestNo("REQ-6002");

        consumers.reservationCreateConsumer().accept(message("RESERVATION_CREATE", event));

        verify(requests).processPendingHotRequest("REQ-6002");
    }

    @Test
    void delayConsumerShouldDeliverReminder() throws Exception {
        ReservationReminderDeliveryService reminders = mock(ReservationReminderDeliveryService.class);
        OutboxStreamConsumers consumers = consumers(mock(ReservationRequestService.class), reminders,
                mock(ReservationAutoCancelService.class), mock(AdminAuditLogWriter.class));

        consumers.reservationDelayConsumer().accept(message(DelayMessageEventTypes.RESERVATION_REMINDER,
                new ReservationReminderDelayPayload(99L)));

        verify(reminders).deliver(99L);
    }

    @Test
    void auditConsumerShouldTreatDuplicateWriteAsSuccess() throws Exception {
        AdminAuditLogWriter writer = mock(AdminAuditLogWriter.class);
        doThrow(new DuplicateKeyException("duplicate")).when(writer).write(any());
        OutboxStreamConsumers consumers = consumers(mock(ReservationRequestService.class),
                mock(ReservationReminderDeliveryService.class), mock(ReservationAutoCancelService.class), writer);
        AdminAuditLogEvent event = new AdminAuditLogEvent();
        event.setEventId("AUDIT-300");
        event.setCreatedAt(LocalDateTime.now());

        consumers.adminAuditConsumer().accept(message("ADMIN_AUDIT_LOG", event));

        verify(writer).write(any());
    }

    @Test
    void invalidEnvelopeShouldFailSoBinderCanRetryIt() {
        OutboxStreamConsumers consumers = consumers(mock(ReservationRequestService.class),
                mock(ReservationReminderDeliveryService.class), mock(ReservationAutoCancelService.class),
                mock(AdminAuditLogWriter.class));
        MessageOutboxEnvelope invalid = new MessageOutboxEnvelope();
        invalid.setEventType("RESERVATION_CREATE");

        assertThatThrownBy(() -> consumers.reservationCreateConsumer().accept(
                MessageBuilder.withPayload(invalid).build())).isInstanceOf(IllegalArgumentException.class);
    }

    private OutboxStreamConsumers consumers(ReservationRequestService requests,
                                            ReservationReminderDeliveryService reminders,
                                            ReservationAutoCancelService autoCancel,
                                            AdminAuditLogWriter auditWriter) {
        return new OutboxStreamConsumers(objectMapper, requests, reminders, autoCancel, auditWriter,
                mock(KbDocumentService.class));
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
