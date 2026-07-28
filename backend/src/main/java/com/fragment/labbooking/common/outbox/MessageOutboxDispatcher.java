package com.fragment.labbooking.common.outbox;

import com.fragment.labbooking.common.mq.OutboxMqPublisher;
import com.fragment.labbooking.entity.MessageOutbox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
    private final MessageOutboxProperties properties;
    private final OutboxMqPublisher publisher;

    public MessageOutboxDispatcher(MessageOutboxService outboxService,
                                   MessageOutboxProperties properties,
                                   OutboxMqPublisher publisher) {
        this.outboxService = outboxService;
        this.properties = properties;
        this.publisher = publisher;
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
            if (publisher.publish(envelope, outbox.getTopic(), outbox.getTag(), outbox.getMessageKey())) {
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

}
