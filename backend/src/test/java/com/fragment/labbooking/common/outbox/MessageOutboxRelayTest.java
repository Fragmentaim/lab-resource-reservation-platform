package com.fragment.labbooking.common.outbox;

import com.fragment.labbooking.entity.MessageOutbox;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageOutboxRelayTest {

    @Test
    void relayPendingMessagesShouldMarkSentAfterSuccessfulPublish() {
        MessageOutboxService outboxService = mock(MessageOutboxService.class);
        MessageOutboxMqPublisher publisher = mock(MessageOutboxMqPublisher.class);
        MessageOutboxProperties properties = enabledProperties(20);
        MessageOutboxRelay relay = new MessageOutboxRelay(outboxService, publisher, properties);
        MessageOutbox outbox = outbox();
        MessageOutboxEnvelope envelope = envelope();

        when(outboxService.findPendingDueBatch(20)).thenReturn(List.of(outbox));
        when(outboxService.markSending(outbox)).thenReturn(true);
        when(outboxService.toEnvelope(outbox)).thenReturn(envelope);
        when(publisher.publish(envelope, "reservation-events", "reservation-create", "REQ-100"))
                .thenReturn(true);

        relay.relayPendingMessages();

        verify(outboxService).markSent(outbox);
        verify(outboxService, never()).markRetryFailure(outbox, "publish returned false");
    }

    @Test
    void relayPendingMessagesShouldMarkRetryFailureWhenPublishReturnsFalse() {
        MessageOutboxService outboxService = mock(MessageOutboxService.class);
        MessageOutboxMqPublisher publisher = mock(MessageOutboxMqPublisher.class);
        MessageOutboxProperties properties = enabledProperties(20);
        MessageOutboxRelay relay = new MessageOutboxRelay(outboxService, publisher, properties);
        MessageOutbox outbox = outbox();
        MessageOutboxEnvelope envelope = envelope();

        when(outboxService.findPendingDueBatch(20)).thenReturn(List.of(outbox));
        when(outboxService.markSending(outbox)).thenReturn(true);
        when(outboxService.toEnvelope(outbox)).thenReturn(envelope);
        when(publisher.publish(envelope, "reservation-events", "reservation-create", "REQ-100"))
                .thenReturn(false);

        relay.relayPendingMessages();

        verify(outboxService).markRetryFailure(outbox, "publish returned false");
        verify(outboxService, never()).markSent(outbox);
    }

    @Test
    void relayPendingMessagesShouldSkipWhenLockWasNotAcquired() {
        MessageOutboxService outboxService = mock(MessageOutboxService.class);
        MessageOutboxMqPublisher publisher = mock(MessageOutboxMqPublisher.class);
        MessageOutboxProperties properties = enabledProperties(20);
        MessageOutboxRelay relay = new MessageOutboxRelay(outboxService, publisher, properties);
        MessageOutbox outbox = outbox();

        when(outboxService.findPendingDueBatch(20)).thenReturn(List.of(outbox));
        when(outboxService.markSending(outbox)).thenReturn(false);

        relay.relayPendingMessages();

        verify(publisher, never()).publish(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(outboxService, never()).markSent(outbox);
    }

    private MessageOutboxProperties enabledProperties(int batchSize) {
        MessageOutboxProperties properties = new MessageOutboxProperties();
        properties.setEnabled(true);
        properties.getOutbox().setBatchSize(batchSize);
        return properties;
    }

    private MessageOutbox outbox() {
        MessageOutbox outbox = new MessageOutbox();
        outbox.setId(1L);
        outbox.setEventId("RESERVATION_CREATE:RESERVATION_REQUEST:REQ-100");
        outbox.setAggregateType("RESERVATION_REQUEST");
        outbox.setAggregateId("REQ-100");
        outbox.setEventType("RESERVATION_CREATE");
        outbox.setTopic("reservation-events");
        outbox.setTag("reservation-create");
        outbox.setMessageKey("REQ-100");
        outbox.setPayload("{\"requestNo\":\"REQ-100\"}");
        outbox.setAvailableAt(LocalDateTime.now());
        return outbox;
    }

    private MessageOutboxEnvelope envelope() {
        MessageOutboxEnvelope envelope = new MessageOutboxEnvelope();
        envelope.setEventId("RESERVATION_CREATE:RESERVATION_REQUEST:REQ-100");
        envelope.setEventType("RESERVATION_CREATE");
        envelope.setAggregateType("RESERVATION_REQUEST");
        envelope.setAggregateId("REQ-100");
        envelope.setBusinessKey("REQ-100");
        envelope.setPayload("{\"requestNo\":\"REQ-100\"}");
        return envelope;
    }
}
