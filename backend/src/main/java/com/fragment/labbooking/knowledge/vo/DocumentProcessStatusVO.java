package com.fragment.labbooking.knowledge.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DocumentProcessStatusVO {

    private Long id;
    private String status;
    private String docVersion;
    private Integer chunkCount;
    private String parserProvider;
    private String parserVersion;
    private String parseQuality;
    private Integer retryCount;
    private String errorMessage;
    private String processTraceId;
    private LocalDateTime processStartedAt;
    private LocalDateTime processFinishedAt;
}
