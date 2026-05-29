ALTER TABLE reservation
    ADD COLUMN resource_name VARCHAR(100) NULL AFTER slot_id,
    ADD COLUMN resource_code VARCHAR(50) NULL AFTER resource_name,
    ADD COLUMN resource_location VARCHAR(255) NULL AFTER resource_code,
    ADD COLUMN slot_start_datetime DATETIME NULL AFTER resource_location,
    ADD COLUMN slot_end_datetime DATETIME NULL AFTER slot_start_datetime;

UPDATE reservation r
         LEFT JOIN resource res ON res.id = r.resource_id
         LEFT JOIN resource_slot slot ON slot.id = r.slot_id
SET r.resource_name = COALESCE(r.resource_name, res.resource_name),
    r.resource_code = COALESCE(r.resource_code, res.resource_code),
    r.resource_location = COALESCE(r.resource_location, res.location),
    r.slot_start_datetime = COALESCE(r.slot_start_datetime, slot.start_datetime),
    r.slot_end_datetime = COALESCE(r.slot_end_datetime, slot.end_datetime);
