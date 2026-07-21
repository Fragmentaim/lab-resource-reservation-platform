package com.fragment.labbooking.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@TableName(value = "qa_context_trace")
@Data
public class QaContextTrace {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "trace_id")
    private String traceId;

    @TableField(value = "qa_record_id")
    private Long qaRecordId;

    @TableField(value = "session_id")
    private String sessionId;

    @TableField(value = "user_id")
    private Long userId;

    @TableField(value = "original_question")
    private String originalQuestion;

    @TableField(value = "rewritten_question")
    private String rewrittenQuestion;

    @TableField(value = "rewrite_applied")
    private Boolean rewriteApplied;

    @TableField(value = "summary_tokens")
    private Integer summaryTokens;

    @TableField(value = "history_tokens")
    private Integer historyTokens;

    @TableField(value = "evidence_tokens")
    private Integer evidenceTokens;

    @TableField(value = "total_prompt_tokens")
    private Integer totalPromptTokens;

    @TableField(value = "selected_source_count")
    private Integer selectedSourceCount;

    @TableField(value = "dropped_source_count")
    private Integer droppedSourceCount;

    @TableField(value = "context_json")
    private String contextJson;

    @TableField(value = "created_at")
    private LocalDateTime createdAt;
}
