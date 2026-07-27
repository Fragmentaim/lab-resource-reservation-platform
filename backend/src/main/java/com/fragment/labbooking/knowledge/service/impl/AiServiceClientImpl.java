package com.fragment.labbooking.knowledge.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.knowledge.service.AiServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class AiServiceClientImpl implements AiServiceClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AiServiceClientImpl(RestClient aiServiceRestClient, ObjectMapper objectMapper) {
        this.restClient = aiServiceRestClient;
        this.objectMapper = objectMapper;
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
            return doProcessDocumentMultipart(documentId, new FileSystemResource(file), fileName, fileType, docVersion);
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
            return doProcessDocumentMultipart(documentId, resource, fileName, fileType, docVersion);
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

            String response = restClient.post()
                    .uri("/api/v1/ai/documents/process-by-url")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(request))
                    .retrieve()
                    .body(String.class);
            return parseProcessResult(response, docVersion);
        } catch (Exception e) {
            log.error("Failed to process document {} by URL via AI service: {}", documentId, e.getMessage());
            throw new BusinessException("文档 URL 处理失败: " + e.getMessage());
        }
    }

    private ProcessResult doProcessDocumentMultipart(Long documentId, Object fileResource, String fileName,
                                                     String fileType, String docVersion) throws Exception {
        MultiValueMap<String, Object> formData = new LinkedMultiValueMap<>();
        formData.add("document_id", documentId.toString());
        formData.add("file_type", fileType);
        formData.add("doc_version", docVersion);
        formData.add("file", fileResource);

        String response = restClient.post()
                .uri("/api/v1/ai/documents/process")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(formData)
                .retrieve()
                .body(String.class);

        return parseProcessResult(response, docVersion);
    }

    private ProcessResult parseProcessResult(String response, String docVersion) throws IOException {
        JsonNode node = objectMapper.readTree(response);
        int chunkCount = node.path("chunk_count").asInt();
        String status = node.path("status").asText("FAILED");
        String resolvedDocVersion = node.path("doc_version").asText(docVersion);
        List<String> chunkIds = parseStringArray(node.path("chunk_ids"));
        List<String> vectorIds = parseStringArray(node.path("vector_ids"));
        List<ChunkResult> chunks = parseChunks(node.path("chunks"));
        return new ProcessResult(
                chunkCount,
                status,
                resolvedDocVersion,
                chunkIds,
                vectorIds,
                chunks,
                parseQuality(node.path("parse_quality"))
        );
    }

    private List<String> parseStringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            values.add(item.asText());
        }
        return values;
    }

    private List<ChunkResult> parseChunks(JsonNode node) {
        if (node == null || !node.isArray()) {
            return Collections.emptyList();
        }
        List<ChunkResult> chunks = new ArrayList<>();
        for (JsonNode item : node) {
            chunks.add(new ChunkResult(
                    item.path("chunk_id").asText(null),
                    nullableInt(item, "chunk_index"),
                    item.path("content").asText(""),
                    nullableInt(item, "token_count"),
                    nullableInt(item, "page_no"),
                    item.path("section_title").asText(null),
                    parseStringArray(item.path("title_path")),
                    item.path("content_hash").asText(null),
                    nullableInt(item, "char_start"),
                    nullableInt(item, "char_end"),
                    item.path("vector_id").asText(null)
            ));
        }
        return chunks;
    }

    private Integer nullableInt(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isMissingNode() || value.isNull() ? null : value.asInt();
    }

    @Override
    public KnowledgeSearchResult retrieveKnowledge(String question, Map<Long, String> documentVersions) {
        try {
            Map<Long, String> scopes = documentVersions == null ? Collections.emptyMap() : documentVersions;
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("question", question);
            request.put("document_ids", scopes.keySet());
            request.put("document_versions", scopes);
            String response = restClient.post()
                    .uri("/api/v1/ai/qa/retrieve")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(request))
                    .retrieve()
                    .body(String.class);
            JsonNode data = objectMapper.readTree(response);
            return new KnowledgeSearchResult(data.path("query").asText(question),
                    parseKnowledgeCandidates(data.path("candidates")));
        } catch (Exception e) {
            log.error("Failed to retrieve knowledge candidates via AI service: {}", e.getMessage());
            throw new BusinessException("知识库候选检索失败: " + e.getMessage());
        }
    }

    private ParseQuality parseQuality(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        return new ParseQuality(
                textOrNull(node, "provider"),
                textOrNull(node, "provider_version"),
                textOrNull(node, "parse_mode"),
                nullableInt(node, "unit_count"),
                nullableInt(node, "non_empty_unit_count"),
                nullableInt(node, "character_count"),
                nullableInt(node, "heading_count"),
                nullableInt(node, "table_count"),
                nullableInt(node, "image_count"),
                nullableInt(node, "scanned_unit_count"),
                nullableInt(node, "ocr_unit_count"),
                nullableInt(node, "ocr_character_count"),
                nullableDouble(node, "ocr_average_confidence"),
                nullableDouble(node, "parse_average_confidence"),
                nullableDouble(node, "layout_average_confidence"),
                nullableDouble(node, "table_average_confidence"),
                nullableInt(node, "empty_unit_count"),
                nullableDouble(node, "quality_score"),
                nullableDouble(node, "quality_low_score"),
                parseStringArray(node.path("warnings")),
                node.toString()
        );
    }

    @Override
    public List<KnowledgeChunk> openKnowledgeChunks(List<String> chunkUids, Map<Long, String> documentVersions) {
        try {
            Map<Long, String> scopes = documentVersions == null ? Collections.emptyMap() : documentVersions;
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("chunk_uids", chunkUids == null ? Collections.emptyList() : chunkUids);
            request.put("document_ids", scopes.keySet());
            request.put("document_versions", scopes);
            String response = restClient.post()
                    .uri("/api/v1/ai/qa/chunks/open")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(request))
                    .retrieve()
                    .body(String.class);
            return parseKnowledgeChunks(objectMapper.readTree(response).path("chunks"));
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

            String response = restClient.post()
                    .uri("/api/v1/ai/qa/sessions/summarize")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(request))
                    .retrieve()
                    .body(String.class);

            JsonNode node = objectMapper.readTree(response);
            return new SummaryResult(
                    node.path("summary").asText(""),
                    node.path("summary_tokens").asInt(0),
                    node.path("provider_usage").isObject()
                            ? objectMapper.convertValue(node.path("provider_usage"), new TypeReference<Map<String, Object>>() {})
                            : Map.of()
            );
        } catch (Exception e) {
            log.warn("Failed to summarize session via AI service: {}", e.getMessage());
            throw new BusinessException("会话摘要更新失败: " + e.getMessage());
        }
    }

    @Override
    public int deleteDocumentVectors(Long documentId) {
        try {
            String response = restClient.delete()
                    .uri("/api/v1/ai/documents/{id}/vectors", documentId)
                    .retrieve()
                    .body(String.class);

            JsonNode node = objectMapper.readTree(response);
            return node.path("deleted_count").asInt();
        } catch (Exception e) {
            log.error("Failed to delete vectors for document {}: {}", documentId, e.getMessage());
            throw new BusinessException("删除文档索引失败: " + e.getMessage());
        }
    }

    @Override
    public int deleteDocumentVersion(Long documentId, String docVersion) {
        try {
            String response = restClient.delete()
                    .uri("/api/v1/ai/documents/{id}/versions/{version}", documentId, docVersion)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(response).path("deleted_count").asInt();
        } catch (Exception e) {
            log.error("Failed to delete document version. documentId={}, version={}, error={}",
                    documentId, docVersion, e.getMessage());
            throw new BusinessException("删除文档版本索引失败: " + e.getMessage());
        }
    }

    @Override
    public boolean checkHealth() {
        try {
            String response = restClient.get()
                    .uri("/api/v1/ai/health")
                    .retrieve()
                    .body(String.class);
            JsonNode node = objectMapper.readTree(response);
            return "ok".equals(node.path("status").asText());
        } catch (Exception e) {
            return false;
        }
    }

    private List<KnowledgeCandidate> parseKnowledgeCandidates(JsonNode node) {
        if (node == null || !node.isArray()) {
            return Collections.emptyList();
        }
        List<KnowledgeCandidate> candidates = new ArrayList<>();
        for (JsonNode item : node) {
            candidates.add(new KnowledgeCandidate(
                    textOrNull(item, "chunk_uid"), item.path("document_id").asLong(),
                    textOrNull(item, "doc_version"), nullableInt(item, "chunk_index"), nullableInt(item, "page_no"),
                    textOrNull(item, "section_title"), parseStringArray(item.path("title_path")),
                    textOrNull(item, "content_hash"), nullableInt(item, "token_count"),
                    nullableDouble(item, "score"), nullableDouble(item, "retrieval_score"),
                    nullableDouble(item, "rerank_score"), textOrNull(item, "rerank_provider"),
                    textOrNull(item, "retrieval_source"), item.path("locator").asText("")
            ));
        }
        return candidates;
    }

    private List<KnowledgeChunk> parseKnowledgeChunks(JsonNode node) {
        if (node == null || !node.isArray()) {
            return Collections.emptyList();
        }
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (JsonNode item : node) {
            chunks.add(new KnowledgeChunk(
                    textOrNull(item, "chunk_uid"), item.path("document_id").asLong(),
                    textOrNull(item, "doc_version"), nullableInt(item, "chunk_index"), nullableInt(item, "page_no"),
                    textOrNull(item, "section_title"), parseStringArray(item.path("title_path")),
                    textOrNull(item, "content_hash"), nullableInt(item, "token_count"), item.path("content").asText("")
            ));
        }
        return chunks;
    }

    private String textOrNull(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private Double nullableDouble(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isMissingNode() || value.isNull() ? null : value.asDouble();
    }

}
