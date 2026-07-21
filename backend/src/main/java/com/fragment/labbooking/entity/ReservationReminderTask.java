package com.fragment.labbooking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@TableName("reservation_reminder_task")
@Data
public class ReservationReminderTask {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("reservation_id")
    private Long reservationId;

    @TableField("user_id")
    private Long userId;

    @TableField("resource_id")
    private Long resourceId;

    @TableField("slot_id")
    private Long slotId;

    @TableField("remind_type")
    private String remindType;

    @TableField("title")
    private String title;

    @TableField("content")
    private String content;

    @TableField("plan_send_time")
    private LocalDateTime planSendTime;

    @TableField("status")
    private String status;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("last_error_message")
    private String lastErrorMessage;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    @TableField("sent_at")
    private LocalDateTime sentAt;
}
