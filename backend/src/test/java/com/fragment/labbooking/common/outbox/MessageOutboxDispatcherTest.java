package com.fragment.labbooking.common.outbox;

import com.fragment.labbooking.entity.MessageOutbox;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageOutboxDispatcherTest {

    @Test
    void dispatchShouldMarkSentAfterSuccessfulPublish() {
        MessageOutboxService outboxService = mock(MessageOutboxService.class);
        OutboxStreamPublisher publisher = mock(OutboxStreamPublisher.class);
        MessageOutboxProperties properties = enabledProperties(20);
        MessageOutboxDispatcher dispatcher = new MessageOutboxDispatcher(outboxService, properties, publisher);
        MessageOutbox outbox = outbox();
        MessageOutboxEnvelope envelope = envelope();

        when(outboxService.findPendingDueBatch(20)).thenReturn(List.of(outbox));
        when(outboxService.markSending(outbox)).thenReturn(true);
        when(outboxService.toEnvelope(outbox)).thenReturn(envelope);
        dispatcher.dispatchPendingMessages();

        verify(outboxService).markSent(outbox);
        verify(publisher).publish(outbox, envelope);
    }

    @Test
    void dispatchShouldMarkRetryFailureWhenPublishThrows() {
        MessageOutboxService outboxService = mock(MessageOutboxService.class);
        OutboxStreamPublisher publisher = mock(OutboxStreamPublisher.class);
        MessageOutboxProperties properties = enabledProperties(20);
        MessageOutboxDispatcher dispatcher = new MessageOutboxDispatcher(outboxService, properties, publisher);
        MessageOutbox outbox = outbox();
        MessageOutboxEnvelope envelope = envelope();

        when(outboxService.findPendingDueBatch(20)).thenReturn(List.of(outbox));
        when(outboxService.markSending(outbox)).thenReturn(true);
        when(outboxService.toEnvelope(outbox)).thenReturn(envelope);
        doThrow(new IllegalStateException("broker unavailable")).when(publisher).publish(outbox, envelope);

        dispatcher.dispatchPendingMessages();

        verify(outboxService).markRetryFailure(outbox, "broker unavailable");
        verify(outboxService, never()).markSent(outbox);
    }

    @Test
    void dispatchShouldSkipWhenLockWasNotAcquired() {
        MessageOutboxService outboxService = mock(MessageOutboxService.class);
        OutboxStreamPublisher publisher = mock(OutboxStreamPublisher.class);
        MessageOutboxProperties properties = enabledProperties(20);
        MessageOutboxDispatcher dispatcher = new MessageOutboxDispatcher(outboxService, properties, publisher);
        MessageOutbox outbox = outbox();

        when(outboxService.findPendingDueBatch(20)).thenReturn(List.of(outbox));
        when(outboxService.markSending(outbox)).thenReturn(false);

        dispatcher.dispatchPendingMessages();

        verify(publisher, never()).publish(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
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
        outbox.setEventId("RESERVATION_REMINDER:REMINDER_TASK:100");
        outbox.setAggregateType("RESERVATION_REQUEST");
        outbox.setAggregateId("REQ-100");
        outbox.setEventType("RESERVATION_REMINDER");
        outbox.setTopic("reservation-events");
        outbox.setTag("reservation-create");
        outbox.setMessageKey("REQ-100");
        outbox.setPayload("{\"requestNo\":\"REQ-100\"}");
        outbox.setAvailableAt(LocalDateTime.now());
        return outbox;
    }

    private MessageOutboxEnvelope envelope() {
        MessageOutboxEnvelope envelope = new MessageOutboxEnvelope();
        envelope.setEventId("RESERVATION_REMINDER:REMINDER_TASK:100");
        envelope.setEventType("RESERVATION_REMINDER");
        envelope.setAggregateType("RESERVATION_REQUEST");
        envelope.setAggregateId("REQ-100");
        envelope.setBusinessKey("REQ-100");
        envelope.setPayload("{\"requestNo\":\"REQ-100\"}");
        return envelope;
    }
}
