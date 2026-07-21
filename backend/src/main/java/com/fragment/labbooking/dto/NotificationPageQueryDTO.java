package com.fragment.labbooking.dto;

import lombok.Data;

@Data
public class NotificationPageQueryDTO {

    private Long pageNum = 1L;

    private Long pageSize = 10L;

    private Boolean read;
}
