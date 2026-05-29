ALTER TABLE reservation
    ADD COLUMN is_active TINYINT NULL COMMENT '是否为当前有效预约：1-有效，NULL-无效' AFTER slot_end_datetime;

UPDATE reservation
SET is_active = CASE
                    WHEN status = 'BOOKED' THEN 1
                    ELSE NULL
    END;

ALTER TABLE reservation
    ADD CONSTRAINT uk_reservation_user_slot_active UNIQUE (user_id, slot_id, is_active);
