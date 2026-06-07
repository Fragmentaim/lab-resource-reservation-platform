package com.fragment.labbooking.common.delay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DelayMessageMqConsumerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @Test
    void consumeMessagesShouldRequeueWhenEventIsNotDueYet() throws Exception {
        DelayMessageMqPublisher publisher = mock(DelayMessageMqPublisher.class);
        ReservationReminderDeliveryService reminderDeliveryService = mock(ReservationReminderDeliveryService.class);
        ReservationRequestService requestService = mock(ReservationRequestService.class);
        ReservationAutoCancelService autoCancelService = mock(ReservationAutoCancelService.class);
        DelayMessageMqConsumer consumer = buildConsumer(publisher, reminderDeliveryService, requestService, autoCancelService);
        DelayMessageEnvelope envelope = buildEnvelope(DelayMessageEventTypes.RESERVATION_REMINDER, LocalDateTime.now().plusMinutes(10), "{}");

        when(publisher.publish(any(), eq("reservation-delay"), eq(DelayMessageTags.RESERVATION_REMINDER), eq("BK-1"), anyInt()))
                .thenReturn(true);

        ConsumeConcurrentlyStatus status = consumer.consumeMessages(List.of(toMessage(envelope)));

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        verify(publisher).publish(any(), eq("reservation-delay"), eq(DelayMessageTags.RESERVATION_REMINDER), eq("BK-1"), anyInt());
        verify(reminderDeliveryService, never()).deliver(any());
    }

    @Test
    void consumeMessagesShouldContinueAfterRequeueingOneMessageInBatch() throws Exception {
        DelayMessageMqPublisher publisher = mock(DelayMessageMqPublisher.class);
        ReservationReminderDeliveryService reminderDeliveryService = mock(ReservationReminderDeliveryService.class);
        ReservationRequestService requestService = mock(ReservationRequestService.class);
        ReservationAutoCancelService autoCancelService = mock(ReservationAutoCancelService.class);
        DelayMessageMqConsumer consumer = buildConsumer(publisher, reminderDeliveryService, requestService, autoCancelService);
        DelayMessageEnvelope futureEnvelope = buildEnvelope(
                DelayMessageEventTypes.RESERVATION_REMINDER,
                LocalDateTime.now().plusMinutes(10),
                "{}"
        );
        String payload = objectMapper.writeValueAsString(new ReservationReminderDelayPayload(100L));
        DelayMessageEnvelope dueEnvelope = buildEnvelope(
                DelayMessageEventTypes.RESERVATION_REMINDER,
                LocalDateTime.now().minusSeconds(1),
                payload
        );

        when(publisher.publish(any(), eq("reservation-delay"), eq(DelayMessageTags.RESERVATION_REMINDER), eq("BK-1"), anyInt()))
                .thenReturn(true);

        ConsumeConcurrentlyStatus status = consumer.consumeMessages(List.of(toMessage(futureEnvelope), toMessage(dueEnvelope)));

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        verify(reminderDeliveryService).deliver(100L);
    }

    @Test
    void consumeMessagesShouldDeliverReminderWhenDue() throws Exception {
        DelayMessageMqPublisher publisher = mock(DelayMessageMqPublisher.class);
        ReservationReminderDeliveryService reminderDeliveryService = mock(ReservationReminderDeliveryService.class);
        ReservationRequestService requestService = mock(ReservationRequestService.class);
        ReservationAutoCancelService autoCancelService = mock(ReservationAutoCancelService.class);
        DelayMessageMqConsumer consumer = buildConsumer(publisher, reminderDeliveryService, requestService, autoCancelService);
        String payload = objectMapper.writeValueAsString(new ReservationReminderDelayPayload(99L));
        DelayMessageEnvelope envelope = buildEnvelope(DelayMessageEventTypes.RESERVATION_REMINDER, LocalDateTime.now().minusSeconds(1), payload);

        ConsumeConcurrentlyStatus status = consumer.consumeMessages(List.of(toMessage(envelope)));

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        verify(reminderDeliveryService).deliver(99L);
        verify(publisher, never()).publish(any(), any(), any(), any(), anyInt());
    }

    @Test
    void consumeMessagesShouldTimeoutReservationRequestWhenDue() throws Exception {
        DelayMessageMqPublisher publisher = mock(DelayMessageMqPublisher.class);
        ReservationReminderDeliveryService reminderDeliveryService = mock(ReservationReminderDeliveryService.class);
        ReservationRequestService requestService = mock(ReservationRequestService.class);
        ReservationAutoCancelService autoCancelService = mock(ReservationAutoCancelService.class);
        DelayMessageMqConsumer consumer = buildConsumer(publisher, reminderDeliveryService, requestService, autoCancelService);
        String payload = objectMapper.writeValueAsString(new ReservationRequestTimeoutDelayPayload("REQ-1"));
        DelayMessageEnvelope envelope = buildEnvelope(DelayMessageEventTypes.RESERVATION_REQUEST_TIMEOUT, LocalDateTime.now().minusSeconds(1), payload);

        ConsumeConcurrentlyStatus status = consumer.consumeMessages(List.of(toMessage(envelope)));

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        verify(requestService).markTimedOutByRequestNo(eq("REQ-1"), any());
    }

    @Test
    void consumeMessagesShouldAutoCancelReservationWhenDue() throws Exception {
        DelayMessageMqPublisher publisher = mock(DelayMessageMqPublisher.class);
        ReservationReminderDeliveryService reminderDeliveryService = mock(ReservationReminderDeliveryService.class);
        ReservationRequestService requestService = mock(ReservationRequestService.class);
        ReservationAutoCancelService autoCancelService = mock(ReservationAutoCancelService.class);
        DelayMessageMqConsumer consumer = buildConsumer(publisher, reminderDeliveryService, requestService, autoCancelService);
        String payload = objectMapper.writeValueAsString(new ReservationAutoCancelDelayPayload(88L));
        DelayMessageEnvelope envelope = buildEnvelope(DelayMessageEventTypes.RESERVATION_AUTO_CANCEL, LocalDateTime.now().minusSeconds(1), payload);

        ConsumeConcurrentlyStatus status = consumer.consumeMessages(List.of(toMessage(envelope)));

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        verify(autoCancelService).autoCancel(88L);
    }

    @Test
    void consumeMessagesShouldRequestRetryWhenPayloadIsInvalid() {
        DelayMessageMqPublisher publisher = mock(DelayMessageMqPublisher.class);
        ReservationReminderDeliveryService reminderDeliveryService = mock(ReservationReminderDeliveryService.class);
        ReservationRequestService requestService = mock(ReservationRequestService.class);
        ReservationAutoCancelService autoCancelService = mock(ReservationAutoCancelService.class);
        DelayMessageMqConsumer consumer = buildConsumer(publisher, reminderDeliveryService, requestService, autoCancelService);
        MessageExt message = new MessageExt();
        message.setBody("not-json".getBytes(StandardCharsets.UTF_8));

        ConsumeConcurrentlyStatus status = consumer.consumeMessages(List.of(message));

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
    }

    private DelayMessageMqConsumer buildConsumer(DelayMessageMqPublisher publisher,
                                                 ReservationReminderDeliveryService reminderDeliveryService,
                                                 ReservationRequestService requestService,
                                                 ReservationAutoCancelService autoCancelService) {
        return new DelayMessageMqConsumer(
                objectMapper,
                publisher,
                new RocketMqDelayLevelResolver(),
                reminderDeliveryService,
                requestService,
                autoCancelService,
                false,
                "",
                "reservation-delay",
                "group",
                -1
        );
    }

    private MessageExt toMessage(DelayMessageEnvelope envelope) throws Exception {
        MessageExt message = new MessageExt();
        message.setBody(objectMapper.writeValueAsBytes(envelope));
        return message;
    }

    private DelayMessageEnvelope buildEnvelope(String eventType, LocalDateTime deliverAt, String payload) {
        DelayMessageEnvelope envelope = new DelayMessageEnvelope();
        envelope.setEventId("EVT-1");
        envelope.setEventType(eventType);
        envelope.setBusinessKey("BK-1");
        envelope.setDeliverAt(deliverAt);
        envelope.setPayload(payload);
        return envelope;
    }
}
