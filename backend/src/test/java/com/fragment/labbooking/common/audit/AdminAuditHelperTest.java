package com.fragment.labbooking.common.audit;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.common.auth.UserContext;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.entity.AdminAuditLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAuditHelperTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void executeShouldPropagateSuccessDispatchFailureToPreserveTransactionRollback() {
        AdminAuditService auditService = mock(AdminAuditService.class);
        AdminAuditDispatchService dispatchService = mock(AdminAuditDispatchService.class);
        AdminAuditHelper helper = new AdminAuditHelper(auditService, dispatchService, true);
        AdminAuditLog auditLog = new AdminAuditLog();
        auditLog.setEventId("AUDIT-1");
        UserContext.set(new LoginUser(1L, "admin", "管理员", "ADMIN", "13800000000"));

        when(auditService.buildSuccessLog(UserContext.get(), "RESOURCE", "ADD", "RESOURCE", 9L, "resourceId=9"))
                .thenReturn(auditLog);
        org.mockito.Mockito.doThrow(new IllegalStateException("outbox insert failed"))
                .when(dispatchService).dispatch(auditLog);

        assertThatThrownBy(() -> helper.execute(
                "RESOURCE",
                "ADD",
                "RESOURCE",
                () -> 9L,
                () -> "resourceId=9",
                () -> { }
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox insert failed");

        verify(dispatchService).dispatch(auditLog);
    }

    @Test
    void executeShouldRecordFailureAuditAndKeepOriginalBusinessException() {
        AdminAuditService auditService = mock(AdminAuditService.class);
        AdminAuditDispatchService dispatchService = mock(AdminAuditDispatchService.class);
        AdminAuditHelper helper = new AdminAuditHelper(auditService, dispatchService, true);
        AdminAuditLog failureLog = new AdminAuditLog();
        failureLog.setEventId("AUDIT-2");
        UserContext.set(new LoginUser(1L, "admin", "管理员", "ADMIN", "13800000000"));

        when(auditService.buildFailureLog(UserContext.get(), "RESOURCE", "DELETE", "RESOURCE", 7L, "resourceId=7", "资源不存在"))
                .thenReturn(failureLog);

        assertThatThrownBy(() -> helper.execute(
                "RESOURCE",
                "DELETE",
                "RESOURCE",
                () -> 7L,
                () -> "resourceId=7",
                () -> { throw new BusinessException("资源不存在"); }
        )).isInstanceOf(BusinessException.class)
                .hasMessage("资源不存在");

        verify(dispatchService).dispatchInNewTransaction(failureLog);
    }
}
