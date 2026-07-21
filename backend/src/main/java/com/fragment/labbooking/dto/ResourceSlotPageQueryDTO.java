package com.fragment.labbooking.dto;

import lombok.Data;

@Data
public class ResourceSlotPageQueryDTO {

    private Long resourceId;

    private String status;

    private String slotType;

    private Integer pageNum;

    private Integer pageSize;
}
