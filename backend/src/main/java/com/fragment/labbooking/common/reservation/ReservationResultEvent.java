package com.fragment.labbooking.common.reservation;

public record ReservationResultEvent(
        String requestId,
        Long userId,
        Long resourceId,
        Long slotId,
        String status,
        Long reservationId,
        String reservationNo,
        String rejectCode,
        String rejectReason
) {
}
