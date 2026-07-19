package com.fragment.labbooking.common.delay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fragment.labbooking.common.outbox.MessageOutboxEnvelope;
import com.fragment.labbooking.common.outbox.MessageOutboxProperties;
import com.fragment.labbooking.common.reminder.ReservationReminderDeliveryService;
import com.fragment.labbooking.common.reservation.ReservationAutoCancelService;
import com.fragment.labbooking.service.ReservationRequestService;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DelayMessageMqConsumerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    void consumeMessagesShouldDeliverReminder() throws Exception {
        ReservationReminderDeliveryService reminderDeliveryService = mock(ReservationReminderDeliveryService.class);
        DelayMessageMqConsumer consumer = buildConsumer(
                reminderDeliveryService,
                mock(ReservationRequestService.class),
                mock(ReservationAutoCancelService.class)
        );
        String payload = objectMapper.writeValueAsString(new ReservationReminderDelayPayload(99L));
        MessageOutboxEnvelope envelope = buildEnvelope(DelayMessageEventTypes.RESERVATION_REMINDER, payload);

        ConsumeConcurrentlyStatus status = consumer.consumeMessages(List.of(toMessage(envelope)));

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        verify(reminderDeliveryService).deliver(99L);
    }

    @Test
    void consumeMessagesShouldTimeoutReservationRequest() throws Exception {
        ReservationRequestService requestService = mock(ReservationRequestService.class);
        DelayMessageMqConsumer consumer = buildConsumer(
                mock(ReservationReminderDeliveryService.class),
                requestService,
                mock(ReservationAutoCancelService.class)
        );
        String payload = objectMapper.writeValueAsString(new ReservationRequestTimeoutDelayPayload("REQ-1"));
        MessageOutboxEnvelope envelope = buildEnvelope(DelayMessageEventTypes.RESERVATION_REQUEST_TIMEOUT, payload);

        ConsumeConcurrentlyStatus status = consumer.consumeMessages(List.of(toMessage(envelope)));

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        verify(requestService).markTimedOutByRequestNo(eq("REQ-1"), any());
    }

    @Test
    void consumeMessagesShouldAutoCancelReservation() throws Exception {
        ReservationAutoCancelService autoCancelService = mock(ReservationAutoCancelService.class);
        DelayMessageMqConsumer consumer = buildConsumer(
                mock(ReservationReminderDeliveryService.class),
                mock(ReservationRequestService.class),
                autoCancelService
        );
        String payload = objectMapper.writeValueAsString(new ReservationAutoCancelDelayPayload(88L));
        MessageOutboxEnvelope envelope = buildEnvelope(DelayMessageEventTypes.RESERVATION_AUTO_CANCEL, payload);

        ConsumeConcurrentlyStatus status = consumer.consumeMessages(List.of(toMessage(envelope)));

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        verify(autoCancelService).autoCancel(88L);
    }

    @Test
    void consumeMessagesShouldRequestRetryWhenPayloadIsInvalid() {
        DelayMessageMqConsumer consumer = buildConsumer(
                mock(ReservationReminderDeliveryService.class),
                mock(ReservationRequestService.class),
                mock(ReservationAutoCancelService.class)
        );
        MessageExt message = new MessageExt();
        message.setBody("not-json".getBytes(StandardCharsets.UTF_8));

        ConsumeConcurrentlyStatus status = consumer.consumeMessages(List.of(message));

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
    }

    private DelayMessageMqConsumer buildConsumer(ReservationReminderDeliveryService reminderDeliveryService,
                                                 ReservationRequestService requestService,
                                                 ReservationAutoCancelService autoCancelService) {
        return new DelayMessageMqConsumer(
                objectMapper,
                reminderDeliveryService,
                requestService,
                autoCancelService,
                new MessageOutboxProperties()
        );
    }

    private MessageExt toMessage(MessageOutboxEnvelope envelope) throws Exception {
        MessageExt message = new MessageExt();
        message.setBody(objectMapper.writeValueAsBytes(envelope));
        return message;
    }

    private MessageOutboxEnvelope buildEnvelope(String eventType, String payload) {
        MessageOutboxEnvelope envelope = new MessageOutboxEnvelope();
        envelope.setEventId("EVT-1");
        envelope.setEventType(eventType);
        envelope.setAggregateType("TEST");
        envelope.setAggregateId("BK-1");
        envelope.setBusinessKey("BK-1");
        envelope.setAvailableAt(LocalDateTime.now());
        envelope.setPayload(payload);
        return envelope;
    }
}
