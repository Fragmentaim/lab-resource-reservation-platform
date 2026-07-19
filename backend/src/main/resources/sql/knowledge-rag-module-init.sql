-- Knowledge RAG module tables for lab_booking.

CREATE TABLE IF NOT EXISTS kb_document (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    title               VARCHAR(255) NOT NULL,
    file_name           VARCHAR(255) NOT NULL,
    file_url            VARCHAR(512) NOT NULL COMMENT 'storage object key',
    file_size           BIGINT       NOT NULL DEFAULT 0,
    file_type           VARCHAR(32)  NOT NULL COMMENT 'PDF/DOCX/MD/TXT',
    category            VARCHAR(64)  NULL COMMENT 'POLICY/MANUAL/SPEC/FAQ',
    tags                VARCHAR(512) NULL COMMENT 'comma-separated tags',
    status              VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/READY/FAILED',
    doc_version         VARCHAR(32)  NOT NULL DEFAULT 'v1' COMMENT 'document processing version',
    chunk_count         INT          NOT NULL DEFAULT 0,
    error_message       VARCHAR(512) NULL,
    process_trace_id    VARCHAR(64)  NULL COMMENT 'trace id for current processing task',
    retry_count         INT          NOT NULL DEFAULT 0,
    process_started_at  DATETIME     NULL,
    process_finished_at DATETIME     NULL,
    uploader_id         BIGINT       NOT NULL,
    visibility          VARCHAR(32)  NOT NULL DEFAULT 'PUBLIC' COMMENT 'PUBLIC/UPLOADER_ONLY/ADMIN_ONLY/SPECIFIED_USERS',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_status (status),
    KEY idx_doc_version (doc_version),
    KEY idx_process_trace_id (process_trace_id),
    KEY idx_category (category),
    KEY idx_uploader_id (uploader_id),
    KEY idx_visibility_status (visibility, status),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS kb_document_access (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_document_user (document_id, user_id),
    KEY idx_user_document (user_id, document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS kb_chunk (
    id             BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id    BIGINT       NOT NULL,
    chunk_uid      VARCHAR(128) NOT NULL COMMENT 'AI/Qdrant chunk id',
    doc_version    VARCHAR(32)  NOT NULL DEFAULT 'v1',
    chunk_index    INT          NOT NULL COMMENT '0-based sequence within document',
    content        TEXT         NOT NULL,
    token_count    INT          NOT NULL DEFAULT 0,
    page_no        INT          NULL COMMENT 'source page number',
    section_title  VARCHAR(255) NULL,
    title_path     VARCHAR(1024) NULL,
    content_hash   VARCHAR(64)  NULL,
    char_start     INT          NULL,
    char_end       INT          NULL,
    vector_id      VARCHAR(128) NULL COMMENT 'Qdrant point ID',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_doc_version_chunk_idx (document_id, doc_version, chunk_index),
    UNIQUE KEY uk_chunk_uid (chunk_uid),
    KEY idx_document_id (document_id),
    KEY idx_doc_version (doc_version),
    KEY idx_content_hash (content_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS qa_session (
    session_id         VARCHAR(64) PRIMARY KEY,
    user_id            BIGINT       NOT NULL,
    title              VARCHAR(128) NULL,
    summary            TEXT         NULL,
    summary_turn_count INT          NOT NULL DEFAULT 0,
    turn_count         INT          NOT NULL DEFAULT 0,
    last_message_at    DATETIME     NULL,
    last_trace_id      VARCHAR(64)  NULL,
    deleted            TINYINT      NOT NULL DEFAULT 0,
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_user_deleted_last (user_id, deleted, last_message_at),
    KEY idx_last_trace_id (last_trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS qa_record (
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL,
    session_id    VARCHAR(64)  NOT NULL COMMENT 'conversation session UUID',
    question      TEXT         NOT NULL,
    answer        TEXT         NULL,
    question_type VARCHAR(32)  NOT NULL DEFAULT 'KB' COMMENT 'KB/BOOKING/GENERAL',
    latency_ms    INT          NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ANSWERED/FAILED',
    trace_id      VARCHAR(64)  NULL,
    model_name    VARCHAR(64)  NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user_id (user_id),
    KEY idx_session_id (session_id),
    KEY idx_status (status),
    KEY idx_question_type (question_type),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS qa_source (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    qa_record_id     BIGINT       NOT NULL,
    document_id      BIGINT       NOT NULL,
    chunk_id         BIGINT       NULL,
    chunk_uid        VARCHAR(128) NULL COMMENT 'retrieved AI/Qdrant chunk id',
    chunk_index      INT          NULL COMMENT 'chunk order in document version',
    page_no          INT          NULL COMMENT 'source page number',
    section_title    VARCHAR(255) NULL COMMENT 'nearest section title',
    title_path       VARCHAR(1000) NULL COMMENT 'section title path',
    doc_version      VARCHAR(32)  NULL COMMENT 'document version used by retrieval',
    content_hash     VARCHAR(64)  NULL COMMENT 'chunk content hash',
    score            DOUBLE       NULL COMMENT 'similarity score',
    retrieval_score  DOUBLE       NULL COMMENT 'first-stage retrieval score',
    rerank_score     DOUBLE       NULL COMMENT 'second-stage rerank score',
    rerank_provider  VARCHAR(32)  NULL COMMENT 'api/local',
    retrieval_source VARCHAR(64)  NULL COMMENT 'vector/keyword/hybrid',
    excerpt          TEXT         NULL COMMENT 'relevant excerpt shown to user',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_qa_record_id (qa_record_id),
    KEY idx_document_id (document_id),
    KEY idx_chunk_uid (chunk_uid),
    KEY idx_doc_version (document_id, doc_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS qa_context_trace (
    id                    BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id              VARCHAR(64)  NOT NULL,
    qa_record_id          BIGINT       NOT NULL,
    session_id            VARCHAR(64)  NOT NULL,
    user_id               BIGINT       NOT NULL,
    original_question     TEXT         NOT NULL,
    rewritten_question    TEXT         NULL,
    rewrite_applied       TINYINT      NOT NULL DEFAULT 0,
    summary_tokens        INT          NOT NULL DEFAULT 0,
    history_tokens        INT          NOT NULL DEFAULT 0,
    evidence_tokens       INT          NOT NULL DEFAULT 0,
    total_prompt_tokens   INT          NOT NULL DEFAULT 0,
    selected_source_count INT          NOT NULL DEFAULT 0,
    dropped_source_count  INT          NOT NULL DEFAULT 0,
    context_json          MEDIUMTEXT   NULL,
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_trace_id (trace_id),
    KEY idx_qa_record_id (qa_record_id),
    KEY idx_session_created (session_id, created_at),
    KEY idx_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS qa_feedback (
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    qa_record_id BIGINT       NOT NULL,
    user_id      BIGINT       NOT NULL,
    helpful      TINYINT      NOT NULL COMMENT '1=yes, 0=no',
    comment      TEXT         NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_record_user (qa_record_id, user_id),
    KEY idx_qa_record_id (qa_record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
