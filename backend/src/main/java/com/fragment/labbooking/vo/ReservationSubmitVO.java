package com.fragment.labbooking.vo;

import lombok.Data;

@Data
public class ReservationSubmitVO {

    private String requestId;
    private String status;
    private Long reservationId;
    private String reservationNo;
    private String message;
}
