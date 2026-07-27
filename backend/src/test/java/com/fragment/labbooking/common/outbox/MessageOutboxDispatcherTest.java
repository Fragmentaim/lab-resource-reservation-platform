package com.fragment.labbooking.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.entity.MessageOutbox;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageOutboxDispatcherTest {

    @Test
    void dispatchShouldMarkSentAfterSuccessfulPublish() {
        MessageOutboxService outboxService = mock(MessageOutboxService.class);
        MessageOutboxProperties properties = enabledProperties(20);
        MessageOutboxDispatcher dispatcher = spy(
                new MessageOutboxDispatcher(outboxService, new ObjectMapper(), properties));
        MessageOutbox outbox = outbox();
        MessageOutboxEnvelope envelope = envelope();

        when(outboxService.findPendingDueBatch(20)).thenReturn(List.of(outbox));
        when(outboxService.markSending(outbox)).thenReturn(true);
        when(outboxService.toEnvelope(outbox)).thenReturn(envelope);
        doReturn(true).when(dispatcher)
                .publish(envelope, "reservation-events", "reservation-create", "REQ-100");

        dispatcher.dispatchPendingMessages();

        verify(outboxService).markSent(outbox);
        verify(outboxService, never()).markRetryFailure(outbox, "publish returned false");
    }

    @Test
    void dispatchShouldMarkRetryFailureWhenPublishReturnsFalse() {
        MessageOutboxService outboxService = mock(MessageOutboxService.class);
        MessageOutboxProperties properties = enabledProperties(20);
        MessageOutboxDispatcher dispatcher = spy(
                new MessageOutboxDispatcher(outboxService, new ObjectMapper(), properties));
        MessageOutbox outbox = outbox();
        MessageOutboxEnvelope envelope = envelope();

        when(outboxService.findPendingDueBatch(20)).thenReturn(List.of(outbox));
        when(outboxService.markSending(outbox)).thenReturn(true);
        when(outboxService.toEnvelope(outbox)).thenReturn(envelope);
        doReturn(false).when(dispatcher)
                .publish(envelope, "reservation-events", "reservation-create", "REQ-100");

        dispatcher.dispatchPendingMessages();

        verify(outboxService).markRetryFailure(outbox, "publish returned false");
        verify(outboxService, never()).markSent(outbox);
    }

    @Test
    void dispatchShouldSkipWhenLockWasNotAcquired() {
        MessageOutboxService outboxService = mock(MessageOutboxService.class);
        MessageOutboxProperties properties = enabledProperties(20);
        MessageOutboxDispatcher dispatcher = spy(
                new MessageOutboxDispatcher(outboxService, new ObjectMapper(), properties));
        MessageOutbox outbox = outbox();

        when(outboxService.findPendingDueBatch(20)).thenReturn(List.of(outbox));
        when(outboxService.markSending(outbox)).thenReturn(false);

        dispatcher.dispatchPendingMessages();

        verify(dispatcher, never()).publish(
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
