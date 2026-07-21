package com.fragment.labbooking.knowledge.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class KbDocumentVO {

    private Long id;
    private String title;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private String category;
    private String tags;
    private String status;
    private String docVersion;
    private Integer chunkCount;
    private String parserProvider;
    private String parserVersion;
    private String parseQuality;
    private String errorMessage;
    private String processTraceId;
    private Integer retryCount;
    private String visibility;
    private LocalDateTime processStartedAt;
    private LocalDateTime processFinishedAt;
    private String uploaderName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
