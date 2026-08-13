package com.fragment.labbooking.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.common.audit.AdminAuditLogEvent;
import com.fragment.labbooking.reservation.reminder.messaging.DelayMessageEventTypes;
import com.fragment.labbooking.reservation.reminder.messaging.ReservationAutoCancelDelayPayload;
import com.fragment.labbooking.reservation.reminder.messaging.ReservationReminderDelayPayload;
import com.fragment.labbooking.reservation.reminder.ReservationReminderDeliveryService;
import com.fragment.labbooking.reservation.reminder.ReservationAutoCancelService;
import com.fragment.labbooking.knowledge.mq.DocumentProcessMessage;
import com.fragment.labbooking.mapper.AdminAuditLogMapper;
import com.fragment.labbooking.knowledge.service.KbDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.Message;
import org.springframework.util.StringUtils;

import java.util.function.Consumer;

/** Function beans keep consumer lifecycle and retry policy in the RocketMQ binder. */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class OutboxStreamConsumers {

    private final ObjectMapper objectMapper;
    private final ReservationReminderDeliveryService reminderDeliveryService;
    private final ReservationAutoCancelService reservationAutoCancelService;
    private final AdminAuditLogMapper adminAuditLogMapper;
    private final KbDocumentService kbDocumentService;

    @Bean
    Consumer<Message<MessageOutboxEnvelope>> reservationDelayConsumer() {
        return message -> dispatchDelay(envelope(message));
    }

    @Bean
    Consumer<Message<MessageOutboxEnvelope>> adminAuditConsumer() {
        return message -> {
            AdminAuditLogEvent event = payload(message, AdminAuditLogEvent.class);
            try {
                adminAuditLogMapper.insert(event.toLog());
            } catch (DuplicateKeyException duplicate) {
                log.info("Admin audit event already consumed. eventId={}", event.getEventId());
            }
        };
    }

    @Bean
    Consumer<Message<MessageOutboxEnvelope>> documentProcessConsumer() {
        return message -> {
            DocumentProcessMessage event = payload(message, DocumentProcessMessage.class);
            kbDocumentService.processDocumentMessage(event.getDocumentId(), event.getTraceId());
        };
    }

    private void dispatchDelay(MessageOutboxEnvelope envelope) {
        switch (envelope.getEventType()) {
            case DelayMessageEventTypes.RESERVATION_REMINDER -> reminderDeliveryService.deliver(
                    readPayload(envelope, ReservationReminderDelayPayload.class).getReminderTaskId());
            case DelayMessageEventTypes.RESERVATION_AUTO_CANCEL -> reservationAutoCancelService.autoCancel(
                    readPayload(envelope, ReservationAutoCancelDelayPayload.class).getReservationId());
            default -> throw new IllegalArgumentException("Unsupported delay event type: " + envelope.getEventType());
        }
    }

    private MessageOutboxEnvelope envelope(Message<MessageOutboxEnvelope> message) {
        MessageOutboxEnvelope envelope = message == null ? null : message.getPayload();
        if (envelope == null || !StringUtils.hasText(envelope.getEventId())
                || !StringUtils.hasText(envelope.getEventType()) || !StringUtils.hasText(envelope.getPayload())) {
            throw new IllegalArgumentException("Invalid outbox envelope");
        }
        return envelope;
    }

    private <T> T payload(Message<MessageOutboxEnvelope> message, Class<T> payloadType) {
        return readPayload(envelope(message), payloadType);
    }

    private <T> T readPayload(MessageOutboxEnvelope envelope, Class<T> payloadType) {
        try {
            return objectMapper.readValue(envelope.getPayload(), payloadType);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid " + envelope.getEventType() + " payload", exception);
        }
    }
}
