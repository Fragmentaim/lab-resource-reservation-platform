package com.fragment.labbooking.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ReservationVO {

    private Long id;
    private Long resourceId;
    private String reservationNo;
    private String status;
    private String sourceType;
    private String cancelReason;
    private LocalDateTime createdAt;

    private String resourceName;
    private String resourceCode;
    private String location;

    private LocalDateTime startDatetime;
    private LocalDateTime endDatetime;

    private String userNickname;
    private String userPhone;
}
