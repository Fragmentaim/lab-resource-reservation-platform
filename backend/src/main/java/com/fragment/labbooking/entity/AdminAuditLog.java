package com.fragment.labbooking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("admin_audit_log")
@Data
public class AdminAuditLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("event_id")
    private String eventId;

    @TableField("operator_id")
    private Long operatorId;

    @TableField("operator_username")
    private String operatorUsername;

    @TableField("module")
    private String module;

    @TableField("action")
    private String action;

    @TableField("target_type")
    private String targetType;

    @TableField("target_id")
    private Long targetId;

    @TableField("result")
    private String result;

    @TableField("summary")
    private String summary;

    @TableField("error_message")
    private String errorMessage;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
