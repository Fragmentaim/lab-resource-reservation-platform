CREATE TABLE IF NOT EXISTS ai_tool_call_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id VARCHAR(64) NOT NULL,
    tool_name VARCHAR(64) NOT NULL,
    actor_user_id BIGINT NULL,
    actor_role VARCHAR(32) NULL,
    subject_user_id BIGINT NULL,
    access_scope VARCHAR(32) NULL,
    result VARCHAR(16) NOT NULL,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    parameter_summary VARCHAR(512) NULL,
    error_message VARCHAR(512) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ai_tool_call_trace_id (trace_id),
    KEY idx_ai_tool_call_actor_created (actor_user_id, created_at),
    KEY idx_ai_tool_call_tool_created (tool_name, created_at),
    KEY idx_ai_tool_call_result_created (result, created_at)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
