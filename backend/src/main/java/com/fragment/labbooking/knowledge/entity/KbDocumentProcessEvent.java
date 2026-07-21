package com.fragment.labbooking.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Append-only processing metadata; document content is never copied here. */
@TableName("kb_document_process_event")
@Data
public class KbDocumentProcessEvent {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField("document_id")
    private Long documentId;
    @TableField("doc_version")
    private String docVersion;
    @TableField("trace_id")
    private String traceId;
    @TableField("attempt")
    private Integer attempt;
    @TableField("stage")
    private String stage;
    @TableField("status")
    private String status;
    @TableField("message")
    private String message;
    @TableField("detail_json")
    private String detailJson;
    @TableField("created_at")
    private LocalDateTime createdAt;
}
