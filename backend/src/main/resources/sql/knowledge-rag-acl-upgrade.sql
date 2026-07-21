-- Run once for databases created before document ACL was introduced.
-- Existing documents become PUBLIC so the upgrade does not silently remove access.

ALTER TABLE kb_document
    ADD COLUMN visibility VARCHAR(32) NOT NULL DEFAULT 'PUBLIC'
    COMMENT 'PUBLIC/UPLOADER_ONLY/ADMIN_ONLY/SPECIFIED_USERS' AFTER uploader_id;

ALTER TABLE kb_document
    ADD KEY idx_visibility_status (visibility, status);

CREATE TABLE IF NOT EXISTS kb_document_access (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_document_user (document_id, user_id),
    KEY idx_user_document (user_id, document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
