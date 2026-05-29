package com.fragment.labbooking.common.audit;

import com.fragment.labbooking.entity.AdminAuditLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAuditDispatchService {

    private final AdminAuditOutboxService adminAuditOutboxService;
    private final AdminAuditLogWriter adminAuditLogWriter;
    private final boolean mqEnabled;

    public AdminAuditDispatchService(AdminAuditOutboxService adminAuditOutboxService,
                                     AdminAuditLogWriter adminAuditLogWriter,
                                     @Value("${app.audit.mq.enabled:true}") boolean mqEnabled) {
        this.adminAuditOutboxService = adminAuditOutboxService;
        this.adminAuditLogWriter = adminAuditLogWriter;
        this.mqEnabled = mqEnabled;
    }

    public void dispatch(AdminAuditLog auditLog) {
        if (auditLog == null) {
            return;
        }

        if (!mqEnabled) {
            adminAuditLogWriter.write(auditLog);
            return;
        }

        adminAuditOutboxService.enqueue(auditLog);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatchInNewTransaction(AdminAuditLog auditLog) {
        dispatch(auditLog);
    }
}
