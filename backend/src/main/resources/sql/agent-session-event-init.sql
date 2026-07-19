CREATE TABLE IF NOT EXISTS agent_session_event (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id      VARCHAR(64)  NOT NULL,
    session_id    VARCHAR(64)  NOT NULL,
    user_id       BIGINT       NOT NULL,
    turn_no       INT          NOT NULL,
    event_type    VARCHAR(32)  NOT NULL COMMENT 'USER_INPUT/ASSISTANT_OUTPUT',
    qa_record_id  BIGINT       NOT NULL,
    trace_id      VARCHAR(64)  NULL,
    token_count   INT          NOT NULL DEFAULT 0,
    content_hash  CHAR(64)     NULL,
    payload_json  TEXT         NULL COMMENT 'privacy-safe event metadata only',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_session_event_id (event_id),
    KEY idx_agent_session_turn (session_id, turn_no, id),
    KEY idx_agent_session_record (qa_record_id),
    KEY idx_agent_session_trace (trace_id),
    KEY idx_agent_session_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
