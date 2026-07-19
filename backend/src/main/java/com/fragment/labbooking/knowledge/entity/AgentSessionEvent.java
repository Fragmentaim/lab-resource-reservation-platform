package com.fragment.labbooking.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Privacy-safe event metadata; raw user/assistant text remains in qa_record. */
@TableName("agent_session_event")
@Data
public class AgentSessionEvent {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField("event_id") private String eventId;
    @TableField("session_id") private String sessionId;
    @TableField("user_id") private Long userId;
    @TableField("turn_no") private Integer turnNo;
    @TableField("event_type") private String eventType;
    @TableField("qa_record_id") private Long qaRecordId;
    @TableField("trace_id") private String traceId;
    @TableField("token_count") private Integer tokenCount;
    @TableField("content_hash") private String contentHash;
    @TableField("payload_json") private String payloadJson;
    @TableField("created_at") private LocalDateTime createdAt;
}
