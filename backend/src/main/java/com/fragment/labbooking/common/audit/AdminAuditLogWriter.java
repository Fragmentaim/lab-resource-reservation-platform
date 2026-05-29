package com.fragment.labbooking.common.audit;

import com.fragment.labbooking.entity.AdminAuditLog;
import com.fragment.labbooking.mapper.AdminAuditLogMapper;
import org.springframework.stereotype.Service;

@Service
public class AdminAuditLogWriter {

    private final AdminAuditLogMapper adminAuditLogMapper;

    public AdminAuditLogWriter(AdminAuditLogMapper adminAuditLogMapper) {
        this.adminAuditLogMapper = adminAuditLogMapper;
    }

    public void write(AdminAuditLog adminAuditLog) {
        adminAuditLogMapper.insert(adminAuditLog);
    }
}
