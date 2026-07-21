package com.fragment.labbooking.knowledge.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiToolCallLogVO {

    private Long id;
    private String traceId;
    private String toolName;
    private Long actorUserId;
    private String actorRole;
    private Long subjectUserId;
    private String accessScope;
    private String result;
    private Long latencyMs;
    private String parameterSummary;
    private String errorMessage;
    private LocalDateTime createdAt;
}
