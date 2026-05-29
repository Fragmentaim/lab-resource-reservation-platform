package com.fragment.labbooking.dto;

import lombok.Data;

@Data
public class ResourcePageQueryDTO {

    private String keyword;
    private String resourceType;
    private String status;

    private Integer pageNum;
    private Integer pageSize;

}
