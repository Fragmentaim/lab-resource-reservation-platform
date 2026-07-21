ALTER TABLE admin_audit_log
    ADD COLUMN event_id VARCHAR(64) NULL AFTER id;

UPDATE admin_audit_log
SET event_id = CONCAT('legacy-audit-', id)
WHERE event_id IS NULL
   OR event_id = '';

ALTER TABLE admin_audit_log
    MODIFY COLUMN event_id VARCHAR(64) NOT NULL;

ALTER TABLE admin_audit_log
    ADD CONSTRAINT uk_admin_audit_event_id UNIQUE (event_id);
