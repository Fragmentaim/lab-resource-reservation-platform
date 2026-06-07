package com.fragment.labbooking.common.delay;

import lombok.Data;

@Data
public class ReservationRequestTimeoutDelayPayload {

    private String requestNo;

    public ReservationRequestTimeoutDelayPayload() {
    }

    public ReservationRequestTimeoutDelayPayload(String requestNo) {
        this.requestNo = requestNo;
    }
}
