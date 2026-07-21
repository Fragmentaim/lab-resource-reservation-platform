package com.fragment.labbooking.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Component
@Slf4j
public class MessageOutboxMqPublisher {

    private final ObjectMapper objectMapper;
    private final MessageOutboxProperties properties;

    private volatile DefaultMQProducer producer;
    private volatile boolean started;

    public MessageOutboxMqPublisher(ObjectMapper objectMapper, MessageOutboxProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @PostConstruct
    public void warmUp() {
        if (properties.isEnabled()) {
            ensureStarted();
        }
    }

    public boolean publish(MessageOutboxEnvelope envelope, String topic, String tag, String messageKey) {
        if (!properties.isEnabled() || envelope == null || !ensureStarted()) {
            return false;
        }

        try {
            Message message = new Message(topic, tag, messageKey, objectMapper.writeValueAsBytes(envelope));
            SendResult sendResult = producer.send(message);
            log.info("Published outbox message. topic={}, tag={}, eventId={}, msgId={}",
                    topic, tag, envelope.getEventId(), sendResult.getMsgId());
            return true;
        } catch (Exception exception) {
            log.warn("Failed to publish outbox message. eventId={}", envelope.getEventId(), exception);
            return false;
        }
    }

    @PreDestroy
    public void shutdown() {
        if (producer != null) {
            producer.shutdown();
        }
    }

    private synchronized boolean ensureStarted() {
        if (started) {
            return true;
        }
        if (!StringUtils.hasText(properties.getNameServer())) {
            log.warn("Message outbox MQ is enabled but NameServer address is empty.");
            return false;
        }

        try {
            DefaultMQProducer mqProducer = new DefaultMQProducer(properties.getProducerGroup());
            mqProducer.setNamesrvAddr(properties.getNameServer());
            mqProducer.start();
            producer = mqProducer;
            started = true;
            log.info("Message outbox MQ producer started. nameServer={}", properties.getNameServer());
            return true;
        } catch (Exception exception) {
            log.warn("Failed to start message outbox MQ producer.", exception);
            return false;
        }
    }
}
