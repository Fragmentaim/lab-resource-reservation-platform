package com.fragment.labbooking.common.audit;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.entity.AdminAuditLog;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class AdminAuditService {

    private static final String RESULT_SUCCESS = "SUCCESS";
    private static final String RESULT_FAILURE = "FAILURE";

    public AdminAuditLog buildSuccessLog(LoginUser operator,
                                         String module,
                                         String action,
                                         String targetType,
                                         Long targetId,
                                         String summary) {
        if (operator == null) {
            return null;
        }
        return buildLog(operator, module, action, targetType, targetId, RESULT_SUCCESS, summary, null);
    }

    public AdminAuditLog buildFailureLog(LoginUser operator,
                                         String module,
                                         String action,
                                         String targetType,
                                         Long targetId,
                                         String summary,
                                         String errorMessage) {
        if (operator == null) {
            return null;
        }
        return buildLog(operator, module, action, targetType, targetId, RESULT_FAILURE, summary, errorMessage);
    }

    private AdminAuditLog buildLog(LoginUser operator,
                                   String module,
                                   String action,
                                   String targetType,
                                   Long targetId,
                                   String result,
                                   String summary,
                                   String errorMessage) {
        AdminAuditLog auditLog = new AdminAuditLog();
        auditLog.setEventId(UUID.randomUUID().toString().replace("-", ""));
        auditLog.setOperatorId(operator.getId());
        auditLog.setOperatorUsername(operator.getUsername());
        auditLog.setModule(module);
        auditLog.setAction(action);
        auditLog.setTargetType(targetType);
        auditLog.setTargetId(targetId);
        auditLog.setResult(result);
        auditLog.setSummary(summary);
        auditLog.setErrorMessage(errorMessage);
        auditLog.setCreatedAt(LocalDateTime.now());
        return auditLog;
    }
}
