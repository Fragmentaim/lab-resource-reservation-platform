package com.fragment.labbooking.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("agent_run")
@Data
public class AgentRun {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("trace_id")
    private String traceId;

    @TableField("qa_record_id")
    private Long qaRecordId;

    @TableField("session_id")
    private String sessionId;

    @TableField("user_id")
    private Long userId;

    @TableField("route")
    private String route;

    @TableField("model_name")
    private String modelName;

    @TableField("status")
    private String status;

    @TableField("total_latency_ms")
    private Integer totalLatencyMs;

    @TableField("source_count")
    private Integer sourceCount;

    @TableField("usage_reported")
    private Boolean usageReported;

    @TableField("input_tokens")
    private Long inputTokens;

    @TableField("output_tokens")
    private Long outputTokens;

    @TableField("cached_input_tokens")
    private Long cachedInputTokens;

    @TableField("total_tokens")
    private Long totalTokens;

    @TableField("model_call_count")
    private Integer modelCallCount;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("finished_at")
    private LocalDateTime finishedAt;
}
