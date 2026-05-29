-- WARNING:
-- This script rebuilds the lab_booking database from scratch and seeds
-- a minimal runnable dataset for local development/demo use.

CREATE DATABASE IF NOT EXISTS lab_booking
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE lab_booking;

SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS admin_audit_outbox;
DROP TABLE IF EXISTS admin_audit_log;
DROP TABLE IF EXISTS user_notification;
DROP TABLE IF EXISTS reservation_reminder_task;
DROP TABLE IF EXISTS reservation_request;
DROP TABLE IF EXISTS reservation;
DROP TABLE IF EXISTS resource_slot;
DROP TABLE IF EXISTS resource;
DROP TABLE IF EXISTS sys_dict_data;
DROP TABLE IF EXISTS sys_dict_type;
DROP TABLE IF EXISTS sys_user;

SET FOREIGN_KEY_CHECKS = 1;

CREATE TABLE sys_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    nickname VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL,
    phone VARCHAR(32) NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_sys_user_username (username),
    KEY idx_sys_user_role_status (role, status)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE sys_dict_type (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    dict_name VARCHAR(100) NOT NULL,
    dict_type VARCHAR(100) NOT NULL,
    UNIQUE KEY uk_sys_dict_type (dict_type)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE sys_dict_data (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    dict_type VARCHAR(100) NOT NULL,
    dict_label VARCHAR(100) NOT NULL,
    dict_value VARCHAR(100) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    is_default CHAR(1) NOT NULL DEFAULT 'N',
    UNIQUE KEY uk_sys_dict_data_type_value (dict_type, dict_value),
    KEY idx_sys_dict_data_type_sort (dict_type, sort_order)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE resource (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    resource_code VARCHAR(50) NOT NULL,
    resource_name VARCHAR(100) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    location VARCHAR(255) NULL,
    description TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_resource_code (resource_code),
    KEY idx_resource_type_status (resource_type, status)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE resource_slot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    resource_id BIGINT NOT NULL,
    start_datetime DATETIME NOT NULL,
    end_datetime DATETIME NOT NULL,
    slot_type VARCHAR(16) NOT NULL,
    open_time DATETIME NULL,
    total_quota INT NOT NULL,
    remain_quota INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_resource_slot_resource (resource_id),
    KEY idx_resource_slot_status (status),
    KEY idx_resource_slot_type (slot_type),
    KEY idx_resource_slot_start (start_datetime)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE reservation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    reservation_no VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    resource_id BIGINT NOT NULL,
    slot_id BIGINT NOT NULL,
    resource_name VARCHAR(100) NULL,
    resource_code VARCHAR(50) NULL,
    resource_location VARCHAR(255) NULL,
    slot_start_datetime DATETIME NULL,
    slot_end_datetime DATETIME NULL,
    is_active TINYINT NULL,
    status VARCHAR(16) NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    cancel_reason VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_reservation_no (reservation_no),
    UNIQUE KEY uk_reservation_user_slot_active (user_id, slot_id, is_active),
    KEY idx_reservation_user_id (user_id),
    KEY idx_reservation_resource_id (resource_id),
    KEY idx_reservation_slot_id (slot_id),
    KEY idx_reservation_status (status),
    KEY idx_reservation_created_at (created_at)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE reservation_request (
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

CREATE TABLE reservation_reminder_task (
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

CREATE TABLE user_notification (
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

CREATE TABLE admin_audit_log (
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
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE admin_audit_outbox (
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
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    sent_at DATETIME NULL,
    UNIQUE KEY uk_admin_audit_outbox_event_id (event_id),
    KEY idx_admin_audit_outbox_status_created (status, created_at)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

INSERT INTO sys_user (id, username, password_hash, nickname, role, phone, status, created_at, updated_at)
VALUES
    (1, 'admin', '123456', '系统管理员', 'ADMIN', '13800000000', 'ACTIVE', '2026-04-20 09:00:00', '2026-04-20 09:00:00'),
    (2, 'tester', '123456', '测试用户', 'USER', '13900000000', 'ACTIVE', '2026-04-20 09:05:00', '2026-04-20 09:05:00'),
    (3, 'locked_user', '123456', '锁定用户', 'USER', '13700000000', 'LOCKED', '2026-04-20 09:10:00', '2026-04-20 09:10:00');

INSERT INTO sys_dict_type (id, dict_name, dict_type)
VALUES
    (1, '资源类型', 'resource_type'),
    (2, '资源状态', 'resource_status');

INSERT INTO sys_dict_data (id, dict_type, dict_label, dict_value, sort_order, is_default)
VALUES
    (1, 'resource_type', '靶车', 'TARGET_CAR', 1, 'N'),
    (2, 'resource_type', '测试场', 'TEST_FIELD', 2, 'N'),
    (3, 'resource_type', '工位', 'WORKBENCH', 3, 'N'),
    (4, 'resource_type', '设备', 'DEVICE', 4, 'N'),
    (5, 'resource_status', '可用', 'AVAILABLE', 1, 'Y'),
    (6, 'resource_status', '维护中', 'MAINTAINING', 2, 'N'),
    (7, 'resource_status', '已停用', 'DISABLED', 3, 'N');

INSERT INTO resource (id, resource_code, resource_name, resource_type, status, location, description, created_at, updated_at)
VALUES
    (1, 'TC-01', '1号靶车', 'TARGET_CAR', 'AVAILABLE', '室外联调区', '热门预约资源，适合联调与演示。', '2026-04-20 09:20:00', '2026-04-20 09:20:00'),
    (2, 'TF-01', '室内测试场', 'TEST_FIELD', 'AVAILABLE', '测试楼一层', '适合功能验证和室内测试。', '2026-04-20 09:21:00', '2026-04-20 09:21:00'),
    (3, 'WB-01', '联调工位A', 'WORKBENCH', 'MAINTAINING', '联调区A排', '当前处于维护中。', '2026-04-20 09:22:00', '2026-04-20 09:22:00'),
    (4, 'DV-01', '频谱仪', 'DEVICE', 'AVAILABLE', '仪器室', '常用测试设备。', '2026-04-20 09:23:00', '2026-04-20 09:23:00');

INSERT INTO resource_slot (id, resource_id, start_datetime, end_datetime, slot_type, open_time, total_quota, remain_quota, status, created_at, updated_at)
VALUES
    (1, 1, '2026-04-21 09:00:00', '2026-04-21 11:00:00', 'NORMAL', NULL, 4, 3, 'OPEN', '2026-04-20 09:30:00', '2026-04-20 09:30:00'),
    (2, 1, '2026-04-22 14:00:00', '2026-04-22 16:00:00', 'HOT', '2026-04-20 09:00:00', 2, 2, 'OPEN', '2026-04-20 09:31:00', '2026-04-20 09:31:00'),
    (3, 2, '2026-04-21 13:00:00', '2026-04-21 15:00:00', 'NORMAL', NULL, 3, 3, 'OPEN', '2026-04-20 09:32:00', '2026-04-20 09:32:00'),
    (4, 4, '2026-04-23 10:00:00', '2026-04-23 12:00:00', 'NORMAL', NULL, 1, 0, 'OPEN', '2026-04-20 09:33:00', '2026-04-20 09:33:00'),
    (5, 2, '2026-04-24 19:00:00', '2026-04-24 21:00:00', 'HOT', '2026-04-23 12:00:00', 2, 2, 'OPEN', '2026-04-20 09:34:00', '2026-04-20 09:34:00'),
    (6, 3, '2026-04-25 09:00:00', '2026-04-25 12:00:00', 'NORMAL', NULL, 2, 2, 'CLOSED', '2026-04-20 09:35:00', '2026-04-20 09:35:00');

INSERT INTO reservation (
    id, reservation_no, user_id, resource_id, slot_id,
    resource_name, resource_code, resource_location,
    slot_start_datetime, slot_end_datetime,
    is_active, status, source_type, cancel_reason,
    created_at, updated_at
)
VALUES
    (1, 'R202604200001', 2, 1, 1, '1号靶车', 'TC-01', '室外联调区',
     '2026-04-21 09:00:00', '2026-04-21 11:00:00',
     1, 'BOOKED', 'NORMAL', NULL,
     '2026-04-20 10:00:00', '2026-04-20 10:00:00'),

    (2, 'R202604190001', 2, 2, 3, '室内测试场', 'TF-01', '测试楼一层',
     '2026-04-21 13:00:00', '2026-04-21 15:00:00',
     NULL, 'FINISHED', 'NORMAL', NULL,
     '2026-04-19 15:00:00', '2026-04-19 18:00:00'),

    (3, 'R202604180001', 2, 1, 2, '1号靶车', 'TC-01', '室外联调区',
     '2026-04-22 14:00:00', '2026-04-22 16:00:00',
     NULL, 'CANCELLED', 'HOT', '测试后主动取消',
     '2026-04-18 09:00:00', '2026-04-18 12:00:00'),

    (4, 'R202604200002', 1, 4, 4, '频谱仪', 'DV-01', '仪器室',
     '2026-04-23 10:00:00', '2026-04-23 12:00:00',
     1, 'BOOKED', 'NORMAL', NULL,
     '2026-04-20 11:00:00', '2026-04-20 11:00:00');

ALTER TABLE sys_user AUTO_INCREMENT = 10;
ALTER TABLE sys_dict_type AUTO_INCREMENT = 10;
ALTER TABLE sys_dict_data AUTO_INCREMENT = 20;
ALTER TABLE resource AUTO_INCREMENT = 10;
ALTER TABLE resource_slot AUTO_INCREMENT = 20;
ALTER TABLE reservation AUTO_INCREMENT = 20;
ALTER TABLE reservation_request AUTO_INCREMENT = 10;
ALTER TABLE reservation_reminder_task AUTO_INCREMENT = 10;
ALTER TABLE user_notification AUTO_INCREMENT = 10;
