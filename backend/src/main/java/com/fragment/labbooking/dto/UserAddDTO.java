package com.fragment.labbooking.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UserAddDTO {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

    @NotBlank(message = "昵称不能为空")
    private String nickname;

    @NotBlank(message = "角色不能为空")
    private String role;

    private String phone;

    @NotBlank(message = "状态不能为空")
    private String status;
}
