package com.fragment.labbooking.knowledge.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class AgentStepVO {
    private Integer stepNo;
    private String stepType;
    private String name;
    private String status;
    private Integer latencyMs;
    private String toolTraceId;
    private Map<String, Object> detail;
    private LocalDateTime createdAt;
}
