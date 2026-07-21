package com.fragment.labbooking.knowledge.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@TableName(value = "qa_session")
@Data
public class QaSession {

    @TableId(value = "session_id")
    private String sessionId;

    @TableField(value = "user_id")
    private Long userId;

    @TableField(value = "title")
    private String title;

    @TableField(value = "summary")
    private String summary;

    @TableField(value = "summary_turn_count")
    private Integer summaryTurnCount;

    @TableField(value = "turn_count")
    private Integer turnCount;

    @TableField(value = "last_message_at")
    private LocalDateTime lastMessageAt;

    @TableField(value = "last_trace_id")
    private String lastTraceId;

    @TableField(value = "deleted")
    private Boolean deleted;

    @TableField(value = "created_at")
    private LocalDateTime createdAt;

    @TableField(value = "updated_at")
    private LocalDateTime updatedAt;
}
