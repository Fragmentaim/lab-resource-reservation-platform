package com.fragment.labbooking.knowledge.dto;

import lombok.Data;

@Data
public class DocumentPageQueryDTO {

    private Integer pageNum;
    private Integer pageSize;
    private String category;
    private String status;
    private String keyword;
}
