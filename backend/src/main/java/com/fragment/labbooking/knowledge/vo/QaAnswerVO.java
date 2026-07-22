package com.fragment.labbooking.knowledge.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class QaAnswerVO {

    private Long recordId;
    private String sessionId;
    private String traceId;
    private String rewrittenQuestion;
    private Boolean rewriteApplied;
    private Map<String, Object> contextStats;
    private List<Map<String, Object>> clientActions;
    private String answer;
    private String questionType;
    private Integer latencyMs;
    private String modelName;
    private List<QaSourceVO> sources;
}
