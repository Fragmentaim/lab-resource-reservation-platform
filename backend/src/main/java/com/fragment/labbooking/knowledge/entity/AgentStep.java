package com.fragment.labbooking.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("agent_step")
@Data
public class AgentStep {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("agent_run_id")
    private Long agentRunId;

    @TableField("step_no")
    private Integer stepNo;

    @TableField("step_type")
    private String stepType;

    @TableField("name")
    private String name;

    @TableField("status")
    private String status;

    @TableField("latency_ms")
    private Integer latencyMs;

    @TableField("tool_trace_id")
    private String toolTraceId;

    @TableField("detail_json")
    private String detailJson;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
