package com.fragment.labbooking.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReservationCreateDTO {

    @NotNull(message = "资源ID不能为空")
    private Long resourceId;

    @NotNull(message = "时段ID不能为空")
    private Long slotId;
}
