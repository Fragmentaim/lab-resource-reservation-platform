package com.fragment.labbooking.common.delay;

import com.fragment.labbooking.entity.DelayMessageOutbox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class DelayMessageRelay {

    private final DelayMessageOutboxService delayMessageOutboxService;
    private final DelayMessageMqPublisher delayMessageMqPublisher;
    private final RocketMqDelayLevelResolver delayLevelResolver;
    private final boolean enabled;
    private final int batchSize;

    public DelayMessageRelay(DelayMessageOutboxService delayMessageOutboxService,
                             DelayMessageMqPublisher delayMessageMqPublisher,
                             RocketMqDelayLevelResolver delayLevelResolver,
                             @Value("${app.delay-message.enabled:false}") boolean enabled,
                             @Value("${app.delay-message.outbox.batch-size:20}") int batchSize) {
        this.delayMessageOutboxService = delayMessageOutboxService;
        this.delayMessageMqPublisher = delayMessageMqPublisher;
        this.delayLevelResolver = delayLevelResolver;
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.delay-message.outbox.relay-delay-millis:1000}")
    public void relayPendingMessages() {
        if (!enabled) {
            return;
        }

        List<DelayMessageOutbox> batch = delayMessageOutboxService.findPendingBatch(batchSize);
        for (DelayMessageOutbox outbox : batch) {
            try {
                DelayMessageEnvelope envelope = delayMessageOutboxService.toEnvelope(outbox);
                int delayLevel = delayLevelResolver.resolveDelayLevel(outbox.getDeliverAt());
                boolean published = delayMessageMqPublisher.publish(
                        envelope,
                        outbox.getTopic(),
                        outbox.getTag(),
                        outbox.getMessageKey(),
                        delayLevel
                );
                if (published) {
                    delayMessageOutboxService.markSent(outbox);
                } else {
                    delayMessageOutboxService.markRetryFailure(outbox, "publish returned false");
                }
            } catch (Exception exception) {
                delayMessageOutboxService.markRetryFailure(outbox, exception.getMessage());
                log.warn("Failed to relay delay message, will retry later. eventId={}",
                        outbox.getEventId(), exception);
            }
        }
    }
}
