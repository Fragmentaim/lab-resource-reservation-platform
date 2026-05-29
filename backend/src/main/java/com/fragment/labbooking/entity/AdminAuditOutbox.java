package com.fragment.labbooking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("admin_audit_outbox")
@Data
public class AdminAuditOutbox {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("event_id")
    private String eventId;

    @TableField("topic")
    private String topic;

    @TableField("tag")
    private String tag;

    @TableField("message_key")
    private String messageKey;

    @TableField("payload")
    private String payload;

    @TableField("status")
    private String status;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("last_error_message")
    private String lastErrorMessage;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    @TableField("sent_at")
    private LocalDateTime sentAt;
}
