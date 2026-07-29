package com.fragment.labbooking.common.audit;

import com.fragment.labbooking.entity.AdminAuditLog;
import com.fragment.labbooking.common.outbox.MessageOutboxService;
import com.fragment.labbooking.mapper.AdminAuditLogMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAuditDispatchService {

    private static final String AGGREGATE_TYPE = "ADMIN_AUDIT";
    private static final String EVENT_TYPE = "ADMIN_AUDIT_LOG";
    private static final String ADMIN_AUDIT_TAG = "admin-audit";

    private final MessageOutboxService messageOutboxService;
    private final AdminAuditLogMapper adminAuditLogMapper;
    private final boolean mqEnabled;
    private final String topic;

    public AdminAuditDispatchService(MessageOutboxService messageOutboxService,
                                     AdminAuditLogMapper adminAuditLogMapper,
                                     @Value("${app.audit.mq.enabled:true}") boolean mqEnabled,
                                     @Value("${app.audit.mq.topic:admin-audit-log}") String topic) {
        this.messageOutboxService = messageOutboxService;
        this.adminAuditLogMapper = adminAuditLogMapper;
        this.mqEnabled = mqEnabled;
        this.topic = topic;
    }

    public void dispatch(AdminAuditLog auditLog) {
        if (auditLog == null) {
            return;
        }

        if (!mqEnabled) {
            adminAuditLogMapper.insert(auditLog);
            return;
        }

        messageOutboxService.enqueue(
                AGGREGATE_TYPE,
                auditLog.getEventId(),
                EVENT_TYPE,
                topic,
                ADMIN_AUDIT_TAG,
                auditLog.getEventId(),
                null,
                AdminAuditLogEvent.from(auditLog)
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatchInNewTransaction(AdminAuditLog auditLog) {
        dispatch(auditLog);
    }

}
