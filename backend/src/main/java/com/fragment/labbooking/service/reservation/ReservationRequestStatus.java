package com.fragment.labbooking.service.reservation;

import java.util.Set;

public enum ReservationRequestStatus {
    PROCESSING,
    CONFIRMED,
    REJECTED,
    EXPIRED;

    private static final Set<String> TERMINAL =
            Set.of(CONFIRMED.name(), REJECTED.name(), EXPIRED.name());

    public static boolean isTerminal(String status) {
        return TERMINAL.contains(status);
    }
}
