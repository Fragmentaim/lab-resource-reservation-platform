package com.fragment.labbooking.dto;


import lombok.Data;

@Data
public class SysDictDataPageQueryDTO {

    private String type;
    private Integer pageNum;
    private Integer pageSize;
    private String keyword;


}
