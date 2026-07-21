package com.fragment.labbooking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 
 * @TableName sys_user
 */
@TableName(value ="sys_user")
@Data
public class SysUser {
    /**
     * 
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 登录用户名
     */
    @TableField(value = "username")
    private String username;

    /**
     * 密码哈希
     */
    @TableField(value = "password_hash")
    private String passwordHash;

    /**
     * 昵称
     */
    @TableField(value = "nickname")
    private String nickname;

    /**
     * 角色 USER/ADMIN
     */
    @TableField(value = "role")
    private String role;

    /**
     * 联系电话
     */
    @TableField(value = "phone")
    private String phone;

    /**
     * 状态 ACTIVE/LOCKED
     */
    @TableField(value = "status")
    private String status;

    /**
     * 
     */
    @TableField(value = "created_at")
    private LocalDateTime createdAt;

    /**
     * 
     */
    @TableField(value = "updated_at")
    private LocalDateTime updatedAt;
}