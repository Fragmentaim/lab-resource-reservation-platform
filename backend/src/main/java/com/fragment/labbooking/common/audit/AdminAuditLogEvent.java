package com.fragment.labbooking.common.audit;

import com.fragment.labbooking.entity.AdminAuditLog;
import lombok.Data;
import org.springframework.beans.BeanUtils;

import java.time.LocalDateTime;

@Data
public class AdminAuditLogEvent {

    private String eventId;
    private Long operatorId;
    private String operatorUsername;
    private String module;
    private String action;
    private String targetType;
    private Long targetId;
    private String result;
    private String summary;
    private String errorMessage;
    private LocalDateTime createdAt;

    public static AdminAuditLogEvent from(AdminAuditLog auditLog) {
        AdminAuditLogEvent event = new AdminAuditLogEvent();
        BeanUtils.copyProperties(auditLog, event);
        return event;
    }

    public AdminAuditLog toLog() {
        AdminAuditLog auditLog = new AdminAuditLog();
        BeanUtils.copyProperties(this, auditLog);
        return auditLog;
    }
}
