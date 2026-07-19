package com.fragment.labbooking.common.audit;

import com.fragment.labbooking.entity.AdminAuditLog;
import com.fragment.labbooking.common.outbox.MessageOutboxService;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AdminAuditDispatchServiceTest {

    @Test
    void dispatchShouldWriteDirectlyWhenMqDisabled() {
        MessageOutboxService outboxService = mock(MessageOutboxService.class);
        AdminAuditLogWriter logWriter = mock(AdminAuditLogWriter.class);
        AdminAuditDispatchService dispatchService = new AdminAuditDispatchService(
                outboxService,
                logWriter,
                false,
                "admin-audit-log"
        );
        AdminAuditLog auditLog = new AdminAuditLog();

        dispatchService.dispatch(auditLog);

        verify(logWriter).write(auditLog);
        verify(outboxService, never()).enqueue(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void dispatchShouldEnqueueOutboxWhenMqEnabled() {
        MessageOutboxService outboxService = mock(MessageOutboxService.class);
        AdminAuditLogWriter logWriter = mock(AdminAuditLogWriter.class);
        AdminAuditDispatchService dispatchService = new AdminAuditDispatchService(
                outboxService,
                logWriter,
                true,
                "admin-audit-log"
        );
        AdminAuditLog auditLog = new AdminAuditLog();
        auditLog.setEventId("AUDIT-100");

        dispatchService.dispatch(auditLog);

        verify(outboxService).enqueue(
                eq("ADMIN_AUDIT"),
                eq("AUDIT-100"),
                eq("ADMIN_AUDIT_LOG"),
                eq("admin-audit-log"),
                eq("admin-audit"),
                eq("AUDIT-100"),
                isNull(),
                any(AdminAuditLogEvent.class)
        );
        verify(logWriter, never()).write(auditLog);
    }
}
