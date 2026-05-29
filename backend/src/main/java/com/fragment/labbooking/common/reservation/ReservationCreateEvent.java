package com.fragment.labbooking.common.reservation;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ReservationCreateEvent {

    private String requestNo;
    private Long userId;
    private Long resourceId;
    private Long slotId;
    private String sourceType;
    private LocalDateTime createdAt;
}
