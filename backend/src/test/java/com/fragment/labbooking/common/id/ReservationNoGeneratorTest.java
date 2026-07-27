package com.fragment.labbooking.common.id;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservationNoGeneratorTest {

    private final ReservationNoGenerator generator = new ReservationNoGenerator();

    @Test
    void shouldGeneratePrefixedUniqueNumbersWithIdWorker() {
        Set<String> numbers = new HashSet<>();

        for (int i = 0; i < 1_000; i++) {
            String reservationNo = generator.nextReservationNo();
            String requestNo = generator.nextRequestNo();
            assertTrue(reservationNo.matches("RES\\d+"));
            assertTrue(requestNo.matches("REQ\\d+"));
            numbers.add(reservationNo);
            numbers.add(requestNo);
        }

        assertEquals(2_000, numbers.size());
    }
}
