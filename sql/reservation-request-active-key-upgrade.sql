USE lab_booking;

ALTER TABLE reservation_request
    ADD COLUMN active_key VARCHAR(128) NULL AFTER slot_id;

CREATE UNIQUE INDEX uk_reservation_request_active_key
    ON reservation_request (active_key);
