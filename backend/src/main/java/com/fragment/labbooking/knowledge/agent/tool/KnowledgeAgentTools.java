package com.fragment.labbooking.knowledge.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.knowledge.agent.AgentContext;
import com.fragment.labbooking.knowledge.service.AiServiceClient;
import com.fragment.labbooking.knowledge.service.KbDocumentService;
import com.fragment.labbooking.knowledge.vo.QaSourceVO;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** ACL-aware knowledge capabilities exposed through Spring AI's native annotated tools. */
@Component
public class KnowledgeAgentTools {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final int MAX_SEARCH_CANDIDATES = 8;
    private static final int MAX_OPEN_CHUNKS = 4;

    private final AgentToolRuntime runtime;
    private final KbDocumentService documentService;
    private final AiServiceClient aiServiceClient;
    private final ObjectMapper objectMapper;

    public KnowledgeAgentTools(AgentToolRuntime runtime, KbDocumentService documentService,
                               AiServiceClient aiServiceClient, ObjectMapper objectMapper) {
        this.runtime = runtime;
        this.documentService = documentService;
        this.aiServiceClient = aiServiceClient;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "knowledge_search", description = "检索当前用户有权限访问的实验室制度、预约规则、设备使用说明和流程。遇到规则、政策、流程、费用、处罚或无法由预约工具直接回答的问题时必须调用。只返回候选 chunk 定位信息；要依据知识库事实回答，必须再调用 knowledge_open_chunks 读取候选全文。")
    public Map<String, Object> knowledgeSearch(
            @ToolParam(description = "用于知识库检索的简短具体问题") String query,
            ToolContext toolContext) {
        String normalizedQuery = optionalText(query, 240);
        return runtime.executeFromToolContext("knowledge_search", "ACL_FILTERED_KNOWLEDGE",
                Map.of("query", normalizedQuery == null ? "" : normalizedQuery), toolContext, context -> {
                    String finalQuery = normalizedQuery == null ? context.question() : normalizedQuery;
                    return searchKnowledge(context, finalQuery);
                });
    }

    @Tool(name = "knowledge_open_chunks", description = "读取本次 knowledge_search 已返回候选中的完整 chunk 正文。仅在需要知识库事实依据时调用；只能传候选中的 chunkUid，不能猜测 ID。")
    public Map<String, Object> knowledgeOpenChunks(
            @ToolParam(description = "从本次候选中按需选择的 chunkUid") List<String> chunkUids,
            ToolContext toolContext) {
        return runtime.executeFromToolContext("knowledge_open_chunks", "ACL_FILTERED_KNOWLEDGE",
                arguments("chunkUids", chunkUids), toolContext,
                context -> openKnowledgeChunks(context, requiredStrings(chunkUids, "chunkUids").stream()
                        .limit(MAX_OPEN_CHUNKS).toList()));
    }

    private AgentToolResult searchKnowledge(AgentContext context, String query) {
        Map<Long, String> documentVersions = documentService.listAccessibleDocumentVersions(context.actor());
        if (documentVersions.isEmpty()) {
            return AgentToolResult.of(Map.of(
                    "status", "NO_ACCESSIBLE_DOCUMENTS",
                    "accessible_document_count", 0,
                    "candidates", List.of()),
                    Map.of("knowledge_status", "NO_ACCESSIBLE_DOCUMENTS", "candidate_count", 0));
        }
        AiServiceClient.KnowledgeSearchResult search = aiServiceClient.retrieveKnowledge(query, documentVersions);
        List<AiServiceClient.KnowledgeCandidate> candidates = search.candidates() == null ? List.of()
                : search.candidates().stream()
                .filter(candidate -> isCurrentAccessibleCandidate(candidate, documentVersions))
                .limit(MAX_SEARCH_CANDIDATES)
                .toList();
        context.registerKnowledgeCandidates(candidates.stream()
                .map(candidate -> Map.entry(candidate.chunkUid(), candidate.documentId()))
                .toList());
        List<Map<String, Object>> payloadCandidates = candidates.stream().map(this::candidatePayload).toList();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", payloadCandidates.isEmpty() ? "NO_MATCH" : "OK");
        output.put("accessible_document_count", documentVersions.size());
        output.put("candidate_count", payloadCandidates.size());
        output.put("candidates", payloadCandidates);
        if (!payloadCandidates.isEmpty()) {
            output.put("next_action", "从候选中选择 chunk_uid 并调用 knowledge_open_chunks 后再回答");
        }
        return AgentToolResult.of(output, Map.of(
                "knowledge_status", output.get("status"),
                "candidate_count", payloadCandidates.size(),
                "opened_chunk_count", 0));
    }

