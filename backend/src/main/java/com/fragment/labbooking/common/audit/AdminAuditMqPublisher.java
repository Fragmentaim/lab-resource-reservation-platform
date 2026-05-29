package com.fragment.labbooking.common.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.entity.AdminAuditLog;
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
public class AdminAuditMqPublisher {

    public static final String TAG = "admin-audit";

    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String nameServer;
    private final String topic;
    private final String producerGroup;

    private volatile DefaultMQProducer producer;
    private volatile boolean started;

    public AdminAuditMqPublisher(ObjectMapper objectMapper,
                                 @Value("${app.audit.mq.enabled:true}") boolean enabled,
                                 @Value("${app.audit.mq.name-server:}") String nameServer,
                                 @Value("${app.audit.mq.topic:admin-audit-log}") String topic,
                                 @Value("${app.audit.mq.producer-group:lab-booking-audit-producer-group}") String producerGroup) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.nameServer = nameServer;
        this.topic = topic;
        this.producerGroup = producerGroup;
    }

    @PostConstruct
    public void warmUp() {
        if (!enabled) {
            return;
        }
        ensureStarted();
    }

    public boolean publish(AdminAuditLog auditLog) {
        if (auditLog == null) {
            return false;
        }

        return publish(AdminAuditLogEvent.from(auditLog), topic, TAG, buildMessageKey(auditLog.getEventId()));
    }

    public boolean publish(AdminAuditLogEvent event, String targetTopic, String tag, String messageKey) {
        if (!enabled || event == null || !ensureStarted()) {
            return false;
        }

        try {
            Message message = new Message(
                    targetTopic,
                    tag,
                    messageKey,
                    objectMapper.writeValueAsBytes(event)
            );
            SendResult sendResult = producer.send(message);
            log.info("Published admin audit log event via MQ. topic={}, eventId={}, msgId={}",
                    targetTopic, event.getEventId(), sendResult.getMsgId());
            return true;
        } catch (Exception exception) {
            log.warn("Failed to publish admin audit log event via MQ, outbox relay will retry later.", exception);
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
            log.warn("Admin audit MQ is enabled but NameServer address is empty.");
            return false;
        }

        try {
            DefaultMQProducer mqProducer = new DefaultMQProducer(producerGroup);
            mqProducer.setNamesrvAddr(nameServer);
            mqProducer.start();
            producer = mqProducer;
            started = true;
            log.info("Admin audit MQ producer started. nameServer={}, topic={}", nameServer, topic);
            return true;
        } catch (Exception exception) {
            log.warn("Failed to start admin audit MQ producer, outbox relay will keep retrying later.", exception);
            return false;
        }
    }

    private String buildMessageKey(String eventId) {
        if (StringUtils.hasText(eventId)) {
            return eventId;
        }

        return new StringBuilder("audit:")
                .append("unknown")
                .append(':')
                .append("unknown")
                .append(':')
                .append("na")
                .append(':')
                .append("na")
                .toString();
    }
}
