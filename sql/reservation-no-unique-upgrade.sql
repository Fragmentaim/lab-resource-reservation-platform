ALTER TABLE reservation
    ADD CONSTRAINT uk_reservation_no UNIQUE (reservation_no);
