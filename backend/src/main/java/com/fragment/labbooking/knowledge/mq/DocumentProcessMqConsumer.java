package com.fragment.labbooking.knowledge.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.common.mq.AbstractRocketMqConsumer;
import com.fragment.labbooking.common.mq.OutboxMessageDecoder;
import com.fragment.labbooking.knowledge.service.KbDocumentService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@Slf4j
public class DocumentProcessMqConsumer extends AbstractRocketMqConsumer {

    private final ObjectMapper objectMapper;
    private final KbDocumentService kbDocumentService;

    public DocumentProcessMqConsumer(ObjectMapper objectMapper,
                                     KbDocumentService kbDocumentService,
                                     DocumentProcessProperties properties) {
        super("Knowledge document", properties.isEnabled(), properties.getNameServer(),
                properties.getTopic(), "*", properties.getConsumerGroup(), properties.getMaxReconsumeTimes());
        this.objectMapper = objectMapper;
        this.kbDocumentService = kbDocumentService;
    }

    @Override
    protected ConsumeConcurrentlyStatus consumeMessages(List<MessageExt> messages) {
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
        return OutboxMessageDecoder.payload(objectMapper, message, DocumentProcessMessage.class);
    }
}
