package com.fragment.labbooking.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class QaAskDTO {

    @NotBlank(message = "问题不能为空")
    private String question;

    private String sessionId;
}
