USE lab_booking;

ALTER TABLE reservation_request
    DROP INDEX idx_reservation_request_dispatch_status_created,
    DROP COLUMN dispatch_status,
    DROP COLUMN dispatch_retry_count,
    DROP COLUMN last_dispatch_error_message;
