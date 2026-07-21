package com.fragment.labbooking.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ResourceAddDTO {

    @NotBlank(message = "资源编号不能为空")
    private String resourceCode;

    @NotBlank(message = "资源名称不能为空")
    private String resourceName;

    @NotBlank(message = "资源类型不能为空")
    private String resourceType;

    @NotBlank(message = "资源状态不能为空")
    private String status;

    private String location;

    private String description;
}
