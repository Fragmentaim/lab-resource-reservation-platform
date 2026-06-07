package com.fragment.labbooking.common.delay;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Component
@Slf4j
public class DelayMessageMqPublisher {

    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String nameServer;
    private final String producerGroup;

    private volatile DefaultMQProducer producer;
    private volatile boolean started;

    public DelayMessageMqPublisher(ObjectMapper objectMapper,
                                   @Value("${app.delay-message.enabled:false}") boolean enabled,
                                   @Value("${app.delay-message.name-server:}") String nameServer,
                                   @Value("${app.delay-message.producer-group:lab-booking-delay-producer-group}") String producerGroup) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.nameServer = nameServer;
        this.producerGroup = producerGroup;
    }

    @PostConstruct
    public void warmUp() {
        if (enabled) {
            ensureStarted();
        }
    }

    public boolean publish(DelayMessageEnvelope envelope, String topic, String tag, String messageKey, int delayLevel) {
        if (!enabled || envelope == null || !ensureStarted()) {
            return false;
        }

        try {
            Message message = new Message(topic, tag, messageKey, objectMapper.writeValueAsBytes(envelope));
            if (delayLevel > 0) {
                message.setDelayTimeLevel(delayLevel);
            }
            SendResult sendResult = producer.send(message);
            log.info("Published delay message. topic={}, tag={}, eventId={}, delayLevel={}, msgId={}",
                    topic, tag, envelope.getEventId(), delayLevel, sendResult.getMsgId());
            return true;
        } catch (Exception exception) {
            log.warn("Failed to publish delay message. eventId={}", envelope.getEventId(), exception);
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
        if (!StringUtils.hasText(nameServer)) {
            log.warn("Delay message MQ is enabled but NameServer address is empty.");
            return false;
        }

        try {
            DefaultMQProducer mqProducer = new DefaultMQProducer(producerGroup);
            mqProducer.setNamesrvAddr(nameServer);
            mqProducer.start();
            producer = mqProducer;
            started = true;
            log.info("Delay message MQ producer started. nameServer={}", nameServer);
            return true;
        } catch (Exception exception) {
            log.warn("Failed to start delay message MQ producer.", exception);
            return false;
        }
    }
}
