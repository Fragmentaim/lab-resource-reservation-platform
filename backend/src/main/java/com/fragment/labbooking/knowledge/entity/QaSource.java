package com.fragment.labbooking.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@TableName(value = "qa_source")
@Data
public class QaSource {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "qa_record_id")
    private Long qaRecordId;

    @TableField(value = "document_id")
    private Long documentId;

    @TableField(value = "chunk_id")
    private Long chunkId;

    @TableField(value = "chunk_uid")
    private String chunkUid;

    @TableField(value = "chunk_index")
    private Integer chunkIndex;

    @TableField(value = "page_no")
    private Integer pageNo;

    @TableField(value = "section_title")
    private String sectionTitle;

    @TableField(value = "title_path")
    private String titlePath;

    @TableField(value = "doc_version")
    private String docVersion;

    @TableField(value = "content_hash")
    private String contentHash;

    @TableField(value = "score")
    private Double score;

    @TableField(value = "retrieval_score")
    private Double retrievalScore;

    @TableField(value = "rerank_score")
    private Double rerankScore;

    @TableField(value = "rerank_provider")
    private String rerankProvider;

    @TableField(value = "retrieval_source")
    private String retrievalSource;

    @TableField(value = "excerpt")
    private String excerpt;

    @TableField(value = "created_at")
    private LocalDateTime createdAt;
}
