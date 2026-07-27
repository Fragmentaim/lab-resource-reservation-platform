package com.fragment.labbooking.knowledge.agent.tool;

import com.fragment.labbooking.knowledge.service.AiServiceClient;
import com.fragment.labbooking.knowledge.service.KbDocumentService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class KnowledgeSearchAgentTool implements AgentTool {

    private final KbDocumentService kbDocumentService;
    private final AiServiceClient aiServiceClient;

    public KnowledgeSearchAgentTool(KbDocumentService kbDocumentService, AiServiceClient aiServiceClient) {
        this.kbDocumentService = kbDocumentService;
        this.aiServiceClient = aiServiceClient;
    }

    @Override
    public String name() {
        return "knowledge_search";
    }

    @Override
    public String accessScope() {
        return "ACL_FILTERED_KNOWLEDGE";
    }

    @Override
    public Map<String, Object> definition() {
        return Map.of("type", "function", "function", Map.of(
                "name", name(),
                "description", "检索当前用户有权限访问的实验室制度、预约规则、设备使用说明和流程。遇到规则、政策、流程、费用、处罚或无法由预约工具直接回答的问题时必须调用。只返回候选 chunk 定位信息；要依据知识库事实回答，必须再调用 knowledge_open_chunks 读取候选全文。",
                "parameters", Map.of("type", "object", "properties", Map.of(
                        "query", Map.of("type", "string", "description", "用于知识库检索的简短具体问题")
                ), "required", List.of("query"), "additionalProperties", false)
        ));
    }

    @Override
    public AgentToolResult execute(AgentToolInvocation invocation) {
        Map<Long, String> documentVersions = kbDocumentService.listAccessibleDocumentVersions(invocation.actor());
        if (documentVersions.isEmpty()) {
            return AgentToolResult.of(Map.of(
                    "status", "NO_ACCESSIBLE_DOCUMENTS",
                    "accessible_document_count", 0,
                    "candidates", List.of()
            ), Map.of("knowledge_status", "NO_ACCESSIBLE_DOCUMENTS", "candidate_count", 0));
        }
        String query = AgentToolArguments.optionalText(invocation.arguments().get("query"), 240);
        if (query == null) {
            query = invocation.originalQuestion();
        }
        AiServiceClient.KnowledgeSearchResult search = aiServiceClient.retrieveKnowledge(query, documentVersions);
        List<AiServiceClient.KnowledgeCandidate> candidates = search.candidates() == null
                ? List.of()
                : search.candidates().stream()
                .filter(candidate -> isCurrentAccessibleCandidate(candidate, documentVersions))
                .toList();
        invocation.state().registerKnowledgeCandidates(candidates.stream()
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
                "opened_chunk_count", 0
        ));
    }

    private boolean isCurrentAccessibleCandidate(AiServiceClient.KnowledgeCandidate candidate,
                                                 Map<Long, String> documentVersions) {
        if (candidate == null || candidate.chunkUid() == null || candidate.chunkUid().isBlank()
                || candidate.documentId() == null) {
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
}
