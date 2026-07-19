package com.fragment.labbooking.knowledge.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ReservationAssistantReservationVO {

    private Long reservationId;
    private String reservationNo;
    private String resourceName;
    private String location;
    private String status;
    private LocalDateTime startDatetime;
    private LocalDateTime endDatetime;
    private Boolean checkedIn;
    private LocalDateTime autoCancelDeadline;
}
