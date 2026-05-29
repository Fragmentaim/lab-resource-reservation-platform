package com.fragment.labbooking.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ReservationRequestVO {

    private String requestNo;
    private String status;
    private String sourceType;
    private String failReason;
    private Long reservationId;
    private String reservationNo;
    private Long resourceId;
    private Long slotId;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
