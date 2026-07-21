-- Apply once to existing databases after knowledge-rag-module-init.sql.
CREATE TABLE IF NOT EXISTS kb_document_process_event (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT       NOT NULL,
    doc_version VARCHAR(32)  NULL,
    trace_id    VARCHAR(64)  NOT NULL,
    attempt     INT          NOT NULL DEFAULT 0,
    stage       VARCHAR(32)  NOT NULL COMMENT 'QUEUED/CLAIMED/VECTOR_CLEANUP/PARSING/PERSISTING_CHUNKS/COMPLETED/RETRY_PENDING/FAILED',
    status      VARCHAR(16)  NOT NULL COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED',
    message     VARCHAR(512) NOT NULL,
    detail_json TEXT         NULL COMMENT 'privacy-safe processing metadata only',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_kb_process_event_document_created (document_id, created_at),
    KEY idx_kb_process_event_trace_created (trace_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
