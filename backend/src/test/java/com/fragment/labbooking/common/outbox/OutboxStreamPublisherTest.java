package com.fragment.labbooking.common.outbox;

import com.fragment.labbooking.entity.MessageOutbox;
import com.fragment.labbooking.knowledge.mq.DocumentProcessProperties;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxStreamPublisherTest {

    @Test
    void publishShouldRouteDelayEventsToDedicatedBinding() {
        StreamBridge bridge = mock(StreamBridge.class);
        MessageOutboxProperties outboxProperties = new MessageOutboxProperties();
        DocumentProcessProperties documentProperties = new DocumentProcessProperties();
        OutboxStreamPublisher publisher = new OutboxStreamPublisher(bridge, outboxProperties, documentProperties);
        ReflectionTestUtils.setField(publisher, "auditTopic", "admin-audit-log");
        when(bridge.send(eq(OutboxStreamPublisher.DELAY_OUTPUT), any())).thenReturn(true);

        publisher.publish(outbox(outboxProperties.getDelayTopic()), envelope());

        verify(bridge).send(eq(OutboxStreamPublisher.DELAY_OUTPUT), any());
    }

    @Test
    void publishShouldRejectUnknownTopicBeforeItCanBeMarkedSent() {
        OutboxStreamPublisher publisher = new OutboxStreamPublisher(mock(StreamBridge.class),
                new MessageOutboxProperties(), new DocumentProcessProperties());
        ReflectionTestUtils.setField(publisher, "auditTopic", "admin-audit-log");

        assertThatThrownBy(() -> publisher.publish(outbox("unknown-topic"), envelope()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No RocketMQ output binding");
    }

    private MessageOutbox outbox(String topic) {
        MessageOutbox outbox = new MessageOutbox();
        outbox.setEventId("EVT-1");
        outbox.setTopic(topic);
        outbox.setTag("delay");
        outbox.setMessageKey("KEY-1");
        return outbox;
    }

    private MessageOutboxEnvelope envelope() {
        MessageOutboxEnvelope envelope = new MessageOutboxEnvelope();
        envelope.setEventId("EVT-1");
        envelope.setEventType("TEST");
        envelope.setPayload("{}");
        return envelope;
    }
}
