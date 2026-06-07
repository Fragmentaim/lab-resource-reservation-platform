USE lab_booking;

CREATE TABLE IF NOT EXISTS delay_message_outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    business_key VARCHAR(128) NOT NULL,
    topic VARCHAR(128) NOT NULL,
    tag VARCHAR(64) NOT NULL,
    message_key VARCHAR(128) NOT NULL,
    deliver_at DATETIME NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    last_error_message VARCHAR(512) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    sent_at DATETIME NULL,
    UNIQUE KEY uk_delay_message_event_id (event_id),
    KEY idx_delay_message_status_id (status, id),
    KEY idx_delay_message_event_type_status (event_type, status),
    KEY idx_delay_message_deliver_at (deliver_at)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
