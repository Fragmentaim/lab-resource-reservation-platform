package com.fragment.labbooking.common.mq;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.util.StringUtils;

import java.util.List;

/** Shared RocketMQ lifecycle; subclasses only decode and handle business messages. */
@Slf4j
public abstract class AbstractRocketMqConsumer {

    private final String consumerName;
    private final boolean enabled;
    private final String nameServer;
    private final String topic;
    private final String tagExpression;
    private final String consumerGroup;
    private final int maxReconsumeTimes;
    private volatile DefaultMQPushConsumer consumer;

    protected AbstractRocketMqConsumer(String consumerName, boolean enabled, String nameServer,
                                       String topic, String tagExpression, String consumerGroup,
                                       int maxReconsumeTimes) {
        this.consumerName = consumerName;
        this.enabled = enabled;
        this.nameServer = nameServer;
        this.topic = topic;
        this.tagExpression = tagExpression;
        this.consumerGroup = consumerGroup;
        this.maxReconsumeTimes = maxReconsumeTimes;
    }

    @PostConstruct
    public final void startConsumer() {
        if (!enabled) {
            return;
        }
        if (!StringUtils.hasText(nameServer)) {
            log.warn("{} MQ consumer is enabled but NameServer is empty.", consumerName);
            return;
        }
        try {
            DefaultMQPushConsumer created = new DefaultMQPushConsumer(consumerGroup);
            created.setNamesrvAddr(nameServer);
            if (maxReconsumeTimes >= 0) {
                created.setMaxReconsumeTimes(maxReconsumeTimes);
            }
            created.subscribe(topic, tagExpression);
            MessageListenerConcurrently listener = (messages, context) -> consumeMessages(messages);
            created.registerMessageListener(listener);
            created.start();
            consumer = created;
            log.info("{} MQ consumer started. topic={}, group={}", consumerName, topic, consumerGroup);
        } catch (Exception exception) {
            log.error("Failed to start {} MQ consumer.", consumerName, exception);
        }
    }

    @PreDestroy
    public final void stopConsumer() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }

    protected abstract ConsumeConcurrentlyStatus consumeMessages(List<MessageExt> messages);
}
