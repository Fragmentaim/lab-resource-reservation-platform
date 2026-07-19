package com.fragment.labbooking.common.audit;

import com.fragment.labbooking.entity.AdminAuditLog;
import com.fragment.labbooking.common.outbox.MessageOutboxService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AdminAuditDispatchService {

    private static final String AGGREGATE_TYPE = "ADMIN_AUDIT";
    private static final String EVENT_TYPE = "ADMIN_AUDIT_LOG";

    private final MessageOutboxService messageOutboxService;
    private final AdminAuditLogWriter adminAuditLogWriter;
    private final boolean mqEnabled;
    private final String topic;

    public AdminAuditDispatchService(MessageOutboxService messageOutboxService,
                                     AdminAuditLogWriter adminAuditLogWriter,
                                     @Value("${app.audit.mq.enabled:true}") boolean mqEnabled,
                                     @Value("${app.audit.mq.topic:admin-audit-log}") String topic) {
        this.messageOutboxService = messageOutboxService;
        this.adminAuditLogWriter = adminAuditLogWriter;
        this.mqEnabled = mqEnabled;
        this.topic = topic;
    }

    public void dispatch(AdminAuditLog auditLog) {
        if (auditLog == null) {
            return;
        }

        if (!mqEnabled) {
            adminAuditLogWriter.write(auditLog);
            return;
        }

        String eventId = buildEventId(auditLog);
        messageOutboxService.enqueue(
                AGGREGATE_TYPE,
                eventId,
                EVENT_TYPE,
                topic,
                AdminAuditMqPublisher.TAG,
                eventId,
                null,
                AdminAuditLogEvent.from(auditLog)
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatchInNewTransaction(AdminAuditLog auditLog) {
        dispatch(auditLog);
    }

    private String buildEventId(AdminAuditLog auditLog) {
        if (StringUtils.hasText(auditLog.getEventId())) {
            return auditLog.getEventId();
        }
        return "audit:" + System.nanoTime();
    }
}
