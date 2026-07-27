package com.fragment.labbooking.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.common.outbox.MessageOutboxService;
import com.fragment.labbooking.common.outbox.MessageOutboxProperties;
import com.fragment.labbooking.common.util.TruncateUtil;
import com.fragment.labbooking.entity.SysUser;
import com.fragment.labbooking.knowledge.common.constants.DocumentStatusConstants;
import com.fragment.labbooking.knowledge.common.constants.DocumentVisibilityConstants;
import com.fragment.labbooking.knowledge.dto.DocumentPageQueryDTO;
import com.fragment.labbooking.knowledge.dto.DocumentUpdateDTO;
import com.fragment.labbooking.knowledge.entity.KbChunk;
import com.fragment.labbooking.knowledge.entity.KbDocument;
import com.fragment.labbooking.knowledge.entity.KbDocumentAccess;
import com.fragment.labbooking.knowledge.entity.KbDocumentProcessEvent;
import com.fragment.labbooking.knowledge.mapper.KbChunkMapper;
import com.fragment.labbooking.knowledge.mapper.KbDocumentAccessMapper;
import com.fragment.labbooking.knowledge.mapper.KbDocumentMapper;
import com.fragment.labbooking.knowledge.mapper.KbDocumentProcessEventMapper;
import com.fragment.labbooking.knowledge.mq.DocumentProcessProperties;
import com.fragment.labbooking.knowledge.mq.DocumentProcessMessage;
import com.fragment.labbooking.knowledge.service.AiServiceClient;
import com.fragment.labbooking.knowledge.service.KbDocumentService;
import com.fragment.labbooking.knowledge.service.MinioService;
import com.fragment.labbooking.knowledge.vo.DocumentProcessStatusVO;
import com.fragment.labbooking.knowledge.vo.DocumentProcessEventVO;
import com.fragment.labbooking.knowledge.vo.KbDocumentVO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.mapper.SysUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@Slf4j
public class KbDocumentServiceImpl extends ServiceImpl<KbDocumentMapper, KbDocument>
        implements KbDocumentService {

    @Value("${app.knowledge.storage.local-dir:../uploads/knowledge}")
    private String uploadDir;

    @Value("${app.knowledge.storage.type:local}")
    private String uploadStorage;

    @Value("${app.knowledge.storage.minio.bucket:lab-knowledge}")
    private String minioBucket;

    @Value("${app.knowledge.storage.minio.presign-expiry-seconds:900}")
    private int presignExpirySeconds;

    private final AiServiceClient aiServiceClient;
    private final MinioService minioService;
    private final SysUserMapper sysUserMapper;
    private final KbChunkMapper kbChunkMapper;
    private final KbDocumentAccessMapper kbDocumentAccessMapper;
    private final KbDocumentProcessEventMapper processEventMapper;
    private final MessageOutboxService outboxService;
    private final MessageOutboxProperties outboxProperties;
    private final DocumentProcessProperties documentProcessProperties;
    private final ObjectMapper objectMapper;

    public KbDocumentServiceImpl(AiServiceClient aiServiceClient,
                                 MinioService minioService,
                                 SysUserMapper sysUserMapper,
                                 KbChunkMapper kbChunkMapper,
                                 KbDocumentAccessMapper kbDocumentAccessMapper,
                                 KbDocumentProcessEventMapper processEventMapper,
                                 MessageOutboxService outboxService,
                                 MessageOutboxProperties outboxProperties,
                                 DocumentProcessProperties documentProcessProperties,
                                 ObjectMapper objectMapper) {
        this.aiServiceClient = aiServiceClient;
        this.minioService = minioService;
        this.sysUserMapper = sysUserMapper;
        this.kbChunkMapper = kbChunkMapper;
        this.kbDocumentAccessMapper = kbDocumentAccessMapper;
        this.processEventMapper = processEventMapper;
        this.outboxService = outboxService;
        this.outboxProperties = outboxProperties;
        this.documentProcessProperties = documentProcessProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KbDocumentVO uploadDocument(MultipartFile file, String title, String category,
                                        String tags, String visibility, List<Long> allowedUserIds, Long uploaderId) {
        validateFile(file);
        String fileName = normalizeFileName(file.getOriginalFilename());
        String fileType = extractFileType(fileName);
        if (!StringUtils.hasText(fileType)) {
            throw new BusinessException("暂不支持该文件类型；请上传 PDF、DOCX、XLSX、TXT、Markdown 或常见图片");
        }
        String objectName = buildObjectName(fileName);
        String traceId = UUID.randomUUID().toString();
        boolean stored = false;

        try {
            storeOriginalFile(objectName, file);
            stored = true;

            LocalDateTime now = LocalDateTime.now();
            KbDocument doc = new KbDocument();
            doc.setTitle(StringUtils.hasText(title) ? title : fileName);
            doc.setFileName(fileName);
            doc.setFileUrl(objectName);
            doc.setFileSize(file.getSize());
            doc.setFileType(fileType);
            doc.setCategory(category);
            doc.setTags(tags);
            doc.setStatus(DocumentStatusConstants.PENDING);
            doc.setDocVersion("v1");
            doc.setChunkCount(0);
            doc.setProcessTraceId(traceId);
            doc.setRetryCount(0);
            doc.setUploaderId(uploaderId);
            doc.setVisibility(normalizeVisibility(visibility));
            doc.setCreatedAt(now);
            doc.setUpdatedAt(now);
            save(doc);
            replaceAccessRules(doc, allowedUserIds);
            recordProcessEvent(doc, 0, "QUEUED", "PENDING", "文档已上传，等待异步处理", Map.of());

            enqueueDocumentProcess(doc.getId(), traceId);
            return toVO(doc);
        } catch (RuntimeException exception) {
            if (stored) {
                deleteOriginalFileQuietly(objectName);
            }
            throw exception;
        } catch (Exception exception) {
            if (stored) {
                deleteOriginalFileQuietly(objectName);
            }
            throw new BusinessException("文件上传或异步处理入队失败: " + exception.getMessage());
        }
    }

    @Override
    public Page<KbDocumentVO> pageDocuments(DocumentPageQueryDTO queryDTO, LoginUser actor) {
        DocumentPageQueryDTO q = queryDTO == null ? new DocumentPageQueryDTO() : queryDTO;
        long pageNum = q.getPageNum() == null ? 1L : q.getPageNum();
        long pageSize = q.getPageSize() == null ? 10L : q.getPageSize();

        Page<KbDocument> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<KbDocument> wrapper = new LambdaQueryWrapper<KbDocument>()
                .eq(StringUtils.hasText(q.getCategory()), KbDocument::getCategory, q.getCategory())
                .eq(StringUtils.hasText(q.getStatus()), KbDocument::getStatus, q.getStatus())
                .and(StringUtils.hasText(q.getKeyword()), w -> w
                        .like(KbDocument::getTitle, q.getKeyword())
                        .or()
                        .like(KbDocument::getFileName, q.getKeyword()));
        applyAccessFilter(wrapper, actor);
        wrapper
                .orderByDesc(KbDocument::getCreatedAt);

        Page<KbDocument> docPage = this.page(page, wrapper);
        List<KbDocumentVO> records = docPage.getRecords().stream()
                .map(this::toVO)
                .collect(Collectors.toList());

        Page<KbDocumentVO> voPage = new Page<>(docPage.getCurrent(), docPage.getSize(), docPage.getTotal());
        voPage.setRecords(records);
        return voPage;
    }

    @Override
    public KbDocumentVO getDocumentDetail(Long id, LoginUser actor) {
        KbDocument doc = getById(id);
        if (doc == null) {
            throw new BusinessException("文档不存在");
        }
        assertCanRead(doc, actor);
        return toVO(doc);
    }

    @Override
    public DocumentProcessStatusVO getDocumentStatus(Long id, LoginUser actor) {
        KbDocument doc = getById(id);
        if (doc == null) {
            throw new BusinessException("文档不存在");
        }
        assertCanRead(doc, actor);
        DocumentProcessStatusVO vo = new DocumentProcessStatusVO();
        BeanUtils.copyProperties(doc, vo);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDocument(DocumentUpdateDTO dto) {
        KbDocument doc = getById(dto.getId());
        if (doc == null) {
            throw new BusinessException("文档不存在");
        }
        doc.setTitle(dto.getTitle());
        doc.setCategory(dto.getCategory());
        doc.setTags(dto.getTags());
        if (StringUtils.hasText(dto.getVisibility())) {
            doc.setVisibility(normalizeVisibility(dto.getVisibility()));
            replaceAccessRules(doc, dto.getAllowedUserIds());
        }
        doc.setUpdatedAt(LocalDateTime.now());
        updateById(doc);
    }

    @Override
    public Map<Long, String> listAccessibleDocumentVersions(LoginUser actor) {
        LambdaQueryWrapper<KbDocument> wrapper = new LambdaQueryWrapper<KbDocument>()
                .select(KbDocument::getId, KbDocument::getDocVersion)
                .gt(KbDocument::getChunkCount, 0);
        applyAccessFilter(wrapper, actor);
        return list(wrapper).stream().collect(Collectors.toMap(
                KbDocument::getId,
                document -> StringUtils.hasText(document.getDocVersion()) ? document.getDocVersion() : "v1",
                (left, right) -> left,
                LinkedHashMap::new
        ));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteDocument(Long id) {
        KbDocument doc = getById(id);
        if (doc == null) {
            throw new BusinessException("文档不存在");
        }
        aiServiceClient.deleteDocumentVectors(id);
        deleteChunks(id);
        deleteAccessRules(id);
        removeById(id);
        deleteOriginalFileQuietly(doc.getFileUrl());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reprocessDocument(Long id) {
        KbDocument doc = getById(id);
        if (doc == null) {
            throw new BusinessException("文档不存在");
        }

        String traceId = UUID.randomUUID().toString();
        doc.setStatus(DocumentStatusConstants.PENDING);
        doc.setErrorMessage(null);
        doc.setProcessTraceId(traceId);
        doc.setRetryCount(0);
        doc.setProcessStartedAt(null);
        doc.setProcessFinishedAt(null);
        doc.setUpdatedAt(LocalDateTime.now());
        updateById(doc);
        recordProcessEvent(doc, 0, "QUEUED", "PENDING", "管理员请求重新处理，等待异步 worker", Map.of());
        enqueueDocumentProcess(id, traceId);
    }

    @Override
    public void processDocumentMessage(Long documentId, String traceId) {
        if (documentId == null || !StringUtils.hasText(traceId)) {
            log.warn("Ignored malformed knowledge document process message. documentId={}, traceId={}",
                    documentId, traceId);
            return;
        }

        KbDocument doc = getById(documentId);
        if (doc == null) {
            log.warn("Ignored document process message because document {} does not exist", documentId);
            return;
        }
        if (DocumentStatusConstants.READY.equals(doc.getStatus())) {
            log.info("Ignored document process message because document {} is already READY", documentId);
            return;
        }
        if (StringUtils.hasText(doc.getProcessTraceId()) && !doc.getProcessTraceId().equals(traceId)) {
            log.info("Ignored stale document process message for id={}, messageTraceId={}, currentTraceId={}",
                    documentId, traceId, doc.getProcessTraceId());
            return;
        }

        int attempt = (doc.getRetryCount() == null ? 0 : doc.getRetryCount()) + 1;
        if (!claimProcessing(documentId, traceId, attempt)) {
            log.info("Skipped document process message because document was not claimable. id={}, traceId={}",
                    documentId, traceId);
            return;
        }

        try {
            KbDocument claimed = getById(documentId);
            if (claimed == null) {
                return;
            }
            String activeVersion = hasActiveVersion(claimed) ? claimed.getDocVersion() : null;
            String buildVersion = activeVersion == null
                    ? (StringUtils.hasText(claimed.getDocVersion()) ? claimed.getDocVersion() : "v1")
                    : nextDocVersion(activeVersion);

            recordProcessEvent(claimed, attempt, "CLAIMED", "RUNNING", "处理任务已被 worker 获取", Map.of());
            recordProcessEvent(claimed, attempt, "VERSION_PREPARE", "RUNNING", "清理目标版本的残留索引",
                    Map.of("build_version", buildVersion));
            aiServiceClient.deleteDocumentVersion(documentId, buildVersion);
            deleteChunks(documentId, buildVersion);
            recordProcessEvent(claimed, attempt, "PARSING", "RUNNING", "调用 AI 服务解析、分块、向量化",
                    Map.of("file_type", claimed.getFileType(), "build_version", buildVersion));
            AiServiceClient.ProcessResult result = processStoredFile(claimed, buildVersion);
            if (result == null || result.chunkCount() <= 0 || result.chunks() == null || result.chunks().isEmpty()) {
                throw new BusinessException("AI-service 未生成可用文档分块");
            }
            if (!buildVersion.equals(result.docVersion())) {
                throw new BusinessException("AI-service 返回了错误的文档版本");
            }
            recordProcessEvent(claimed, attempt, "PERSISTING_CHUNKS", "RUNNING", "持久化文档分块和来源元数据",
                    Map.of("chunk_count", result.chunkCount(), "build_version", buildVersion));
            saveChunks(documentId, result);

            if (!activateDocumentVersion(documentId, traceId, buildVersion, result)) {
                throw new BusinessException("文档版本切换失败，处理任务已过期");
            }
            KbDocument latest = getById(documentId);
            recordProcessEvent(latest, attempt, "COMPLETED", "SUCCEEDED", "文档已完成处理，可以参与问答",
                    completionDetail(latest, result));
            log.info("Document processing completed: id={}, docVersion={}, chunks={}",
                    documentId, buildVersion, result.chunkCount());
            cleanupRetiredVersion(documentId, activeVersion, attempt, latest);
        } catch (Exception e) {
            boolean finalAttempt = attempt >= Math.max(1, documentProcessProperties.getMaxRetryAttempts());
            markProcessingFailed(documentId, traceId, finalAttempt, e.getMessage());
            KbDocument failed = getById(documentId);
            if (failed != null && traceId.equals(failed.getProcessTraceId())) {
                recordProcessEvent(failed, attempt, finalAttempt ? "FAILED" : "RETRY_PENDING",
                        finalAttempt ? "FAILED" : "PENDING",
                        finalAttempt ? "处理失败，已达到最大重试次数" : "本次处理失败，等待消息队列重试",
                        Map.of("error", org.springframework.util.StringUtils.hasText(e.getMessage()) ? TruncateUtil.truncate(e.getMessage(), 512) : "未知错误"));
            }
            log.error("Document processing failed: id={}, attempt={}, finalAttempt={}, error={}",
                    documentId, attempt, finalAttempt, e.getMessage());
            if (!finalAttempt) {
                throw e instanceof RuntimeException runtimeException
                        ? runtimeException
                        : new BusinessException(e.getMessage());
            }
        }
    }

    private boolean claimProcessing(Long documentId, String traceId, int attempt) {
        LocalDateTime now = LocalDateTime.now();
        int updated = baseMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                .eq(KbDocument::getId, documentId)
                .eq(KbDocument::getProcessTraceId, traceId)
                .eq(KbDocument::getStatus, DocumentStatusConstants.PENDING)
                .set(KbDocument::getStatus, DocumentStatusConstants.PROCESSING)
                .set(KbDocument::getRetryCount, attempt)
                .set(KbDocument::getProcessStartedAt, now)
                .set(KbDocument::getProcessFinishedAt, null)
                .set(KbDocument::getErrorMessage, null)
                .set(KbDocument::getUpdatedAt, now));
        return updated > 0;
    }

    private void enqueueDocumentProcess(Long documentId, String traceId) {
        // 本地无 MQ 时使用异步任务；生产环境走 Outbox 消息链路。
        if (!outboxProperties.isEnabled() || !documentProcessProperties.isEnabled()) {
            Runnable processTask = () -> processDocumentMessage(documentId, traceId);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        CompletableFuture.runAsync(processTask);
                    }
                });
            } else {
                CompletableFuture.runAsync(processTask);
            }
            return;
        }
        outboxService.enqueue(
                "KB_DOCUMENT",
                documentId + ":" + traceId,
                documentProcessProperties.getEventType(),
                documentProcessProperties.getTopic(),
                documentProcessProperties.getTag(),
                String.valueOf(documentId),
                LocalDateTime.now(),
                new DocumentProcessMessage(documentId, traceId)
        );
    }

    private void markProcessingFailed(Long documentId, String traceId, boolean finalAttempt, String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        KbDocument failed = getById(documentId);
        if (failed == null || !traceId.equals(failed.getProcessTraceId())) {
            return;
        }
        boolean activeVersionAvailable = hasActiveVersion(failed);
        baseMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                .eq(KbDocument::getId, documentId)
                .eq(KbDocument::getProcessTraceId, traceId)
                .eq(KbDocument::getStatus, DocumentStatusConstants.PROCESSING)
                .set(KbDocument::getStatus, finalAttempt
                        ? (activeVersionAvailable ? DocumentStatusConstants.READY : DocumentStatusConstants.FAILED)
                        : DocumentStatusConstants.PENDING)
                .set(KbDocument::getErrorMessage,
                        TruncateUtil.truncate("文档异步处理失败: " + errorMessage, 512))
                .set(KbDocument::getProcessFinishedAt, finalAttempt ? now : null)
                .set(KbDocument::getUpdatedAt, now));
    }

    private AiServiceClient.ProcessResult processStoredFile(KbDocument doc, String docVersion) {
        if (useMinio()) {
            String fileUrl = minioService.presignedGetUrl(minioBucket, doc.getFileUrl(), presignExpirySeconds);
            return aiServiceClient.processDocumentByUrl(
                    doc.getId(),
                    fileUrl,
                    doc.getFileName(),
                    doc.getFileType(),
                    docVersion
            );
        }

        Path target = resolveLocalPath(doc.getFileUrl());
        if (!Files.exists(target)) {
            throw new BusinessException("本地文件不存在，无法重新处理");
        }
        return aiServiceClient.processDocument(
                doc.getId(),
                target.toFile(),
                doc.getFileName(),
                doc.getFileType(),
                docVersion
        );
    }

    private KbDocumentVO toVO(KbDocument doc) {
        KbDocumentVO vo = new KbDocumentVO();
        BeanUtils.copyProperties(doc, vo);

        if (doc.getUploaderId() != null) {
            SysUser user = sysUserMapper.selectById(doc.getUploaderId());
            if (user != null) {
                vo.setUploaderName(user.getNickname());
            }
        }
        return vo;
    }

    @Override
    public List<DocumentProcessEventVO> listProcessEvents(Long id, LoginUser actor) {
        KbDocument doc = getById(id);
        if (doc == null) {
            throw new BusinessException("文档不存在");
        }
        assertCanRead(doc, actor);
        return processEventMapper.selectList(new LambdaQueryWrapper<KbDocumentProcessEvent>()
                        .eq(KbDocumentProcessEvent::getDocumentId, id)
                        .orderByAsc(KbDocumentProcessEvent::getCreatedAt)
                        .orderByAsc(KbDocumentProcessEvent::getId))
                .stream()
                .map(this::toProcessEventVO)
                .toList();
    }

    private Map<String, Object> completionDetail(KbDocument document, AiServiceClient.ProcessResult result) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("chunk_count", result.chunkCount());
        detail.put("parser_provider", document.getParserProvider());
        detail.put("parser_version", document.getParserVersion());
        detail.put("document_status", document.getStatus());
        return detail;
    }

    private void recordProcessEvent(KbDocument document, int attempt, String stage, String status,
                                    String message, Map<String, Object> detail) {
        if (document == null || document.getId() == null || !StringUtils.hasText(document.getProcessTraceId())) {
            return;
        }
        try {
            KbDocumentProcessEvent event = new KbDocumentProcessEvent();
            event.setDocumentId(document.getId());
            event.setDocVersion(StringUtils.hasText(document.getDocVersion()) ? document.getDocVersion() : "v1");
            event.setTraceId(document.getProcessTraceId());
            event.setAttempt(Math.max(0, attempt));
            event.setStage(org.springframework.util.StringUtils.hasText(stage) ? TruncateUtil.truncate(stage, 32) : "-");
            event.setStatus(org.springframework.util.StringUtils.hasText(status) ? TruncateUtil.truncate(status, 16) : "-");
            event.setMessage(org.springframework.util.StringUtils.hasText(message) ? TruncateUtil.truncate(message, 512) : "-");
            event.setDetailJson(detail == null || detail.isEmpty() ? null : objectMapper.writeValueAsString(detail));
            event.setCreatedAt(LocalDateTime.now());
            processEventMapper.insert(event);
        } catch (Exception exception) {
            // An unavailable audit table must not prevent the document from becoming searchable.
            log.warn("Unable to persist document process audit. documentId={}, stage={}, error={}",
                    document.getId(), stage, exception.getMessage());
        }
    }

    private DocumentProcessEventVO toProcessEventVO(KbDocumentProcessEvent event) {
        DocumentProcessEventVO vo = new DocumentProcessEventVO();
        BeanUtils.copyProperties(event, vo);
        vo.setDetail(parseAuditDetail(event.getDetailJson()));
        return vo;
    }

    private Map<String, Object> parseAuditDetail(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception exception) {
            return Map.of("unparsed_detail", json);
        }
    }

    private void applyAccessFilter(LambdaQueryWrapper<KbDocument> wrapper, LoginUser actor) {
        if (actor == null || actor.getId() == null) {
            throw new BusinessException(401, "未登录或登录已失效");
        }
        if (actor.isAdmin()) {
            return;
        }
        Long userId = actor.getId();
        List<Long> assignedDocumentIds = assignedDocumentIds(userId);
        wrapper.and(w -> {
            w.eq(KbDocument::getVisibility, DocumentVisibilityConstants.PUBLIC)
                    .or()
                    .isNull(KbDocument::getVisibility)
                    .or()
                    .eq(KbDocument::getUploaderId, userId);
            if (!assignedDocumentIds.isEmpty()) {
                w.or().in(KbDocument::getId, assignedDocumentIds);
            }
        });
    }

    private void assertCanRead(KbDocument doc, LoginUser actor) {
        if (actor == null || actor.getId() == null) {
            throw new BusinessException(401, "未登录或登录已失效");
        }
        if (actor.isAdmin() || actor.getId().equals(doc.getUploaderId()) || isPublic(doc)) {
            return;
        }
        if (DocumentVisibilityConstants.SPECIFIED_USERS.equals(doc.getVisibility())) {
            Long count = kbDocumentAccessMapper.selectCount(new LambdaQueryWrapper<KbDocumentAccess>()
                    .eq(KbDocumentAccess::getDocumentId, doc.getId())
                    .eq(KbDocumentAccess::getUserId, actor.getId()));
            if (count != null && count > 0) {
                return;
            }
        }
        throw new BusinessException(403, "无权访问该知识库文档");
    }

    private boolean isPublic(KbDocument doc) {
        return !StringUtils.hasText(doc.getVisibility())
                || DocumentVisibilityConstants.PUBLIC.equals(doc.getVisibility());
    }

    private List<Long> assignedDocumentIds(Long userId) {
        return kbDocumentAccessMapper.selectList(new LambdaQueryWrapper<KbDocumentAccess>()
                        .select(KbDocumentAccess::getDocumentId)
                        .eq(KbDocumentAccess::getUserId, userId))
                .stream()
                .map(KbDocumentAccess::getDocumentId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    private String normalizeVisibility(String visibility) {
        String normalized = StringUtils.hasText(visibility)
                ? visibility.trim().toUpperCase(Locale.ROOT)
                : DocumentVisibilityConstants.PUBLIC;
        if (!DocumentVisibilityConstants.isSupported(normalized)) {
            throw new BusinessException("不支持的文档可见范围");
        }
        return normalized;
    }

    private void replaceAccessRules(KbDocument doc, List<Long> allowedUserIds) {
        List<Long> normalizedUserIds = normalizeUserIds(allowedUserIds);
        if (DocumentVisibilityConstants.SPECIFIED_USERS.equals(doc.getVisibility())
                && normalizedUserIds.isEmpty()) {
            throw new BusinessException("指定用户可见的文档至少需要一名授权用户");
        }
        if (!normalizedUserIds.isEmpty()) {
            long existingUsers = sysUserMapper.selectBatchIds(normalizedUserIds).stream()
                    .map(SysUser::getId)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .count();
            if (existingUsers != normalizedUserIds.size()) {
                throw new BusinessException("存在无效的授权用户");
            }
        }

        deleteAccessRules(doc.getId());
        if (!DocumentVisibilityConstants.SPECIFIED_USERS.equals(doc.getVisibility())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (Long userId : normalizedUserIds) {
            KbDocumentAccess access = new KbDocumentAccess();
            access.setDocumentId(doc.getId());
            access.setUserId(userId);
            access.setCreatedAt(now);
            kbDocumentAccessMapper.insert(access);
        }
    }

    private List<Long> normalizeUserIds(List<Long> allowedUserIds) {
        if (allowedUserIds == null || allowedUserIds.isEmpty()) {
            return List.of();
        }
        Set<Long> uniqueUserIds = new LinkedHashSet<>();
        for (Long userId : allowedUserIds) {
            if (userId != null && userId > 0) {
                uniqueUserIds.add(userId);
            }
        }
        return new ArrayList<>(uniqueUserIds);
    }

    private void deleteAccessRules(Long documentId) {
        kbDocumentAccessMapper.delete(new LambdaQueryWrapper<KbDocumentAccess>()
                .eq(KbDocumentAccess::getDocumentId, documentId));
    }

    private void deleteChunks(Long documentId) {
        kbChunkMapper.delete(new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getDocumentId, documentId));
    }

    private void deleteChunks(Long documentId, String docVersion) {
        kbChunkMapper.delete(new LambdaQueryWrapper<KbChunk>()
                .eq(KbChunk::getDocumentId, documentId)
                .eq(KbChunk::getDocVersion, docVersion));
    }

    private void saveChunks(Long documentId, AiServiceClient.ProcessResult result) {
        if (result == null || result.chunks() == null || result.chunks().isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        String resolvedDocVersion = StringUtils.hasText(result.docVersion()) ? result.docVersion() : "v1";
        for (int i = 0; i < result.chunks().size(); i++) {
            AiServiceClient.ChunkResult chunk = result.chunks().get(i);
            KbChunk entity = new KbChunk();
            entity.setDocumentId(documentId);
            entity.setChunkUid(StringUtils.hasText(chunk.chunkId())
                    ? chunk.chunkId()
                    : "doc-" + documentId + "-" + resolvedDocVersion + "-chunk-" + String.format("%04d", i));
            entity.setDocVersion(resolvedDocVersion);
            entity.setChunkIndex(chunk.chunkIndex() == null ? i : chunk.chunkIndex());
            entity.setContent(chunk.content() == null ? "" : chunk.content());
            entity.setTokenCount(chunk.tokenCount() == null ? 0 : chunk.tokenCount());
            entity.setPageNo(chunk.pageNo());
            entity.setSectionTitle(chunk.sectionTitle());
            entity.setTitlePath(chunk.titlePath() == null ? "" : String.join(" > ", chunk.titlePath()));
            entity.setContentHash(chunk.contentHash());
            entity.setCharStart(chunk.charStart());
            entity.setCharEnd(chunk.charEnd());
            entity.setVectorId(chunk.vectorId());
            entity.setCreatedAt(now);
            kbChunkMapper.insert(entity);
        }
    }

    private String nextDocVersion(String currentVersion) {
        if (!StringUtils.hasText(currentVersion)) {
            return "v1";
        }
        String trimmed = currentVersion.trim();
        if (trimmed.matches("v\\d+")) {
            int current = Integer.parseInt(trimmed.substring(1));
            return "v" + (current + 1);
        }
        return "v" + System.currentTimeMillis();
    }

    private boolean hasActiveVersion(KbDocument document) {
        return document != null
                && document.getChunkCount() != null
                && document.getChunkCount() > 0
                && StringUtils.hasText(document.getDocVersion());
    }

    private boolean activateDocumentVersion(Long documentId, String traceId, String buildVersion,
                                            AiServiceClient.ProcessResult result) {
        LocalDateTime now = LocalDateTime.now();
        AiServiceClient.ParseQuality quality = result.parseQuality();
        int updated = baseMapper.update(null, new LambdaUpdateWrapper<KbDocument>()
                .eq(KbDocument::getId, documentId)
                .eq(KbDocument::getProcessTraceId, traceId)
                .eq(KbDocument::getStatus, DocumentStatusConstants.PROCESSING)
                .set(KbDocument::getDocVersion, buildVersion)
                .set(KbDocument::getChunkCount, result.chunkCount())
                .set(KbDocument::getParserProvider, quality == null ? null : quality.provider())
                .set(KbDocument::getParserVersion, quality == null ? null : quality.providerVersion())
                .set(KbDocument::getParseQuality, quality == null ? null : quality.reportJson())
                .set(KbDocument::getStatus, DocumentStatusConstants.READY)
                .set(KbDocument::getErrorMessage, null)
                .set(KbDocument::getProcessFinishedAt, now)
                .set(KbDocument::getUpdatedAt, now));
        return updated == 1;
    }

    private void cleanupRetiredVersion(Long documentId, String retiredVersion, int attempt,
                                       KbDocument document) {
        if (!StringUtils.hasText(retiredVersion)
                || retiredVersion.equals(document == null ? null : document.getDocVersion())) {
            return;
        }
        try {
            aiServiceClient.deleteDocumentVersion(documentId, retiredVersion);
            deleteChunks(documentId, retiredVersion);
            recordProcessEvent(document, attempt, "RETIRED_VERSION_CLEANUP", "SUCCEEDED",
                    "旧文档版本已清理", Map.of("retired_version", retiredVersion));
        } catch (Exception cleanupError) {
            String cleanupMessage = StringUtils.hasText(cleanupError.getMessage())
                    ? cleanupError.getMessage()
                    : cleanupError.getClass().getSimpleName();
            log.warn("Document version activated but retired version cleanup failed. documentId={}, version={}, error={}",
                    documentId, retiredVersion, cleanupMessage);
            recordProcessEvent(document, attempt, "RETIRED_VERSION_CLEANUP", "WARNING",
                    "新版本已生效，旧版本清理失败", Map.of(
                            "retired_version", retiredVersion,
                            "error", TruncateUtil.truncate(cleanupMessage, 256)));
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
    }

    private void storeOriginalFile(String objectName, MultipartFile file) {
        if (useMinio()) {
            minioService.uploadFile(minioBucket, objectName, file);
            return;
        }

        try (InputStream inputStream = file.getInputStream()) {
            Path target = resolveLocalPath(objectName);
            Files.createDirectories(target.getParent());
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException("保存本地文件失败: " + e.getMessage());
        }
    }

    private void deleteOriginalFileQuietly(String objectName) {
        if (!StringUtils.hasText(objectName)) {
            return;
        }
        if (useMinio()) {
            minioService.deleteFile(minioBucket, objectName);
            return;
        }

        try {
            Files.deleteIfExists(resolveLocalPath(objectName));
        } catch (IOException e) {
            log.warn("Local file delete failed: {}", e.getMessage());
        }
    }

    private Path resolveLocalPath(String objectName) {
        Path base = Path.of(uploadDir).toAbsolutePath().normalize();
        Path target = base.resolve(objectName).normalize();
        if (!target.startsWith(base)) {
            throw new BusinessException("非法文件路径");
        }
        return target;
    }

    private boolean useMinio() {
        return "minio".equalsIgnoreCase(uploadStorage);
    }

    private String buildObjectName(String fileName) {
        return UUID.randomUUID() + "_" + normalizeFileName(fileName);
    }

    private String normalizeFileName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return "document.txt";
        }
        String normalized = fileName.replace("\\", "/");
        int slashIndex = normalized.lastIndexOf('/');
        String baseName = slashIndex >= 0 ? normalized.substring(slashIndex + 1) : normalized;
        baseName = baseName.replace("\r", "_").replace("\n", "_").trim();
        return StringUtils.hasText(baseName) ? baseName : "document.txt";
    }

    private String extractFileType(String fileName) {
        if (fileName == null) {
            return null;
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return "PDF";
        }
        if (lower.endsWith(".docx")) {
            return "DOCX";
        }
        if (lower.endsWith(".xlsx")) {
            return "XLSX";
        }
        if (lower.endsWith(".png")) {
            return "PNG";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "JPG";
        }
        if (lower.endsWith(".bmp")) {
            return "BMP";
        }
        if (lower.endsWith(".tif") || lower.endsWith(".tiff")) {
            return "TIFF";
        }
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return "MD";
        }
        if (lower.endsWith(".txt")) {
            return "TXT";
        }
        return null;
    }
}
