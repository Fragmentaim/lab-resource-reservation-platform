package com.fragment.labbooking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Durable result ledger for an asynchronous hot-reservation request.
 *
 * <p>Redis owns the short-lived acceptance state. This row is created by the
 * confirmation consumer and records only the authoritative MySQL outcome.</p>
 */
@Data
@TableName("reservation_request")
public class ReservationRequest {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("request_id")
    private String requestId;

    @TableField("user_id")
    private Long userId;

    @TableField("resource_id")
    private Long resourceId;

    @TableField("slot_id")
    private Long slotId;

    @TableField("status")
    private String status;

    @TableField("reservation_id")
    private Long reservationId;

    @TableField("reject_code")
    private String rejectCode;

    @TableField("reject_reason")
    private String rejectReason;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;
}
