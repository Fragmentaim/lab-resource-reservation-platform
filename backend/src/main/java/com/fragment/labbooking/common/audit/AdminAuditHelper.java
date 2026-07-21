package com.fragment.labbooking.common.audit;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.common.auth.UserContext;
import com.fragment.labbooking.entity.AdminAuditLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
@Slf4j
public class AdminAuditHelper {

    private final AdminAuditService adminAuditService;
    private final AdminAuditDispatchService adminAuditDispatchService;
    private final boolean enabled;

    public AdminAuditHelper(AdminAuditService adminAuditService,
                            AdminAuditDispatchService adminAuditDispatchService,
                            @Value("${app.audit.enabled:true}") boolean enabled) {
        this.adminAuditService = adminAuditService;
        this.adminAuditDispatchService = adminAuditDispatchService;
        this.enabled = enabled;
    }

    public void execute(String module,
                        String action,
                        String targetType,
                        Supplier<Long> targetIdSupplier,
                        Supplier<String> summarySupplier,
                        Runnable actionRunner) {
        if (!enabled) {
            actionRunner.run();
            return;
        }

        LoginUser operator = UserContext.get();
        if (operator == null) {
            actionRunner.run();
            return;
        }

        try {
            actionRunner.run();
        } catch (RuntimeException exception) {
            dispatchFailure(operator, module, action, targetType, targetIdSupplier, summarySupplier, exception);
            throw exception;
        }

        dispatchSuccess(operator, module, action, targetType, targetIdSupplier, summarySupplier);
    }

    private void dispatchSuccess(LoginUser operator,
                                 String module,
                                 String action,
                                 String targetType,
                                 Supplier<Long> targetIdSupplier,
                                 Supplier<String> summarySupplier) {
        AdminAuditLog auditLog = adminAuditService.buildSuccessLog(
                operator,
                module,
                action,
                targetType,
                safeGet(targetIdSupplier),
                safeText(summarySupplier)
        );
        if (auditLog == null) {
            return;
        }
        adminAuditDispatchService.dispatch(auditLog);
    }

    private void dispatchFailure(LoginUser operator,
                                 String module,
                                 String action,
                                 String targetType,
                                 Supplier<Long> targetIdSupplier,
                                 Supplier<String> summarySupplier,
                                 RuntimeException businessException) {
        AdminAuditLog failureLog = adminAuditService.buildFailureLog(
                operator,
                module,
                action,
                targetType,
                safeGet(targetIdSupplier),
                safeText(summarySupplier),
                businessException.getMessage()
        );
        if (failureLog == null) {
            return;
        }

        try {
            adminAuditDispatchService.dispatchInNewTransaction(failureLog);
        } catch (Exception exception) {
            log.warn("Failed to enqueue failure audit log, original business exception will continue. eventId={}",
                    failureLog.getEventId(), exception);
        }
    }

    private Long safeGet(Supplier<Long> supplier) {
        try {
            return supplier == null ? null : supplier.get();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String safeText(Supplier<String> supplier) {
        try {
            return supplier == null ? null : supplier.get();
        } catch (Exception ignored) {
            return null;
        }
    }
}
