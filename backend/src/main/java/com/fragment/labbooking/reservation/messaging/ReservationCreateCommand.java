package com.fragment.labbooking.reservation.messaging;

public record ReservationCreateCommand(
        String requestId,
        Long userId,
        Long resourceId,
        Long slotId
) {
}