    private AgentToolResult openKnowledgeChunks(AgentContext context, List<String> requestedChunkUids) {
        List<String> authorized = context.authorizeKnowledgeChunks(requestedChunkUids);
        if (authorized.isEmpty()) {
            return AgentToolResult.of(Map.of("status", "NO_AUTHORIZED_CANDIDATES", "chunks", List.of()),
                    Map.of("knowledge_status", "NO_AUTHORIZED_CANDIDATES", "candidate_count", 0,
                            "opened_chunk_count", 0));
        }
        Map<Long, String> documentVersions = documentService.listAccessibleDocumentVersions(context.actor());
        if (documentVersions.isEmpty()) {
            return AgentToolResult.of(Map.of("status", "NO_ACCESSIBLE_DOCUMENTS", "chunks", List.of()),
                    Map.of("knowledge_status", "NO_ACCESSIBLE_DOCUMENTS", "candidate_count", 0,
                            "opened_chunk_count", 0));
        }
        List<AiServiceClient.KnowledgeChunk> chunks = aiServiceClient.openKnowledgeChunks(authorized, documentVersions);
        if (!chunks.isEmpty()) context.markKnowledgeOpened();
        List<QaSourceVO> sources = chunks.stream().map(this::toQaSource).toList();
        List<Map<String, Object>> evidence = chunks.stream()
                .map(Evidence::from).map(value -> objectMapper.convertValue(value, MAP_TYPE)).toList();
        String status = evidence.isEmpty() ? "NO_MATCH" : "OK";
        return AgentToolResult.withSources(Map.of("status", status, "chunks", evidence), sources, Map.of(
                "knowledge_status", status,
                "candidate_count", 0,
                "opened_chunk_count", evidence.size()));
    }

    private boolean isCurrentAccessibleCandidate(AiServiceClient.KnowledgeCandidate candidate,
                                                  Map<Long, String> documentVersions) {
        if (candidate == null || !StringUtils.hasText(candidate.chunkUid()) || candidate.documentId() == null) {
            return false;
        }
        String currentVersion = documentVersions.get(candidate.documentId());
        return currentVersion != null && currentVersion.equals(candidate.docVersion());
    }

    private Map<String, Object> candidatePayload(AiServiceClient.KnowledgeCandidate candidate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chunk_uid", candidate.chunkUid());
        payload.put("section_title", candidate.sectionTitle());
        payload.put("page_no", candidate.pageNo());
        payload.put("title_path", candidate.titlePath() == null ? List.of() : candidate.titlePath());
        payload.put("token_count", candidate.tokenCount());
        payload.put("score", candidate.score());
        payload.put("retrieval_source", candidate.retrievalSource());
        payload.put("locator", candidate.locator());
        return payload;
    }

    private QaSourceVO toQaSource(AiServiceClient.KnowledgeChunk chunk) {
        QaSourceVO source = new QaSourceVO();
        source.setDocumentId(chunk.documentId());
        source.setChunkId(chunk.chunkUid());
        source.setChunkUid(chunk.chunkUid());
        source.setChunkIndex(chunk.chunkIndex());
        source.setPageNo(chunk.pageNo());
        source.setSectionTitle(chunk.sectionTitle());
        source.setTitlePath(chunk.titlePath() == null ? List.of() : chunk.titlePath());
        source.setDocVersion(chunk.docVersion());
        source.setContentHash(chunk.contentHash());
        source.setExcerpt(sourceExcerpt(chunk.content()));
        return source;
    }

    private Map<String, Object> arguments(String key, Object value) {
        return value == null ? Map.of() : Map.of(key, value);
    }

    private String optionalText(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String text = value.trim();
        return text.substring(0, Math.min(text.length(), maxLength));
    }

    private List<String> requiredStrings(List<String> values, String field) {
        if (values == null || values.isEmpty()) {
            throw new BusinessException("工具参数 " + field + " 必须是非空数组");
        }
        List<String> normalized = values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            throw new BusinessException("工具参数 " + field + " 必须是非空数组");
        }
        return normalized;
    }

    private String sourceExcerpt(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= 240 ? content : content.substring(0, 240);
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record Evidence(String chunkUid, String sectionTitle, Integer pageNo,
                            List<String> titlePath, Integer tokenCount, String content) {
        private Evidence {
            chunkUid = chunkUid == null ? "" : chunkUid;
            sectionTitle = sectionTitle == null ? "" : sectionTitle;
            titlePath = titlePath == null ? List.of() : List.copyOf(titlePath);
            content = content == null ? "" : content;
        }

        static Evidence from(AiServiceClient.KnowledgeChunk source) {
            return new Evidence(source.chunkUid(), source.sectionTitle(), source.pageNo(), source.titlePath(),
                    source.tokenCount(), source.content());
        }
    }
}
