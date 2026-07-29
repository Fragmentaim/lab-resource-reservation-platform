USE lab_booking;

CREATE TABLE IF NOT EXISTS reservation_request (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    resource_id BIGINT NOT NULL,
    slot_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    reservation_id BIGINT NULL,
    reject_code VARCHAR(64) NULL,
    reject_reason VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at DATETIME NULL,
    UNIQUE KEY uk_reservation_request_id (request_id),
    KEY idx_reservation_request_user_created (user_id, created_at)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

ALTER TABLE user_notification
    ADD COLUMN event_id VARCHAR(191) NULL AFTER user_id,
    ADD UNIQUE KEY uk_user_notification_event_id (event_id);
