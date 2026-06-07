package com.fragment.labbooking.common.delay;

import com.fragment.labbooking.entity.DelayMessageOutbox;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DelayMessageRelayTest {

    @Test
    void relayPendingMessagesShouldMarkSentWhenPublishSucceeds() {
        DelayMessageOutboxService outboxService = mock(DelayMessageOutboxService.class);
        DelayMessageMqPublisher publisher = mock(DelayMessageMqPublisher.class);
        RocketMqDelayLevelResolver resolver = mock(RocketMqDelayLevelResolver.class);
        DelayMessageRelay relay = new DelayMessageRelay(outboxService, publisher, resolver, true, 20);
        DelayMessageOutbox outbox = buildOutbox("EVT-1");
        DelayMessageEnvelope envelope = new DelayMessageEnvelope();

        when(outboxService.findPendingBatch(20)).thenReturn(List.of(outbox));
        when(outboxService.toEnvelope(outbox)).thenReturn(envelope);
        when(resolver.resolveDelayLevel(outbox.getDeliverAt())).thenReturn(5);
        when(publisher.publish(envelope, "reservation-delay", "reservation-reminder", "1", 5)).thenReturn(true);

        relay.relayPendingMessages();

        verify(outboxService).markSent(outbox);
        verify(outboxService, never()).markRetryFailure(outbox, "publish returned false");
    }

    @Test
    void relayPendingMessagesShouldMarkRetryFailureWhenPublishFails() {
        DelayMessageOutboxService outboxService = mock(DelayMessageOutboxService.class);
        DelayMessageMqPublisher publisher = mock(DelayMessageMqPublisher.class);
        RocketMqDelayLevelResolver resolver = mock(RocketMqDelayLevelResolver.class);
        DelayMessageRelay relay = new DelayMessageRelay(outboxService, publisher, resolver, true, 20);
        DelayMessageOutbox outbox = buildOutbox("EVT-2");
        DelayMessageEnvelope envelope = new DelayMessageEnvelope();

        when(outboxService.findPendingBatch(20)).thenReturn(List.of(outbox));
        when(outboxService.toEnvelope(outbox)).thenReturn(envelope);
        when(resolver.resolveDelayLevel(outbox.getDeliverAt())).thenReturn(5);
        when(publisher.publish(envelope, "reservation-delay", "reservation-reminder", "1", 5)).thenReturn(false);

        relay.relayPendingMessages();

        verify(outboxService).markRetryFailure(outbox, "publish returned false");
        verify(outboxService, never()).markSent(outbox);
    }

    private DelayMessageOutbox buildOutbox(String eventId) {
        DelayMessageOutbox outbox = new DelayMessageOutbox();
        outbox.setId(1L);
        outbox.setEventId(eventId);
        outbox.setTopic("reservation-delay");
        outbox.setTag("reservation-reminder");
        outbox.setMessageKey("1");
        outbox.setDeliverAt(LocalDateTime.now().plusMinutes(5));
        return outbox;
    }
}
