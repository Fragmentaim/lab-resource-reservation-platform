CREATE TABLE IF NOT EXISTS agent_run (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id         VARCHAR(64)  NOT NULL,
    qa_record_id     BIGINT       NOT NULL,
    session_id       VARCHAR(64)  NOT NULL,
    user_id          BIGINT       NOT NULL,
    route            VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/TOOL/KB_RAG',
    model_name       VARCHAR(64)  NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/SUCCEEDED/FAILED',
    total_latency_ms INT          NULL,
    source_count     INT          NOT NULL DEFAULT 0,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at      DATETIME     NULL,
    UNIQUE KEY uk_agent_run_trace_id (trace_id),
    KEY idx_agent_run_status_created (status, created_at),
    KEY idx_agent_run_route_created (route, created_at),
    KEY idx_agent_run_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_step (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    agent_run_id  BIGINT       NOT NULL,
    step_no       INT          NOT NULL,
    step_type     VARCHAR(32)  NOT NULL COMMENT 'REQUEST/TOOL_CALL/RETRIEVAL/ANSWER/FAILURE',
    name          VARCHAR(64)  NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    latency_ms    INT          NOT NULL DEFAULT 0,
    tool_trace_id VARCHAR(64)  NULL,
    detail_json   TEXT         NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_step_no (agent_run_id, step_no),
    KEY idx_agent_step_tool_trace (tool_trace_id),
    KEY idx_agent_step_run_created (agent_run_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
