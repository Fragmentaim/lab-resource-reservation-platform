package com.fragment.labbooking.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AdminDashboardVO {

    private Long totalReservationCount;

    private Long todayReservationCount;

    private Long activeReservationCount;

    private Long finishedReservationCount;

    private Long cancelledReservationCount;

    private Long totalResourceCount;

    private Long availableResourceCount;

    private Long openSlotCount;

    private Long hotOpenSlotCount;

    private Long pendingAsyncRequestCount;

    private Long dispatchPendingRequestCount;

    private Long failedAsyncRequestCount;

    private Long pendingReminderCount;

    private Long unreadNotificationCount;

    private Long pendingAuditOutboxCount;

    private List<AdminDashboardHotSlotVO> hotSlots = new ArrayList<>();

    private List<AdminDashboardResourceHeatVO> topResources = new ArrayList<>();

    private List<AdminDashboardRequestVO> recentRequests = new ArrayList<>();
}
