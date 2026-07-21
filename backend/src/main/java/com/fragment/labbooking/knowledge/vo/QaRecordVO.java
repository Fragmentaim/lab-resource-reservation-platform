package com.fragment.labbooking.knowledge.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class QaRecordVO {

    private Long id;
    private String sessionId;
    private String question;
    private String answer;
    private String questionType;
    private Integer latencyMs;
    private String status;
    private String traceId;
    private String modelName;
    private List<QaSourceVO> sources;
    private LocalDateTime createdAt;
}
