package com.fragment.labbooking.common.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class AdminAuditMqConsumer {

    private static final String TAG_EXPRESSION = "admin-audit";

    private final ObjectMapper objectMapper;
    private final AdminAuditLogWriter adminAuditLogWriter;
    private final boolean enabled;
    private final String nameServer;
    private final String topic;
    private final String consumerGroup;
    private final int maxReconsumeTimes;
    private final boolean demoFailOnceEnabled;
    private final String demoFailOnceMatch;
    private final String demoFailAlwaysMatch;

    private volatile DefaultMQPushConsumer consumer;
    private final Set<String> retriedDemoEventIds = ConcurrentHashMap.newKeySet();

    public AdminAuditMqConsumer(ObjectMapper objectMapper,
                                AdminAuditLogWriter adminAuditLogWriter,
                                @Value("${app.audit.mq.enabled:true}") boolean enabled,
                                @Value("${app.audit.mq.name-server:}") String nameServer,
                                @Value("${app.audit.mq.topic:admin-audit-log}") String topic,
                                @Value("${app.audit.mq.consumer-group:lab-booking-audit-consumer-group}") String consumerGroup,
                                @Value("${app.audit.mq.max-reconsume-times:-1}") int maxReconsumeTimes,
                                @Value("${app.audit.mq.demo.fail-once-enabled:false}") boolean demoFailOnceEnabled,
                                @Value("${app.audit.mq.demo.fail-once-match:}") String demoFailOnceMatch,
                                @Value("${app.audit.mq.demo.fail-always-match:}") String demoFailAlwaysMatch) {
        this.objectMapper = objectMapper;
        this.adminAuditLogWriter = adminAuditLogWriter;
        this.enabled = enabled;
        this.nameServer = nameServer;
        this.topic = topic;
        this.consumerGroup = consumerGroup;
        this.maxReconsumeTimes = maxReconsumeTimes;
        this.demoFailOnceEnabled = demoFailOnceEnabled;
        this.demoFailOnceMatch = demoFailOnceMatch;
        this.demoFailAlwaysMatch = demoFailAlwaysMatch;
    }

    @PostConstruct
    public void start() {
        if (!enabled) {
            return;
        }
        if (!StringUtils.hasText(nameServer)) {
            log.warn("Admin audit MQ is enabled but NameServer address is empty, consumer will not start.");
            return;
        }

        try {
            DefaultMQPushConsumer mqConsumer = new DefaultMQPushConsumer(consumerGroup);
            mqConsumer.setNamesrvAddr(nameServer);
            if (maxReconsumeTimes >= 0) {
                mqConsumer.setMaxReconsumeTimes(maxReconsumeTimes);
            }
            mqConsumer.subscribe(topic, TAG_EXPRESSION);
            mqConsumer.registerMessageListener((List<MessageExt> messages, ConsumeConcurrentlyContext context) ->
                    consumeMessages(messages));
            mqConsumer.start();
            consumer = mqConsumer;
            log.info("Admin audit MQ consumer started. nameServer={}, topic={}, consumerGroup={}, maxReconsumeTimes={}",
                    nameServer, topic, consumerGroup, maxReconsumeTimes);
        } catch (Exception exception) {
            log.error("Failed to start admin audit MQ consumer. Audit logging will continue to use fallback direct DB write.", exception);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }

    ConsumeConcurrentlyStatus consumeMessages(List<MessageExt> messages) {
        for (MessageExt message : messages) {
            AdminAuditLogEvent event = null;
            try {
                event = objectMapper.readValue(message.getBody(), AdminAuditLogEvent.class);
                if (shouldSimulatePermanentFailure(event)) {
                    log.warn("Simulating repeated failure for admin audit log event. eventId={}, reconsumeTimes={}, summary={}",
                            event.getEventId(), message.getReconsumeTimes(), event.getSummary());
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
                if (shouldSimulateRetry(event)) {
                    log.warn("Simulating one retry for admin audit log event. eventId={}, summary={}",
                            event.getEventId(), event.getSummary());
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
                adminAuditLogWriter.write(event.toLog());
            } catch (DuplicateKeyException duplicateKeyException) {
                log.info("Admin audit log event already consumed, treating as success. eventId={}",
                        event == null ? null : event.getEventId());
            } catch (Exception exception) {
                log.error("Failed to consume admin audit log event, requesting re-consume later.", exception);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    private boolean shouldSimulateRetry(AdminAuditLogEvent event) {
        if (!demoFailOnceEnabled || event == null || !StringUtils.hasText(demoFailOnceMatch)) {
            return false;
        }

        if (!StringUtils.hasText(event.getEventId())) {
            return false;
        }

        String summary = event.getSummary();
        if (!StringUtils.hasText(summary) || !summary.contains(demoFailOnceMatch)) {
            return false;
        }

        return retriedDemoEventIds.add(event.getEventId());
    }

    private boolean shouldSimulatePermanentFailure(AdminAuditLogEvent event) {
        if (event == null || !StringUtils.hasText(demoFailAlwaysMatch)) {
            return false;
        }

        String summary = event.getSummary();
        return StringUtils.hasText(summary) && summary.contains(demoFailAlwaysMatch);
    }
}
