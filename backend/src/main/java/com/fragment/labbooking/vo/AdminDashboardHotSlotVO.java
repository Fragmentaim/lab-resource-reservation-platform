package com.fragment.labbooking.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminDashboardHotSlotVO {

    private Long slotId;

    private Long resourceId;

    private String resourceName;

    private String resourceCode;

    private LocalDateTime startDatetime;

    private LocalDateTime endDatetime;

    private Integer totalQuota;

    private Integer remainQuota;

    private Integer bookedQuota;

    private Integer occupancyRate;

    private String pressureLevel;
}
