package com.fragment.labbooking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 资源时段表
 * @TableName resource_slot
 */
@TableName(value ="resource_slot")
@Data
public class ResourceSlot {
    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 资源ID
     */
    @TableField(value = "resource_id")
    private Long resourceId;

    /**
     * 开始时间
     */
    @TableField(value = "start_datetime")
    private LocalDateTime startDatetime;

    /**
     * 结束时间
     */
    @TableField(value = "end_datetime")
    private LocalDateTime endDatetime;

    /**
     * 时段类型 NORMAL/HOT
     */
    @TableField(value = "slot_type")
    private String slotType;

    /**
     * 开放预约时间，热门时段使用
     */
    @TableField(value = "open_time")
    private LocalDateTime openTime;

    /**
     * 总名额
     */
    @TableField(value = "total_quota")
    private Integer totalQuota;

    /**
     * 剩余名额
     */
    @TableField(value = "remain_quota")
    private Integer remainQuota;

    /**
     * 状态 OPEN/CLOSED
     */
    @TableField(value = "status")
    private String status;

    /**
     * 创建时间
     */
    @TableField(value = "created_at")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(value = "updated_at")
    private LocalDateTime updatedAt;
}