package com.fragment.labbooking.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("ai_tool_call_log")
@Data
public class AiToolCallLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("trace_id")
    private String traceId;

    @TableField("tool_name")
    private String toolName;

    @TableField("actor_user_id")
    private Long actorUserId;

    @TableField("actor_role")
    private String actorRole;

    @TableField("subject_user_id")
    private Long subjectUserId;

    @TableField("access_scope")
    private String accessScope;

    @TableField("result")
    private String result;

    @TableField("latency_ms")
    private Long latencyMs;

    @TableField("parameter_summary")
    private String parameterSummary;

    @TableField("error_message")
    private String errorMessage;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
