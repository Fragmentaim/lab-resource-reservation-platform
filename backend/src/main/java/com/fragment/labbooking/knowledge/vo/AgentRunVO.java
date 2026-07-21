package com.fragment.labbooking.knowledge.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentRunVO {
    private Long id;
    private String traceId;
    private Long qaRecordId;
    private String sessionId;
    private Long userId;
    private String route;
    private String modelName;
    private String status;
    private Integer totalLatencyMs;
    private Integer sourceCount;
    private Integer stepCount;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
