-- Unified transactional outbox for immediate and scheduled MQ events.

CREATE TABLE IF NOT EXISTS message_outbox (
    id                 BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id           VARCHAR(191) NOT NULL,
    aggregate_type     VARCHAR(64)  NOT NULL,
    aggregate_id       VARCHAR(128) NOT NULL,
    event_type         VARCHAR(64)  NOT NULL,
    topic              VARCHAR(128) NOT NULL,
    tag                VARCHAR(64)  NULL,
    message_key        VARCHAR(128) NULL,
    payload            TEXT         NOT NULL,
    status             VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    available_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retry_count        INT          NOT NULL DEFAULT 0,
    locked_until       DATETIME     NULL,
    last_error_message VARCHAR(512) NULL,
    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    sent_at            DATETIME     NULL,
    UNIQUE KEY uk_event_id (event_id),
    KEY idx_status_available_id (status, available_at, id),
    KEY idx_status_locked_until (status, locked_until),
    KEY idx_aggregate (aggregate_type, aggregate_id),
    KEY idx_event_type_status (event_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
