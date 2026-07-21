package com.fragment.labbooking.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserNotificationVO {

    private Long id;
    private String type;
    private String title;
    private String content;
    private Long relatedReservationId;
    private Boolean read;
    private LocalDateTime createdAt;
    private LocalDateTime readAt;
}
