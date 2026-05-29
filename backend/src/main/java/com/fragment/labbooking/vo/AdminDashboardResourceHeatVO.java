package com.fragment.labbooking.vo;

import lombok.Data;

@Data
public class AdminDashboardResourceHeatVO {

    private Long resourceId;

    private String resourceName;

    private String resourceCode;

    private String resourceType;

    private Long reservationCount;

    private Long activeReservationCount;
}
