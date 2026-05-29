package com.fragment.labbooking.common.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.entity.AdminAuditOutbox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class AdminAuditOutboxRelay {

    private final AdminAuditOutboxService adminAuditOutboxService;
    private final AdminAuditMqPublisher adminAuditMqPublisher;
    private final ObjectMapper objectMapper;
    private final boolean auditEnabled;
    private final boolean mqEnabled;
    private final int batchSize;

    public AdminAuditOutboxRelay(AdminAuditOutboxService adminAuditOutboxService,
                                 AdminAuditMqPublisher adminAuditMqPublisher,
                                 ObjectMapper objectMapper,
                                 @Value("${app.audit.enabled:true}") boolean auditEnabled,
                                 @Value("${app.audit.mq.enabled:true}") boolean mqEnabled,
                                 @Value("${app.audit.mq.outbox.batch-size:20}") int batchSize) {
        this.adminAuditOutboxService = adminAuditOutboxService;
        this.adminAuditMqPublisher = adminAuditMqPublisher;
        this.objectMapper = objectMapper;
        this.auditEnabled = auditEnabled;
        this.mqEnabled = mqEnabled;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.audit.mq.outbox.relay-delay-millis:5000}")
    public void relayPendingMessages() {
        if (!auditEnabled || !mqEnabled) {
            return;
        }

        List<AdminAuditOutbox> batch = adminAuditOutboxService.findPendingBatch(batchSize);
        for (AdminAuditOutbox outbox : batch) {
            try {
                AdminAuditLogEvent event = objectMapper.readValue(outbox.getPayload(), AdminAuditLogEvent.class);
                boolean published = adminAuditMqPublisher.publish(
                        event,
                        outbox.getTopic(),
                        outbox.getTag(),
                        outbox.getMessageKey()
                );
                if (published) {
                    adminAuditOutboxService.markSent(outbox);
                } else {
                    adminAuditOutboxService.markRetryFailure(outbox, "publish returned false");
                }
            } catch (Exception exception) {
                adminAuditOutboxService.markRetryFailure(outbox, exception.getMessage());
                log.warn("Failed to relay admin audit outbox event, will retry later. eventId={}",
                        outbox.getEventId(), exception);
            }
        }
    }
}
