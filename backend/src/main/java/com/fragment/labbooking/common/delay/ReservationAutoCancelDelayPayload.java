package com.fragment.labbooking.common.delay;

import lombok.Data;

@Data
public class ReservationAutoCancelDelayPayload {

    private Long reservationId;

    public ReservationAutoCancelDelayPayload() {
    }

    public ReservationAutoCancelDelayPayload(Long reservationId) {
        this.reservationId = reservationId;
    }
}
