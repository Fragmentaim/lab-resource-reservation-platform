package com.fragment.labbooking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("reservation_request")
@Data
public class ReservationRequest {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("request_no")
    private String requestNo;

    @TableField("user_id")
    private Long userId;

    @TableField("resource_id")
    private Long resourceId;

    @TableField("slot_id")
    private Long slotId;

    @TableField("active_key")
    private String activeKey;

    @TableField("source_type")
    private String sourceType;

    @TableField("status")
    private String status;

    @TableField("dispatch_status")
    private String dispatchStatus;

    @TableField("dispatch_retry_count")
    private Integer dispatchRetryCount;

    @TableField("last_dispatch_error_message")
    private String lastDispatchErrorMessage;

    @TableField("fail_reason")
    private String failReason;

    @TableField("reservation_id")
    private Long reservationId;

    @TableField("reservation_no")
    private String reservationNo;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;
}
