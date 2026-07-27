package com.fragment.labbooking.knowledge.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.knowledge.service.AiServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class AiServiceClientImpl implements AiServiceClient {

    private final RestClient restClient;

    public AiServiceClientImpl(RestClient aiServiceRestClient) {
        this.restClient = aiServiceRestClient;
    }

    @Override
    public ProcessResult processDocument(Long documentId, MultipartFile file, String fileType) {
        try {
            return processDocument(documentId, file.getBytes(), file.getOriginalFilename(), fileType);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to process document {} via AI service: {}", documentId, e.getMessage());
            throw new BusinessException("文档处理失败: " + e.getMessage());
        }
    }

    @Override
    public ProcessResult processDocument(Long documentId, File file, String fileName, String fileType) {
        return processDocument(documentId, file, fileName, fileType, "v1");
    }

    @Override
    public ProcessResult processDocument(Long documentId, File file, String fileName, String fileType, String docVersion) {
        try {
            return doProcessDocumentMultipart(documentId, new FileSystemResource(file), fileType, docVersion);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to reprocess document {} via AI service: {}", documentId, e.getMessage());
            throw new BusinessException("文档重处理失败: " + e.getMessage());
        }
    }

    @Override
    public ProcessResult processDocument(Long documentId, byte[] bytes, String fileName, String fileType) {
        return processDocument(documentId, bytes, fileName, fileType, "v1");
    }

    @Override
    public ProcessResult processDocument(Long documentId, byte[] bytes, String fileName, String fileType, String docVersion) {
        try {
            ByteArrayResource resource = new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return fileName;
                }
            };
            return doProcessDocumentMultipart(documentId, resource, fileType, docVersion);
        } catch (Exception e) {
            log.error("Failed to process document {} via AI service: {}", documentId, e.getMessage());
            throw new BusinessException("文档处理失败: " + e.getMessage());
        }
    }

    @Override
    public ProcessResult processDocumentByUrl(Long documentId, String fileUrl, String fileName, String fileType,
                                              String docVersion) {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("document_id", documentId);
            request.put("file_url", fileUrl);
            request.put("file_name", fileName);
            request.put("file_type", fileType);
            request.put("doc_version", docVersion);

            ProcessResult response = restClient.post()
                    .uri("/api/v1/ai/documents/process-by-url")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(ProcessResult.class);
            return normalizeProcessResult(requireBody(response, "AI-service 返回空处理结果"), docVersion);
        } catch (Exception e) {
            log.error("Failed to process document {} by URL via AI service: {}", documentId, e.getMessage());
            throw new BusinessException("文档 URL 处理失败: " + e.getMessage());
        }
    }

    private ProcessResult doProcessDocumentMultipart(Long documentId, Object fileResource,
                                                     String fileType, String docVersion) {
        MultiValueMap<String, Object> formData = new LinkedMultiValueMap<>();
        formData.add("document_id", documentId.toString());
        formData.add("file_type", fileType);
        formData.add("doc_version", docVersion);
        formData.add("file", fileResource);

        ProcessResult response = restClient.post()
                .uri("/api/v1/ai/documents/process")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(formData)
                .retrieve()
                .body(ProcessResult.class);

        return normalizeProcessResult(requireBody(response, "AI-service 返回空处理结果"), docVersion);
    }

    @Override
    public KnowledgeSearchResult retrieveKnowledge(String question, Map<Long, String> documentVersions) {
        try {
            Map<Long, String> scopes = documentVersions == null ? Collections.emptyMap() : documentVersions;
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("question", question);
            request.put("document_ids", scopes.keySet());
            request.put("document_versions", scopes);
            KnowledgeSearchResult response = restClient.post()
                    .uri("/api/v1/ai/qa/retrieve")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(KnowledgeSearchResult.class);
            KnowledgeSearchResult result = requireBody(response, "AI-service 返回空检索结果");
            return StringUtils.hasText(result.query()) ? result
                    : new KnowledgeSearchResult(question, result.candidates());
        } catch (Exception e) {
            log.error("Failed to retrieve knowledge candidates via AI service: {}", e.getMessage());
            throw new BusinessException("知识库候选检索失败: " + e.getMessage());
        }
    }

    @Override
    public List<KnowledgeChunk> openKnowledgeChunks(List<String> chunkUids, Map<Long, String> documentVersions) {
        try {
            Map<Long, String> scopes = documentVersions == null ? Collections.emptyMap() : documentVersions;
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("chunk_uids", chunkUids == null ? Collections.emptyList() : chunkUids);
            request.put("document_ids", scopes.keySet());
            request.put("document_versions", scopes);
            KnowledgeChunksResponse response = restClient.post()
                    .uri("/api/v1/ai/qa/chunks/open")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(KnowledgeChunksResponse.class);
            return requireBody(response, "AI-service 返回空正文结果").chunks();
        } catch (Exception e) {
            log.error("Failed to open knowledge chunks via AI service: {}", e.getMessage());
            throw new BusinessException("知识库正文读取失败: " + e.getMessage());
        }
    }

    @Override
    public SummaryResult summarizeSession(String existingSummary, List<ChatMessage> newTurns) {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("existing_summary", existingSummary);
            request.put("new_turns", newTurns == null ? Collections.emptyList() : newTurns);

            SummaryResult response = restClient.post()
                    .uri("/api/v1/ai/qa/sessions/summarize")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(SummaryResult.class);
            return requireBody(response, "AI-service 返回空摘要结果");
        } catch (Exception e) {
            log.warn("Failed to summarize session via AI service: {}", e.getMessage());
            throw new BusinessException("会话摘要更新失败: " + e.getMessage());
        }
    }

    @Override
    public int deleteDocumentVectors(Long documentId) {
        try {
            DeleteResponse response = restClient.delete()
                    .uri("/api/v1/ai/documents/{id}/vectors", documentId)
                    .retrieve()
                    .body(DeleteResponse.class);
            return requireBody(response, "AI-service 返回空删除结果").deletedCount();
        } catch (Exception e) {
            log.error("Failed to delete vectors for document {}: {}", documentId, e.getMessage());
            throw new BusinessException("删除文档索引失败: " + e.getMessage());
        }
    }

    @Override
    public int deleteDocumentVersion(Long documentId, String docVersion) {
        try {
            DeleteResponse response = restClient.delete()
                    .uri("/api/v1/ai/documents/{id}/versions/{version}", documentId, docVersion)
                    .retrieve()
                    .body(DeleteResponse.class);
            return requireBody(response, "AI-service 返回空删除结果").deletedCount();
        } catch (Exception e) {
            log.error("Failed to delete document version. documentId={}, version={}, error={}",
                    documentId, docVersion, e.getMessage());
            throw new BusinessException("删除文档版本索引失败: " + e.getMessage());
        }
    }

    @Override
    public boolean checkHealth() {
        try {
            HealthResponse response = restClient.get()
                    .uri("/api/v1/ai/health")
                    .retrieve()
                    .body(HealthResponse.class);
            return response != null && "ok".equals(response.status());
        } catch (Exception e) {
            return false;
        }
    }

    private ProcessResult normalizeProcessResult(ProcessResult result, String fallbackDocVersion) {
        String status = StringUtils.hasText(result.status()) ? result.status() : "FAILED";
        String docVersion = StringUtils.hasText(result.docVersion()) ? result.docVersion() : fallbackDocVersion;
        return new ProcessResult(result.chunkCount(), status, docVersion, result.chunkIds(), result.vectorIds(),
                result.chunks(), result.parseQuality());
    }

    private <T> T requireBody(T body, String message) {
        if (body == null) {
            throw new BusinessException(message);
        }
        return body;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record KnowledgeChunksResponse(List<KnowledgeChunk> chunks) {
        private KnowledgeChunksResponse {
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record DeleteResponse(int deletedCount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record HealthResponse(String status) {
    }
}
