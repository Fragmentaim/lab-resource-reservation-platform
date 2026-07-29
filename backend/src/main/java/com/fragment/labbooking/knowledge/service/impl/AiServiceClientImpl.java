package com.fragment.labbooking.knowledge.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.knowledge.service.AiServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
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
    public ProcessResult processDocument(Long documentId, File file, String fileName, String fileType, String docVersion) {
        return call("文档重处理", () -> doProcessDocumentMultipart(
                documentId, new FileSystemResource(file), fileType, docVersion));
    }

    @Override
    public ProcessResult processDocumentByUrl(Long documentId, String fileUrl, String fileName, String fileType,
                                              String docVersion) {
        return call("文档 URL 处理", () -> {
            // MinIO 模式只把短期预签名 URL 交给 FastAPI，不复制大文件到 Java 进程内存。
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
        });
    }

    @Override
    public KnowledgeSearchResult retrieveKnowledge(String question, Map<Long, String> documentVersions) {
        return call("知识库候选检索", () -> {
            // Java 先计算 ACL 范围；Python 只能在这些文档及指定版本内做混合检索。
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
            return StringUtils.hasText(result.query()) ? result : new KnowledgeSearchResult(question, result.candidates());
        });
    }

    @Override
    public List<KnowledgeChunk> openKnowledgeChunks(List<String> chunkUids, Map<Long, String> documentVersions) {
        return call("知识库正文读取", () -> {
            // 再次传入 ACL 范围，避免候选 chunkId 被篡改后绕过第一次检索过滤。
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
        });
    }

    @Override
    public SummaryResult summarizeSession(String existingSummary, List<ChatMessage> newTurns) {
        return call("会话摘要更新", () -> {
            // 摘要服务接收旧交接记录和待移出的完整轮次，返回一份重写后的最新记录。
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
        });
    }

    @Override
    public int deleteDocumentVectors(Long documentId) {
        return delete("删除文档索引", "/api/v1/ai/documents/{id}/vectors", documentId);
    }

    @Override
    public int deleteDocumentVersion(Long documentId, String docVersion) {
        return call("删除文档版本索引", () -> {
            DeleteResponse response = restClient.delete()
                    .uri("/api/v1/ai/documents/{id}/versions/{version}", documentId, docVersion)
                    .retrieve()
                    .body(DeleteResponse.class);
            return requireBody(response, "AI-service 返回空删除结果").deletedCount();
        });
    }

    private ProcessResult doProcessDocumentMultipart(Long documentId, Object fileResource,
                                                     String fileType, String docVersion) {
        // 文档重处理通过 multipart 将本地文件传给 AI 服务。
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

    private int delete(String operation, String path, Long documentId) {
        return call(operation, () -> {
            DeleteResponse response = restClient.delete().uri(path, documentId).retrieve().body(DeleteResponse.class);
            return requireBody(response, "AI-service 返回空删除结果").deletedCount();
        });
    }

    private ProcessResult normalizeProcessResult(ProcessResult result, String fallbackDocVersion) {
        // 兼容 AI 服务缺省返回的 status/version，但不伪造 chunk 内容。
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

    private <T> T call(String operation, RemoteCall<T> remoteCall) {
        try {
            return remoteCall.call();
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            // 对外统一隐藏底层 HTTP 细节，日志保留完整异常供服务端排查。
            log.warn("AI service call failed. operation={}", operation, exception);
            throw new BusinessException(operation + "失败，请稍后重试");
        }
    }

    @FunctionalInterface
    private interface RemoteCall<T> {
        T call() throws Exception;
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

}
