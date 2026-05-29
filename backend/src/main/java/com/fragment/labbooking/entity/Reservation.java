package com.fragment.labbooking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@TableName(value = "reservation")
@Data
public class Reservation {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "reservation_no")
    private String reservationNo;

    @TableField(value = "user_id")
    private Long userId;

    @TableField(value = "resource_id")
    private Long resourceId;

    @TableField(value = "slot_id")
    private Long slotId;

    @TableField(value = "resource_name")
    private String resourceName;

    @TableField(value = "resource_code")
    private String resourceCode;

    @TableField(value = "resource_location")
    private String resourceLocation;

    @TableField(value = "slot_start_datetime")
    private LocalDateTime slotStartDatetime;

    @TableField(value = "slot_end_datetime")
    private LocalDateTime slotEndDatetime;

    @TableField(value = "is_active")
    private Integer isActive;

    @TableField(value = "status")
    private String status;

    @TableField(value = "source_type")
    private String sourceType;

    @TableField(value = "cancel_reason")
    private String cancelReason;

    @TableField(value = "created_at")
    private LocalDateTime createdAt;

    @TableField(value = "updated_at")
    private LocalDateTime updatedAt;
}
