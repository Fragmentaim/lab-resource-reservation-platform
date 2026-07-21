package com.fragment.labbooking.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@TableName(value = "kb_chunk")
@Data
public class KbChunk {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "document_id")
    private Long documentId;

    @TableField(value = "chunk_uid")
    private String chunkUid;

    @TableField(value = "doc_version")
    private String docVersion;

    @TableField(value = "chunk_index")
    private Integer chunkIndex;

    @TableField(value = "content")
    private String content;

    @TableField(value = "token_count")
    private Integer tokenCount;

    @TableField(value = "page_no")
    private Integer pageNo;

    @TableField(value = "section_title")
    private String sectionTitle;

    @TableField(value = "title_path")
    private String titlePath;

    @TableField(value = "content_hash")
    private String contentHash;

    @TableField(value = "char_start")
    private Integer charStart;

    @TableField(value = "char_end")
    private Integer charEnd;

    @TableField(value = "vector_id")
    private String vectorId;

    @TableField(value = "created_at")
    private LocalDateTime createdAt;
}
