CREATE TABLE IF NOT EXISTS admin_audit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    operator_id BIGINT NOT NULL,
    operator_username VARCHAR(64) NOT NULL,
    module VARCHAR(32) NOT NULL,
    action VARCHAR(32) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id BIGINT NULL,
    result VARCHAR(16) NOT NULL,
    summary TEXT NULL,
    error_message VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_admin_audit_event_id (event_id),
    KEY idx_admin_audit_operator_id (operator_id),
    KEY idx_admin_audit_module_action (module, action),
    KEY idx_admin_audit_created_at (created_at)
);

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
