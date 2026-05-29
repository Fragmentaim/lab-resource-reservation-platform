CREATE TABLE IF NOT EXISTS admin_audit_outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    topic VARCHAR(128) NOT NULL,
    tag VARCHAR(64) NOT NULL,
    message_key VARCHAR(128) NOT NULL,
    payload LONGTEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    last_error_message VARCHAR(512) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at DATETIME NULL,
    UNIQUE KEY uk_admin_audit_outbox_event_id (event_id),
    KEY idx_admin_audit_outbox_status_created (status, created_at)
);
