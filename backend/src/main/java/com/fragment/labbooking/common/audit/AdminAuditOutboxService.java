package com.fragment.labbooking.common.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.entity.AdminAuditLog;
import com.fragment.labbooking.entity.AdminAuditOutbox;
import com.fragment.labbooking.mapper.AdminAuditOutboxMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AdminAuditOutboxService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SENT = "SENT";

    private final AdminAuditOutboxMapper adminAuditOutboxMapper;
    private final ObjectMapper objectMapper;
    private final String topic;

    public AdminAuditOutboxService(AdminAuditOutboxMapper adminAuditOutboxMapper,
                                   ObjectMapper objectMapper,
                                   @Value("${app.audit.mq.topic:admin-audit-log}") String topic) {
        this.adminAuditOutboxMapper = adminAuditOutboxMapper;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    public void enqueue(AdminAuditLog auditLog) {
        if (auditLog == null) {
            return;
        }

        adminAuditOutboxMapper.insert(buildOutbox(auditLog));
    }

    public List<AdminAuditOutbox> findPendingBatch(int batchSize) {
        LambdaQueryWrapper<AdminAuditOutbox> queryWrapper = new LambdaQueryWrapper<AdminAuditOutbox>()
                .eq(AdminAuditOutbox::getStatus, STATUS_PENDING)
                .orderByAsc(AdminAuditOutbox::getId)
                .last("LIMIT " + Math.max(batchSize, 1));
        return adminAuditOutboxMapper.selectList(queryWrapper);
    }

    public void markSent(AdminAuditOutbox outbox) {
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<AdminAuditOutbox> updateWrapper = new LambdaUpdateWrapper<AdminAuditOutbox>()
                .eq(AdminAuditOutbox::getId, outbox.getId())
                .eq(AdminAuditOutbox::getStatus, STATUS_PENDING)
                .set(AdminAuditOutbox::getStatus, STATUS_SENT)
                .set(AdminAuditOutbox::getSentAt, now)
                .set(AdminAuditOutbox::getUpdatedAt, now)
                .set(AdminAuditOutbox::getLastErrorMessage, null);
        adminAuditOutboxMapper.update(null, updateWrapper);
    }

    public void markRetryFailure(AdminAuditOutbox outbox, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<AdminAuditOutbox> updateWrapper = new LambdaUpdateWrapper<AdminAuditOutbox>()
                .eq(AdminAuditOutbox::getId, outbox.getId())
                .eq(AdminAuditOutbox::getStatus, STATUS_PENDING)
                .set(AdminAuditOutbox::getRetryCount, outbox.getRetryCount() == null ? 1 : outbox.getRetryCount() + 1)
                .set(AdminAuditOutbox::getLastErrorMessage, truncate(errorMessage))
                .set(AdminAuditOutbox::getUpdatedAt, now);
        adminAuditOutboxMapper.update(null, updateWrapper);
    }

    private AdminAuditOutbox buildOutbox(AdminAuditLog auditLog) {
        LocalDateTime now = LocalDateTime.now();
        AdminAuditLogEvent event = AdminAuditLogEvent.from(auditLog);
        AdminAuditOutbox outbox = new AdminAuditOutbox();
        outbox.setEventId(auditLog.getEventId());
        outbox.setTopic(topic);
        outbox.setTag(AdminAuditMqPublisher.TAG);
        outbox.setMessageKey(auditLog.getEventId());
        outbox.setPayload(writePayload(event));
        outbox.setStatus(STATUS_PENDING);
        outbox.setRetryCount(0);
        outbox.setCreatedAt(now);
        outbox.setUpdatedAt(now);
        return outbox;
    }

    private String writePayload(AdminAuditLogEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize admin audit event for outbox", exception);
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= 512 ? text : text.substring(0, 512);
    }
}
