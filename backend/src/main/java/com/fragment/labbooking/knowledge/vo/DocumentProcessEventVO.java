package com.fragment.labbooking.knowledge.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class DocumentProcessEventVO {
    private String docVersion;
    private String traceId;
    private Integer attempt;
    private String stage;
    private String status;
    private String message;
    private Map<String, Object> detail;
    private LocalDateTime createdAt;
}
