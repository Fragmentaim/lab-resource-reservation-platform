package com.fragment.labbooking.knowledge.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Privacy-safe context accounting for an RAG run. It intentionally excludes
 * raw questions, answers, session summaries and evidence text.
 */
@Data
public class ContextTraceVO {
    private String traceId;
    private Boolean rewriteApplied;
    private Integer summaryTokens;
    private Integer historyTokens;
    private Integer evidenceTokens;
    private Integer totalPromptTokens;
    private Integer selectedSourceCount;
    private Integer droppedSourceCount;
    private LocalDateTime createdAt;
}
