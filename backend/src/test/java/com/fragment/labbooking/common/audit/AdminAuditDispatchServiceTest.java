package com.fragment.labbooking.common.audit;

import com.fragment.labbooking.entity.AdminAuditLog;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AdminAuditDispatchServiceTest {

    @Test
    void dispatchShouldWriteDirectlyWhenMqDisabled() {
        AdminAuditOutboxService outboxService = mock(AdminAuditOutboxService.class);
        AdminAuditLogWriter logWriter = mock(AdminAuditLogWriter.class);
        AdminAuditDispatchService dispatchService = new AdminAuditDispatchService(outboxService, logWriter, false);
        AdminAuditLog auditLog = new AdminAuditLog();

        dispatchService.dispatch(auditLog);

        verify(logWriter).write(auditLog);
        verify(outboxService, never()).enqueue(auditLog);
    }

    @Test
    void dispatchShouldEnqueueOutboxWhenMqEnabled() {
        AdminAuditOutboxService outboxService = mock(AdminAuditOutboxService.class);
        AdminAuditLogWriter logWriter = mock(AdminAuditLogWriter.class);
        AdminAuditDispatchService dispatchService = new AdminAuditDispatchService(outboxService, logWriter, true);
        AdminAuditLog auditLog = new AdminAuditLog();

        dispatchService.dispatch(auditLog);

        verify(outboxService).enqueue(auditLog);
        verify(logWriter, never()).write(auditLog);
    }
}
