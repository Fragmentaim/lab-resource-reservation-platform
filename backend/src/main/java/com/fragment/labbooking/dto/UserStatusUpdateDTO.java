package com.fragment.labbooking.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UserStatusUpdateDTO {

    @NotBlank(message = "状态不能为空")
    private String status;
}
