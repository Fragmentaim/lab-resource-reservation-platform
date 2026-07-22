package com.fragment.labbooking.knowledge.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReservationDraftConfirmDTO {

    @NotNull(message = "请明确确认预约")
    @AssertTrue(message = "未确认的预约草案不能执行")
    private Boolean confirmed;
}
