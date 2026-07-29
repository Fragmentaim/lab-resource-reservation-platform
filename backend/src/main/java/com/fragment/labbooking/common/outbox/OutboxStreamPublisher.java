package com.fragment.labbooking.common.outbox;

import com.alibaba.cloud.stream.binder.rocketmq.constant.RocketMQConst;
import com.fragment.labbooking.entity.MessageOutbox;
import com.fragment.labbooking.knowledge.mq.DocumentProcessProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** The only transport adapter between the transactional outbox and RocketMQ. */
@Component
@RequiredArgsConstructor
public class OutboxStreamPublisher {

    static final String DELAY_OUTPUT = "reservationDelay-out-0";
    static final String AUDIT_OUTPUT = "adminAudit-out-0";
    static final String DOCUMENT_OUTPUT = "documentProcess-out-0";

    private final StreamBridge streamBridge;
    private final MessageOutboxProperties outboxProperties;
    private final DocumentProcessProperties documentProcessProperties;

    @Value("${app.audit.mq.topic:admin-audit-log}")
    private String auditTopic;

    public void publish(MessageOutbox outbox, MessageOutboxEnvelope envelope) {
        if (outbox == null || envelope == null) {
            throw new IllegalArgumentException("Outbox message must not be null");
        }

        MessageBuilder<MessageOutboxEnvelope> builder = MessageBuilder.withPayload(envelope);
        if (StringUtils.hasText(outbox.getTag())) {
            builder.setHeader(RocketMQConst.Headers.TAGS, outbox.getTag());
        }
        if (StringUtils.hasText(outbox.getMessageKey())) {
            builder.setHeader(RocketMQConst.Headers.KEYS, outbox.getMessageKey());
        }
        if (!streamBridge.send(bindingFor(outbox.getTopic()), builder.build())) {
            throw new IllegalStateException("RocketMQ binder rejected outbox event " + outbox.getEventId());
        }
    }

    private String bindingFor(String topic) {
        if (outboxProperties.getDelayTopic().equals(topic)) {
            return DELAY_OUTPUT;
        }
        if (auditTopic.equals(topic)) {
            return AUDIT_OUTPUT;
        }
        if (documentProcessProperties.getTopic().equals(topic)) {
            return DOCUMENT_OUTPUT;
        }
        throw new IllegalArgumentException("No RocketMQ output binding for topic: " + topic);
    }
}
