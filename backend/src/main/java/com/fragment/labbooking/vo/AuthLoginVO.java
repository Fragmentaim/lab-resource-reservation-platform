package com.fragment.labbooking.vo;

import lombok.Data;

@Data
public class AuthLoginVO {

    private String token;
    private String tokenType;
    private Long expiresIn;
    private CurrentUserVO user;
}
