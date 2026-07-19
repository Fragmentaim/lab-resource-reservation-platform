package com.fragment.labbooking.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class DocumentUpdateDTO {

    private Long id;

    @NotBlank(message = "标题不能为空")
    private String title;

    private String category;
    private String tags;
    private String visibility;
    private List<Long> allowedUserIds;
}
