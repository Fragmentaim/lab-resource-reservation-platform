package com.fragment.labbooking.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ResourceVO {
    private Long id;
    private String resourceCode;
    private String resourceName;
    private String resourceType;
    private String resourceTypeDesc;
    private String status;
    private String statusDesc;
    private String location;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
