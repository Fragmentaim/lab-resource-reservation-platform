package com.fragment.labbooking.vo;

import lombok.Data;

@Data
public class CurrentUserVO {

    private Long id;
    private String username;
    private String nickname;
    private String role;
    private String phone;
}
