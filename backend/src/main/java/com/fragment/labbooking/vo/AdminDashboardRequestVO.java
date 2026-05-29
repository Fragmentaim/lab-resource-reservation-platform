package com.fragment.labbooking.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminDashboardRequestVO {

    private String requestNo;

    private Long userId;

    private String username;

    private Long resourceId;

    private String resourceName;

    private Long slotId;

    private String status;

    private String dispatchStatus;

    private Integer dispatchRetryCount;

    private String failReason;

    private LocalDateTime createdAt;

    private LocalDateTime completedAt;
}
