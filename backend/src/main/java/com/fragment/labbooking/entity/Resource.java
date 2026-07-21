package com.fragment.labbooking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 资源表
 * @TableName resource
 */
@TableName(value ="resource")
@Data
public class Resource {
    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 资源编号，如 TC-01、TF-01、WB-01、DV-01
     */
    @TableField(value = "resource_code")
    private String resourceCode;

    /**
     * 资源名称，如 1号靶车、室内测试场、联调工位A
     */
    @TableField(value = "resource_name")
    private String resourceName;

    /**
     * 资源类型：TARGET_CAR/TEST_FIELD/WORKBENCH/DEVICE
     */
    @TableField(value = "resource_type")
    private String resourceType;

    /**
     * 状态：AVAILABLE/MAINTAINING/DISABLED
     */
    @TableField(value = "status")
    private String status;

    /**
     * 所在位置
     */
    @TableField(value = "location")
    private String location;

    /**
     * 补充描述
     */
    @TableField(value = "description")
    private String description;

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