package com.fragment.labbooking.knowledge.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AvailableResourceSlotVO {

    private Long resourceId;
    private String resourceName;
    private String resourceType;
    private String location;
    private Long slotId;
    private LocalDateTime startDatetime;
    private LocalDateTime endDatetime;
    private String slotType;
    private Integer remainQuota;
}
