USE lab_booking;

SET @schema_name = DATABASE();

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'reservation' AND COLUMN_NAME = 'checked_in_at') = 0,
    'ALTER TABLE reservation ADD COLUMN checked_in_at DATETIME NULL AFTER slot_end_datetime',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'reservation' AND COLUMN_NAME = 'auto_cancel_deadline') = 0,
    'ALTER TABLE reservation ADD COLUMN auto_cancel_deadline DATETIME NULL AFTER checked_in_at',
    'SELECT 1'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE reservation
SET auto_cancel_deadline = DATE_ADD(slot_start_datetime, INTERVAL 15 MINUTE)
WHERE status = 'BOOKED'
  AND slot_start_datetime IS NOT NULL
  AND auto_cancel_deadline IS NULL;
