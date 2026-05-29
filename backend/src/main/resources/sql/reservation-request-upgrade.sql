USE lab_booking;

CREATE TABLE IF NOT EXISTS reservation_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_no VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    resource_id BIGINT NOT NULL,
    slot_id BIGINT NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    dispatch_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    dispatch_retry_count INT NOT NULL DEFAULT 0,
    last_dispatch_error_message VARCHAR(512) NULL,
    fail_reason VARCHAR(255) NULL,
    reservation_id BIGINT NULL,
    reservation_no VARCHAR(64) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    completed_at DATETIME NULL,
    UNIQUE KEY uk_reservation_request_no (request_no),
    KEY idx_reservation_request_user_created (user_id, created_at),
    KEY idx_reservation_request_dispatch_status_created (dispatch_status, created_at),
    KEY idx_reservation_request_status_created (status, created_at)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
