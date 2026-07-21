package com.fragment.labbooking.knowledge.vo;

import lombok.Data;

import java.util.List;

@Data
public class QaSourceVO {

    private Long documentId;
    private String chunkId;
    private String chunkUid;
    private Integer chunkIndex;
    private Integer pageNo;
    private String sectionTitle;
    private List<String> titlePath;
    private String docVersion;
    private String contentHash;
    private Double score;
    private Double retrievalScore;
    private Double rerankScore;
    private String rerankProvider;
    private String retrievalSource;
    private String excerpt;
    private String documentTitle;
}
