package com.fragment.labbooking.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@TableName(value = "qa_record")
@Data
public class QaRecord {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "user_id")
    private Long userId;

    @TableField(value = "session_id")
    private String sessionId;

    @TableField(value = "question")
    private String question;

    @TableField(value = "answer")
    private String answer;

    @TableField(value = "question_type")
    private String questionType;

    @TableField(value = "latency_ms")
    private Integer latencyMs;

    @TableField(value = "status")
    private String status;

    @TableField(value = "trace_id")
    private String traceId;

    @TableField(value = "model_name")
    private String modelName;

    @TableField(value = "created_at")
    private LocalDateTime createdAt;
}
