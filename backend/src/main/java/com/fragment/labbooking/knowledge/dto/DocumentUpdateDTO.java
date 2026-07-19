package com.fragment.labbooking.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DocumentUpdateDTO {

    private Long id;

    @NotBlank(message = "标题不能为空")
    private String title;

    private String category;
    private String tags;
}
