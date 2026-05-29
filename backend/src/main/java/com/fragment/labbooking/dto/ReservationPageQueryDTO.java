package com.fragment.labbooking.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ReservationPageQueryDTO {
    private Long userId;
    private Long resourceId;
    private String status;
    private LocalDateTime createdFrom;
    private LocalDateTime createdTo;
    private Integer pageNum;
    private Integer pageSize;
}
