package com.fragment.labbooking.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@TableName(value = "qa_feedback")
@Data
public class QaFeedback {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "qa_record_id")
    private Long qaRecordId;

    @TableField(value = "user_id")
    private Long userId;

    @TableField(value = "helpful")
    private Integer helpful;

    @TableField(value = "comment")
    private String comment;

    @TableField(value = "created_at")
    private LocalDateTime createdAt;
}
