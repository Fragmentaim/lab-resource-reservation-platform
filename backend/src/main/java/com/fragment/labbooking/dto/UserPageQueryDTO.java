package com.fragment.labbooking.dto;

import lombok.Data;

@Data
public class UserPageQueryDTO {

    private String keyword;
    private String role;
    private String status;

    private Integer pageNum;
    private Integer pageSize;
}
