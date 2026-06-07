package com.fragment.labbooking.common.delay;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.entity.DelayMessageOutbox;
import com.fragment.labbooking.mapper.DelayMessageOutboxMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class DelayMessageOutboxService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SENT = "SENT";

    private final DelayMessageOutboxMapper delayMessageOutboxMapper;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String topic;

    public DelayMessageOutboxService(DelayMessageOutboxMapper delayMessageOutboxMapper,
                                     ObjectMapper objectMapper,
                                     @Value("${app.delay-message.enabled:false}") boolean enabled,
                                     @Value("${app.delay-message.topic:reservation-delay}") String topic) {
        this.delayMessageOutboxMapper = delayMessageOutboxMapper;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.topic = topic;
    }

    public void enqueue(String eventType, String businessKey, LocalDateTime deliverAt, Object payload) {
        if (!enabled || eventType == null || businessKey == null || deliverAt == null || payload == null) {
            return;
        }

        try {
            delayMessageOutboxMapper.insert(buildOutbox(eventType, businessKey, deliverAt, payload));
        } catch (DuplicateKeyException duplicateKeyException) {
            log.info("Delay message outbox event already exists, skip enqueue. eventType={}, businessKey={}",
                    eventType, businessKey);
        }
    }

    public List<DelayMessageOutbox> findPendingBatch(int batchSize) {
        return delayMessageOutboxMapper.selectList(new LambdaQueryWrapper<DelayMessageOutbox>()
                .eq(DelayMessageOutbox::getStatus, STATUS_PENDING)
                .orderByAsc(DelayMessageOutbox::getId)
                .last("LIMIT " + Math.max(batchSize, 1)));
    }

    public void markSent(DelayMessageOutbox outbox) {
        if (outbox == null || outbox.getId() == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        delayMessageOutboxMapper.update(null, new LambdaUpdateWrapper<DelayMessageOutbox>()
                .eq(DelayMessageOutbox::getId, outbox.getId())
                .eq(DelayMessageOutbox::getStatus, STATUS_PENDING)
                .set(DelayMessageOutbox::getStatus, STATUS_SENT)
                .set(DelayMessageOutbox::getSentAt, now)
                .set(DelayMessageOutbox::getUpdatedAt, now)
                .set(DelayMessageOutbox::getLastErrorMessage, null));
    }

    public void markRetryFailure(DelayMessageOutbox outbox, String errorMessage) {
        if (outbox == null || outbox.getId() == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        delayMessageOutboxMapper.update(null, new LambdaUpdateWrapper<DelayMessageOutbox>()
                .eq(DelayMessageOutbox::getId, outbox.getId())
                .eq(DelayMessageOutbox::getStatus, STATUS_PENDING)
                .set(DelayMessageOutbox::getRetryCount, outbox.getRetryCount() == null ? 1 : outbox.getRetryCount() + 1)
                .set(DelayMessageOutbox::getLastErrorMessage, truncate(errorMessage))
                .set(DelayMessageOutbox::getUpdatedAt, now));
    }

    public DelayMessageEnvelope toEnvelope(DelayMessageOutbox outbox) {
        DelayMessageEnvelope envelope = new DelayMessageEnvelope();
        envelope.setEventId(outbox.getEventId());
        envelope.setEventType(outbox.getEventType());
        envelope.setBusinessKey(outbox.getBusinessKey());
        envelope.setDeliverAt(outbox.getDeliverAt());
        envelope.setPayload(outbox.getPayload());
        return envelope;
    }

    private DelayMessageOutbox buildOutbox(String eventType, String businessKey, LocalDateTime deliverAt, Object payload) {
        LocalDateTime now = LocalDateTime.now();
        String eventId = eventType + ":" + businessKey;
        DelayMessageOutbox outbox = new DelayMessageOutbox();
        outbox.setEventId(eventId);
        outbox.setEventType(eventType);
        outbox.setBusinessKey(businessKey);
        outbox.setTopic(topic);
        outbox.setTag(DelayMessageTags.tagFor(eventType));
        outbox.setMessageKey(businessKey);
        outbox.setDeliverAt(deliverAt);
        outbox.setPayload(writePayload(payload));
        outbox.setStatus(STATUS_PENDING);
        outbox.setRetryCount(0);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        return outbox;
    }

    private String writePayload(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize delay message payload", exception);
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 512 ? text : text.substring(0, 512);
    }
}
