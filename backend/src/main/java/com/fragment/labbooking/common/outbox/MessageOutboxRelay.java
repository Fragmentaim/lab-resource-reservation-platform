package com.fragment.labbooking.common.outbox;

import com.fragment.labbooking.entity.MessageOutbox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class MessageOutboxRelay {

    private final MessageOutboxService outboxService;
    private final MessageOutboxMqPublisher publisher;
    private final MessageOutboxProperties properties;

    public MessageOutboxRelay(MessageOutboxService outboxService,
                              MessageOutboxMqPublisher publisher,
                              MessageOutboxProperties properties) {
        this.outboxService = outboxService;
        this.publisher = publisher;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.message-outbox.outbox.relay-delay-millis:1000}")
    public void relayPendingMessages() {
        if (!properties.isEnabled()) {
            return;
        }

        List<MessageOutbox> batch = outboxService.findPendingDueBatch(properties.getOutbox().getBatchSize());
        for (MessageOutbox outbox : batch) {
            try {
                if (!outboxService.markSending(outbox)) {
                    continue;
                }
                MessageOutboxEnvelope envelope = outboxService.toEnvelope(outbox);
                boolean published = publisher.publish(
                        envelope,
                        outbox.getTopic(),
                        outbox.getTag(),
                        outbox.getMessageKey()
                );
                if (published) {
                    outboxService.markSent(outbox);
                } else {
                    outboxService.markRetryFailure(outbox, "publish returned false");
                }
            } catch (Exception exception) {
                outboxService.markRetryFailure(outbox, exception.getMessage());
                log.warn("Failed to relay outbox event, will retry later. eventId={}",
                        outbox.getEventId(), exception);
            }
        }
    }
}
