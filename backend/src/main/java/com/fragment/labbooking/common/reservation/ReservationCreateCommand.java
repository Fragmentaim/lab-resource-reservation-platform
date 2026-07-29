package com.fragment.labbooking.common.reservation;

public record ReservationCreateCommand(
        String requestId,
        Long userId,
        Long resourceId,
        Long slotId,
        long expiresAtEpochMillis
) {
}
