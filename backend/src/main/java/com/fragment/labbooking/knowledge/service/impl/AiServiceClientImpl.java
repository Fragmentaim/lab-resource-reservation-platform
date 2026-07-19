package com.fragment.labbooking.knowledge.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.knowledge.service.AiServiceClient;
import com.fragment.labbooking.knowledge.vo.QaAnswerVO;
import com.fragment.labbooking.knowledge.vo.QaSourceVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404 || e.getStatusCode().value() == 405) {
                log.warn("AI service does not support process-by-url yet, falling back to multipart. documentId={}",
                        documentId);
                return processDocumentUrlByMultipartFallback(documentId, fileUrl, fileName, fileType, docVersion);
            }
            log.error("Failed to process document {} by URL via AI service: {}", documentId, e.getMessage());
            throw new BusinessException("文档 URL 处理失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to process document {} by URL via AI service: {}", documentId, e.getMessage());
            throw new BusinessException("文档 URL 处理失败: " + e.getMessage());
        }
    }

    private ProcessResult processDocumentUrlByMultipartFallback(Long documentId, String fileUrl, String fileName,
                                                                String fileType, String docVersion) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("knowledge-doc-", "." + safeSuffix(fileType));
            try (InputStream inputStream = URI.create(fileUrl).toURL().openStream()) {
                Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }
            return processDocument(documentId, tempFile.toFile(), fileName, fileType, docVersion);
        } catch (Exception e) {
            throw new BusinessException("文档 URL 兼容处理失败: " + e.getMessage());
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private String safeSuffix(String fileType) {
        if (fileType == null || fileType.isBlank()) {
            return "txt";
        }
        return fileType.replaceAll("[^A-Za-z0-9]", "").toLowerCase();
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
        return new ProcessResult(chunkCount, status, resolvedDocVersion, chunkIds, vectorIds, chunks);
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
    public QaAnswerVO askQuestion(String question, String sessionId, List<Long> documentIds) {
        return askQuestion(question, sessionId, documentIds, null, Collections.emptyList(), null, null);
    }

    @Override
    public QaAnswerVO askQuestion(String question, String sessionId, List<Long> documentIds,
                                  String sessionSummary, List<ChatMessage> chatHistory,
                                  ContextOptions contextOptions, String traceId) {
        try {
            String requestBody = objectMapper.writeValueAsString(
                    qaRequestBody(question, sessionId, documentIds, sessionSummary, chatHistory, contextOptions, traceId)
            );

            String response = restClient.post()
                    .uri("/api/v1/ai/qa/ask")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            JsonNode data = objectMapper.readTree(response);

            QaAnswerVO vo = new QaAnswerVO();
            vo.setAnswer(data.path("answer").asText());
            vo.setLatencyMs(data.path("latency_ms").asInt());
            vo.setModelName(data.path("model").asText());
            vo.setRewrittenQuestion(textOrNull(data, "rewritten_question"));
            vo.setRewriteApplied(data.path("rewrite_applied").asBoolean(false));
            vo.setContextStats(parseObject(data.path("context_stats")));

            vo.setSources(parseQaSources(data.path("sources")));
            return vo;
        } catch (Exception e) {
            log.error("Failed to ask question via AI service: {}", e.getMessage());
            throw new BusinessException("AI 服务请求失败: " + e.getMessage());
        }
    }

    @Override
    public KnowledgeSearchResult retrieveKnowledge(String question, List<Long> documentIds) {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("question", question);
            request.put("document_ids", documentIds == null ? Collections.emptyList() : documentIds);
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

    @Override
    public List<KnowledgeChunk> openKnowledgeChunks(List<String> chunkUids, List<Long> documentIds) {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("chunk_uids", chunkUids == null ? Collections.emptyList() : chunkUids);
            request.put("document_ids", documentIds == null ? Collections.emptyList() : documentIds);
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
    public void askQuestionStream(String question, String sessionId, List<Long> documentIds,
                                  StreamEventConsumer eventConsumer) {
        askQuestionStream(question, sessionId, documentIds, null, Collections.emptyList(), null, null, eventConsumer);
    }

    @Override
    public void askQuestionStream(String question, String sessionId, List<Long> documentIds,
                                  String sessionSummary, List<ChatMessage> chatHistory,
                                  ContextOptions contextOptions, String traceId,
                                  StreamEventConsumer eventConsumer) {
        try {
            String requestBody = objectMapper.writeValueAsString(
                    qaRequestBody(question, sessionId, documentIds, sessionSummary, chatHistory, contextOptions, traceId)
            );

            restClient.post()
                    .uri("/api/v1/ai/qa/ask/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.valueOf("application/x-ndjson"))
                    .body(requestBody)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw new BusinessException("AI 流式服务请求失败: HTTP " + response.getStatusCode());
                        }

                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (!line.isBlank()) {
                                    eventConsumer.accept(parseStreamEvent(line));
                                }
                            }
                        }
                        return null;
                    });
        } catch (Exception e) {
            log.error("Failed to stream question via AI service: {}", e.getMessage());
            throw new BusinessException("AI 流式服务请求失败: " + e.getMessage());
        }
    }

    @Override
    public SummaryResult summarizeSession(String existingSummary, List<ChatMessage> newTurns, Integer maxSummaryTokens) {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("existing_summary", existingSummary);
            request.put("new_turns", newTurns == null ? Collections.emptyList() : newTurns);
            request.put("max_summary_tokens", maxSummaryTokens);

            String response = restClient.post()
                    .uri("/api/v1/ai/qa/sessions/summarize")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(request))
                    .retrieve()
                    .body(String.class);

            JsonNode node = objectMapper.readTree(response);
            return new SummaryResult(
                    node.path("summary").asText(""),
                    node.path("summary_tokens").asInt(0)
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
            return 0;
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

    private StreamEvent parseStreamEvent(String line) throws IOException {
        JsonNode data = objectMapper.readTree(line);
        String type = data.path("type").asText();
        List<QaSourceVO> sources = null;

        JsonNode sourcesNode = data.path("sources");
        if (sourcesNode.isArray()) {
            sources = parseQaSources(sourcesNode);
        }

        Integer latencyMs = data.hasNonNull("latency_ms") ? data.path("latency_ms").asInt() : null;
        String model = data.hasNonNull("model") ? data.path("model").asText() : null;
        String content = data.hasNonNull("content") ? data.path("content").asText() : null;
        String message = data.hasNonNull("message") ? data.path("message").asText() : null;
        String rewrittenQuestion = data.hasNonNull("rewritten_question") ? data.path("rewritten_question").asText() : null;
        Boolean rewriteApplied = data.hasNonNull("rewrite_applied") ? data.path("rewrite_applied").asBoolean() : null;
        Map<String, Object> contextStats = parseObject(data.path("context_stats"));
        return new StreamEvent(type, content, sources, latencyMs, model, message,
                rewrittenQuestion, rewriteApplied, contextStats);
    }

    private Map<String, Object> qaRequestBody(String question, String sessionId, List<Long> documentIds,
                                              String sessionSummary, List<ChatMessage> chatHistory,
                                              ContextOptions contextOptions, String traceId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("question", question);
        request.put("session_id", sessionId != null ? sessionId : "");
        request.put("document_ids", documentIds == null ? Collections.emptyList() : documentIds);
        if (sessionSummary != null) {
            request.put("session_summary", sessionSummary);
        }
        if (chatHistory != null && !chatHistory.isEmpty()) {
            request.put("chat_history", chatHistory);
        }
        if (traceId != null) {
            request.put("trace_id", traceId);
        }
        if (contextOptions != null) {
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("context_window_tokens", contextOptions.contextWindowTokens());
            options.put("max_output_tokens", contextOptions.maxOutputTokens());
            options.put("safety_margin_tokens", contextOptions.safetyMarginTokens());
            options.put("summary_max_tokens", contextOptions.summaryMaxTokens());
            request.put("context_options", options);
        }
        return request;
    }

    private List<QaSourceVO> parseQaSources(JsonNode sourcesNode) {
        if (sourcesNode == null || !sourcesNode.isArray()) {
            return Collections.emptyList();
        }

        List<QaSourceVO> sources = new ArrayList<>();
        for (JsonNode sourceNode : sourcesNode) {
            sources.add(parseQaSource(sourceNode));
        }
        return sources;
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

    private QaSourceVO parseQaSource(JsonNode node) {
        QaSourceVO source = new QaSourceVO();
        source.setDocumentId(node.path("document_id").asLong());

        String chunkId = textOrNull(node, "chunk_id");
        source.setChunkId(chunkId);
        source.setChunkUid(chunkId);
        source.setChunkIndex(nullableInt(node, "chunk_index"));
        source.setPageNo(nullableInt(node, "page_no"));
        source.setSectionTitle(textOrNull(node, "section_title"));
        source.setTitlePath(parseStringArray(node.path("title_path")));
        source.setDocVersion(textOrNull(node, "doc_version"));
        source.setContentHash(textOrNull(node, "content_hash"));
        source.setScore(node.path("score").asDouble());
        source.setRetrievalScore(nullableDouble(node, "retrieval_score"));
        source.setRerankScore(nullableDouble(node, "rerank_score"));
        source.setRerankProvider(textOrNull(node, "rerank_provider"));
        source.setRetrievalSource(textOrNull(node, "retrieval_source"));
        source.setExcerpt(node.path("excerpt").asText());
        source.setDocumentTitle(textOrNull(node, "document_title"));
        return source;
    }

    private String textOrNull(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private Double nullableDouble(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isMissingNode() || value.isNull() ? null : value.asDouble();
    }

    private Map<String, Object> parseObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Collections.emptyMap();
        }
        return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
    }
}
