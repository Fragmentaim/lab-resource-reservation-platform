package com.fragment.labbooking.common.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.common.outbox.MessageOutboxEnvelope;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.util.StringUtils;

public final class OutboxMessageDecoder {

    private OutboxMessageDecoder() {
    }

    public static MessageOutboxEnvelope envelope(ObjectMapper objectMapper, MessageExt message) throws Exception {
        MessageOutboxEnvelope envelope = objectMapper.readValue(message.getBody(), MessageOutboxEnvelope.class);
        if (!StringUtils.hasText(envelope.getEventId())
                || !StringUtils.hasText(envelope.getEventType())
                || !StringUtils.hasText(envelope.getPayload())) {
            throw new IllegalArgumentException("Invalid outbox envelope");
        }
        return envelope;
    }

    public static <T> T payload(ObjectMapper objectMapper, MessageExt message, Class<T> payloadType)
            throws Exception {
        return objectMapper.readValue(envelope(objectMapper, message).getPayload(), payloadType);
    }
}
