package com.fragment.labbooking.common.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.common.outbox.MessageOutboxEnvelope;
import com.fragment.labbooking.common.outbox.MessageOutboxProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Slf4j
public class OutboxMqPublisher {

    private final ObjectMapper objectMapper;
    private final MessageOutboxProperties properties;
    private volatile DefaultMQProducer producer;

    public OutboxMqPublisher(ObjectMapper objectMapper, MessageOutboxProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @PostConstruct
    public void start() {
        ensureStarted();
    }

    private synchronized boolean ensureStarted() {
        if (producer != null) {
            return true;
        }
        if (!properties.isEnabled()) {
            return false;
        }
        if (!StringUtils.hasText(properties.getNameServer())) {
            log.warn("Outbox MQ publisher is enabled but NameServer is empty.");
            return false;
        }
        DefaultMQProducer created = null;
        try {
            created = new DefaultMQProducer(properties.getProducerGroup());
            created.setNamesrvAddr(properties.getNameServer());
            created.start();
            producer = created;
            log.info("Outbox MQ publisher started.");
            return true;
        } catch (Exception exception) {
            if (created != null) {
                created.shutdown();
            }
            log.error("Failed to start outbox MQ publisher.", exception);
            return false;
        }
    }

    @PreDestroy
    public void stop() {
        if (producer != null) {
            producer.shutdown();
        }
    }

    public boolean publish(MessageOutboxEnvelope envelope, String topic, String tag, String messageKey) {
        if (envelope == null || !ensureStarted()) {
            return false;
        }
        try {
            Message message = new Message(topic, tag, messageKey, objectMapper.writeValueAsBytes(envelope));
            SendResult result = producer.send(message);
            log.info("Published outbox message. topic={}, eventId={}, msgId={}",
                    topic, envelope.getEventId(), result.getMsgId());
            return true;
        } catch (Exception exception) {
            log.warn("Failed to publish outbox message. eventId={}", envelope.getEventId(), exception);
            return false;
        }
    }
}
