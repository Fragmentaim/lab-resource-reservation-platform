package com.fragment.labbooking.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class SysDictDataUpdateDTO {

    @NotNull(message = "字典值ID不能为空")
    private Long id;

    @NotBlank(message = "显示名称不能为空")
    private String dictLabel;

    @NotBlank(message = "编码值不能为空")
    private String dictValue;

    @Pattern(regexp = "^[YyNn]$", message = "默认项标记只能为 Y 或 N")
    private String isDefault;

    @Min(value = 0, message = "排序值不能小于 0")
    private Integer sortOrder;
}
