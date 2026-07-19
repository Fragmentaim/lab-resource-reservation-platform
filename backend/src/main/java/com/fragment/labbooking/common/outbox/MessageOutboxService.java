package com.fragment.labbooking.common.outbox;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.entity.MessageOutbox;
import com.fragment.labbooking.mapper.MessageOutboxMapper;
import com.fragment.labbooking.common.util.TruncateUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class MessageOutboxService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENDING = "SENDING";
    public static final String STATUS_SENT = "SENT";

    private final MessageOutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;
    private final MessageOutboxProperties properties;

    public MessageOutboxService(MessageOutboxMapper outboxMapper,
                                ObjectMapper objectMapper,
                                MessageOutboxProperties properties) {
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void enqueue(String aggregateType, String aggregateId, String eventType, String topic,
                        String tag, String messageKey, LocalDateTime availableAt, Object payload) {
        if (!StringUtils.hasText(aggregateType)
                || !StringUtils.hasText(aggregateId)
                || !StringUtils.hasText(eventType)
                || !StringUtils.hasText(topic)
                || payload == null) {
            return;
        }

        try {
            outboxMapper.insert(buildOutbox(
                    aggregateType,
                    aggregateId,
                    eventType,
                    topic,
                    tag,
                    StringUtils.hasText(messageKey) ? messageKey : aggregateId,
                    availableAt == null ? LocalDateTime.now() : availableAt,
                    payload
            ));
        } catch (DuplicateKeyException duplicateKeyException) {
            log.info("Message outbox event already exists, skip enqueue. eventType={}, aggregateType={}, aggregateId={}",
                    eventType, aggregateType, aggregateId);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to enqueue message outbox event", exception);
        }
    }

    public List<MessageOutbox> findPendingDueBatch(int batchSize) {
        LocalDateTime now = LocalDateTime.now();
        return outboxMapper.selectList(new LambdaQueryWrapper<MessageOutbox>()
                .and(wrapper -> wrapper
                        .eq(MessageOutbox::getStatus, STATUS_PENDING)
                        .le(MessageOutbox::getAvailableAt, now)
                        .or()
                        .eq(MessageOutbox::getStatus, STATUS_SENDING)
                        .le(MessageOutbox::getLockedUntil, now))
                .orderByAsc(MessageOutbox::getAvailableAt)
                .orderByAsc(MessageOutbox::getId)
                .last("LIMIT " + Math.max(batchSize, 1)));
    }

    public boolean markSending(MessageOutbox outbox) {
        if (outbox == null || outbox.getId() == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = outboxMapper.update(null, new LambdaUpdateWrapper<MessageOutbox>()
                .eq(MessageOutbox::getId, outbox.getId())
                .and(wrapper -> wrapper
                        .eq(MessageOutbox::getStatus, STATUS_PENDING)
                        .or()
                        .eq(MessageOutbox::getStatus, STATUS_SENDING)
                        .le(MessageOutbox::getLockedUntil, now))
                .set(MessageOutbox::getStatus, STATUS_SENDING)
                .set(MessageOutbox::getLockedUntil, now.plusSeconds(Math.max(10, properties.getLockSeconds())))
                .set(MessageOutbox::getUpdatedAt, now));
        return updated > 0;
    }

    public void markSent(MessageOutbox outbox) {
        if (outbox == null || outbox.getId() == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        outboxMapper.update(null, new LambdaUpdateWrapper<MessageOutbox>()
                .eq(MessageOutbox::getId, outbox.getId())
                .eq(MessageOutbox::getStatus, STATUS_SENDING)
                .set(MessageOutbox::getStatus, STATUS_SENT)
                .set(MessageOutbox::getSentAt, now)
                .set(MessageOutbox::getLockedUntil, null)
                .set(MessageOutbox::getUpdatedAt, now)
                .set(MessageOutbox::getLastErrorMessage, null));
    }

    public void markRetryFailure(MessageOutbox outbox, String errorMessage) {
        if (outbox == null || outbox.getId() == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        outboxMapper.update(null, new LambdaUpdateWrapper<MessageOutbox>()
                .eq(MessageOutbox::getId, outbox.getId())
                .eq(MessageOutbox::getStatus, STATUS_SENDING)
                .set(MessageOutbox::getStatus, STATUS_PENDING)
                .set(MessageOutbox::getRetryCount, outbox.getRetryCount() == null ? 1 : outbox.getRetryCount() + 1)
                .set(MessageOutbox::getLockedUntil, null)
                .set(MessageOutbox::getLastErrorMessage, TruncateUtil.truncate(errorMessage, 512))
                .set(MessageOutbox::getUpdatedAt, now));
    }

    public MessageOutboxEnvelope toEnvelope(MessageOutbox outbox) {
        MessageOutboxEnvelope envelope = new MessageOutboxEnvelope();
        envelope.setEventId(outbox.getEventId());
        envelope.setEventType(outbox.getEventType());
        envelope.setAggregateType(outbox.getAggregateType());
        envelope.setAggregateId(outbox.getAggregateId());
        envelope.setBusinessKey(outbox.getAggregateId());
        envelope.setAvailableAt(outbox.getAvailableAt());
        envelope.setPayload(outbox.getPayload());
        return envelope;
    }

    private MessageOutbox buildOutbox(String aggregateType, String aggregateId, String eventType,
                                      String topic, String tag, String messageKey,
                                      LocalDateTime availableAt, Object payload) throws Exception {
        LocalDateTime now = LocalDateTime.now();
        MessageOutbox outbox = new MessageOutbox();
        outbox.setEventId(eventType + ":" + aggregateType + ":" + aggregateId);
        outbox.setAggregateType(aggregateType);
        outbox.setAggregateId(aggregateId);
        outbox.setEventType(eventType);
        outbox.setTopic(topic);
        outbox.setTag(tag);
        outbox.setMessageKey(messageKey);
        outbox.setPayload(objectMapper.writeValueAsString(payload));
        outbox.setStatus(STATUS_PENDING);
        outbox.setAvailableAt(availableAt);
        outbox.setRetryCount(0);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        return outbox;
    }

}
