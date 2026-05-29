USE lab_booking;

CREATE TABLE IF NOT EXISTS reservation_reminder_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    reservation_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    resource_id BIGINT NOT NULL,
    slot_id BIGINT NOT NULL,
    remind_type VARCHAR(32) NOT NULL,
    title VARCHAR(128) NOT NULL,
    content VARCHAR(512) NOT NULL,
    plan_send_time DATETIME NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    last_error_message VARCHAR(512) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    sent_at DATETIME NULL,
    UNIQUE KEY uk_reservation_reminder_reservation_type (reservation_id, remind_type),
    KEY idx_reservation_reminder_status_plan_send (status, plan_send_time),
    KEY idx_reservation_reminder_user_status (user_id, status)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_notification (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    title VARCHAR(128) NOT NULL,
    content VARCHAR(512) NOT NULL,
    related_reservation_id BIGINT NULL,
    reminder_task_id BIGINT NULL,
    is_read TINYINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at DATETIME NULL,
    UNIQUE KEY uk_user_notification_reminder_task (reminder_task_id),
    KEY idx_user_notification_user_read_created (user_id, is_read, created_at)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
