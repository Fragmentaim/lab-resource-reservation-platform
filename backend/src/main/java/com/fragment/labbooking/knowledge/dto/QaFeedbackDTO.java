package com.fragment.labbooking.knowledge.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class QaFeedbackDTO {

    @NotNull(message = "问答记录ID不能为空")
    private Long qaRecordId;

    @NotNull(message = "反馈不能为空")
    private Integer helpful;

    private String comment;
}
