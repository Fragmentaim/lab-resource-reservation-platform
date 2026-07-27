package com.fragment.labbooking.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.entity.MessageOutbox;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Claims due outbox rows and publishes them to RocketMQ.
 *
 * <p>The database state machine remains in {@link MessageOutboxService}; this
 * component owns the single transport boundary from the outbox table to MQ.</p>
 */
@Component
@Slf4j
public class MessageOutboxDispatcher {

    private final MessageOutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final MessageOutboxProperties properties;

    private volatile DefaultMQProducer producer;
    private volatile boolean started;

    public MessageOutboxDispatcher(MessageOutboxService outboxService,
                                   ObjectMapper objectMapper,
                                   MessageOutboxProperties properties) {
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @PostConstruct
    public void warmUp() {
        if (properties.isEnabled()) {
            ensureProducerStarted();
        }
    }

    @Scheduled(fixedDelayString = "${app.message-outbox.outbox.dispatch-delay-millis:1000}")
    public void dispatchPendingMessages() {
        if (!properties.isEnabled()) {
            return;
        }

        List<MessageOutbox> batch =
                outboxService.findPendingDueBatch(properties.getOutbox().getBatchSize());
        for (MessageOutbox outbox : batch) {
            dispatch(outbox);
        }
    }

    private void dispatch(MessageOutbox outbox) {
        if (!outboxService.markSending(outbox)) {
            return;
        }

        try {
            MessageOutboxEnvelope envelope = outboxService.toEnvelope(outbox);
            if (publish(envelope, outbox.getTopic(), outbox.getTag(), outbox.getMessageKey())) {
                outboxService.markSent(outbox);
            } else {
                outboxService.markRetryFailure(outbox, "publish returned false");
            }
        } catch (Exception exception) {
            outboxService.markRetryFailure(outbox, exception.getMessage());
            log.warn("Failed to dispatch outbox event, will retry later. eventId={}",
                    outbox.getEventId(), exception);
        }
    }

    /**
     * Package-visible for transport-focused tests; business callers must use
     * the outbox table instead of invoking MQ directly.
     */
    boolean publish(MessageOutboxEnvelope envelope, String topic, String tag, String messageKey) {
        if (!properties.isEnabled() || envelope == null || !ensureProducerStarted()) {
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

    private synchronized boolean ensureProducerStarted() {
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
