package com.fragment.labbooking.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@TableName(value = "kb_document")
@Data
public class KbDocument {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "title")
    private String title;

    @TableField(value = "file_name")
    private String fileName;

    @TableField(value = "file_url")
    private String fileUrl;

    @TableField(value = "file_size")
    private Long fileSize;

    @TableField(value = "file_type")
    private String fileType;

    @TableField(value = "category")
    private String category;

    @TableField(value = "tags")
    private String tags;

    @TableField(value = "status")
    private String status;

    @TableField(value = "doc_version")
    private String docVersion;

    @TableField(value = "chunk_count")
    private Integer chunkCount;

    @TableField(value = "parser_provider")
    private String parserProvider;

    @TableField(value = "parser_version")
    private String parserVersion;

    @TableField(value = "parse_quality")
    private String parseQuality;

    @TableField(value = "error_message")
    private String errorMessage;

    @TableField(value = "process_trace_id")
    private String processTraceId;

    @TableField(value = "retry_count")
    private Integer retryCount;

    @TableField(value = "process_started_at")
    private LocalDateTime processStartedAt;

    @TableField(value = "process_finished_at")
    private LocalDateTime processFinishedAt;

    @TableField(value = "uploader_id")
    private Long uploaderId;

    @TableField(value = "visibility")
    private String visibility;

    @TableField(value = "created_at")
    private LocalDateTime createdAt;

    @TableField(value = "updated_at")
    private LocalDateTime updatedAt;
}
