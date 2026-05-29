package com.fragment.labbooking.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ResourceSlotAddDTO {

    @NotNull(message = "资源ID不能为空")
    private Long resourceId;

    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startDatetime;

    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endDatetime;

    @NotBlank(message = "时段类型不能为空")
    private String slotType;

    private LocalDateTime openTime;

    @NotNull(message = "总名额不能为空")
    @Min(value = 0, message = "总名额不能小于0")
    private Integer totalQuota;

    @NotBlank(message = "时段状态不能为空")
    private String status;
}
