package com.fragment.labbooking.knowledge.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.common.outbox.MessageOutboxEnvelope;
import com.fragment.labbooking.knowledge.service.KbDocumentService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;

@Component
@Slf4j
public class DocumentProcessMqConsumer {

    private final ObjectMapper objectMapper;
    private final KbDocumentService kbDocumentService;
    private final DocumentProcessProperties properties;

    private volatile DefaultMQPushConsumer consumer;

    public DocumentProcessMqConsumer(ObjectMapper objectMapper,
                                     KbDocumentService kbDocumentService,
                                     DocumentProcessProperties properties) {
        this.objectMapper = objectMapper;
        this.kbDocumentService = kbDocumentService;
        this.properties = properties;
    }

    @PostConstruct
    public void start() {
        if (!properties.isEnabled()) {
            return;
        }
        if (!StringUtils.hasText(properties.getNameServer())) {
            log.warn("Knowledge document MQ is enabled but NameServer address is empty, consumer will not start.");
            return;
        }

        try {
            DefaultMQPushConsumer mqConsumer = new DefaultMQPushConsumer(properties.getConsumerGroup());
            mqConsumer.setNamesrvAddr(properties.getNameServer());
            if (properties.getMaxReconsumeTimes() >= 0) {
                mqConsumer.setMaxReconsumeTimes(properties.getMaxReconsumeTimes());
            }
            mqConsumer.subscribe(properties.getTopic(), "*");
            mqConsumer.registerMessageListener((List<MessageExt> messages, ConsumeConcurrentlyContext context) ->
                    consumeMessages(messages));
            mqConsumer.start();
            consumer = mqConsumer;
            log.info("Knowledge document MQ consumer started. nameServer={}, topic={}, consumerGroup={}",
                    properties.getNameServer(), properties.getTopic(), properties.getConsumerGroup());
        } catch (Exception exception) {
            log.error("Failed to start knowledge document MQ consumer.", exception);
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
            try {
                DocumentProcessMessage event = parseEvent(message);
                kbDocumentService.processDocumentMessage(event.getDocumentId(), event.getTraceId());
            } catch (Exception exception) {
                log.error("Failed to consume knowledge document process message, requesting re-consume later.", exception);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    private DocumentProcessMessage parseEvent(MessageExt message) throws Exception {
        MessageOutboxEnvelope envelope = objectMapper.readValue(message.getBody(), MessageOutboxEnvelope.class);
        if (StringUtils.hasText(envelope.getPayload())) {
            return objectMapper.readValue(envelope.getPayload(), DocumentProcessMessage.class);
        }
        return objectMapper.readValue(message.getBody(), DocumentProcessMessage.class);
    }
}
