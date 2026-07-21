package com.fragment.labbooking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("message_outbox")
@Data
public class MessageOutbox {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("event_id")
    private String eventId;

    @TableField("aggregate_type")
    private String aggregateType;

    @TableField("aggregate_id")
    private String aggregateId;

    @TableField("event_type")
    private String eventType;

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

    @TableField("available_at")
    private LocalDateTime availableAt;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("locked_until")
    private LocalDateTime lockedUntil;

    @TableField("last_error_message")
    private String lastErrorMessage;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    @TableField("sent_at")
    private LocalDateTime sentAt;
}
