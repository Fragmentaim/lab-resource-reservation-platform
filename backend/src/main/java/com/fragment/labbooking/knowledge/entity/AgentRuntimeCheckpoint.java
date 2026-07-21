package com.fragment.labbooking.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Private, expiring state required to safely rehydrate an interrupted Agent run. */
@TableName("agent_runtime_checkpoint")
@Data
public class AgentRuntimeCheckpoint {

    @TableId("trace_id")
    private String traceId;

    @TableField("agent_run_id")
    private Long agentRunId;

    @TableField("user_id")
    private Long userId;

    @TableField("session_id")
    private String sessionId;

    @TableField("checkpoint_json")
    private String checkpointJson;

    @TableField("expires_at")
    private LocalDateTime expiresAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
