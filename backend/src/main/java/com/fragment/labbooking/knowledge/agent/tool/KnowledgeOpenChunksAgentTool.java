package com.fragment.labbooking.knowledge.agent.tool;

import com.fragment.labbooking.knowledge.agent.EvidenceCard;
import com.fragment.labbooking.knowledge.service.AiServiceClient;
import com.fragment.labbooking.knowledge.service.KbDocumentService;
import com.fragment.labbooking.knowledge.vo.QaSourceVO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class KnowledgeOpenChunksAgentTool implements AgentTool {

    private final KbDocumentService kbDocumentService;
    private final AiServiceClient aiServiceClient;

    public KnowledgeOpenChunksAgentTool(KbDocumentService kbDocumentService, AiServiceClient aiServiceClient) {
        this.kbDocumentService = kbDocumentService;
        this.aiServiceClient = aiServiceClient;
    }

    @Override
    public String name() {
        return "knowledge_open_chunks";
    }

    @Override
    public String accessScope() {
        return "ACL_FILTERED_KNOWLEDGE";
    }

    @Override
    public Map<String, Object> definition() {
        return Map.of("type", "function", "function", Map.of(
                "name", name(),
                "description", "读取本次 knowledge_search 已返回候选中的完整 chunk 正文。仅在需要知识库事实依据时调用；只能传候选中的 chunkUid，不能猜测 ID。",
                "parameters", Map.of("type", "object", "properties", Map.of(
                        "chunkUids", Map.of("type", "array", "items", Map.of("type", "string"), "description", "从本次候选中选择的 chunkUid，按需选择")
                ), "required", List.of("chunkUids"), "additionalProperties", false)
        ));
    }

    @Override
    public AgentToolResult execute(AgentToolInvocation invocation) {
        List<String> requestedChunkUids = AgentToolArguments.requiredStringList(
                invocation.arguments().get("chunkUids"), "chunkUids");
        List<String> authorizedChunkUids = invocation.state().authorizeKnowledgeChunkOpen(requestedChunkUids);
        if (authorizedChunkUids.isEmpty()) {
            return new AgentToolResult(Map.of("status", "NO_AUTHORIZED_CANDIDATES", "chunks", List.of()), 0,
                    Map.of("knowledge_status", "NO_AUTHORIZED_CANDIDATES", "candidate_count", 0, "opened_chunk_count", 0));
        }
        // Re-evaluate document ACL at read time; Python applies the same list to the vector-store filter.
        List<Long> documentIds = kbDocumentService.listAccessibleReadyDocumentIds(invocation.actor());
        if (documentIds.isEmpty()) {
            return new AgentToolResult(Map.of("status", "NO_ACCESSIBLE_DOCUMENTS", "chunks", List.of()), 0,
                    Map.of("knowledge_status", "NO_ACCESSIBLE_DOCUMENTS", "candidate_count", 0, "opened_chunk_count", 0));
        }
        List<AiServiceClient.KnowledgeChunk> chunks = aiServiceClient.openKnowledgeChunks(authorizedChunkUids, documentIds);
        invocation.state().registerOpenedKnowledgeChunks(chunks.stream().map(AiServiceClient.KnowledgeChunk::chunkUid).toList());
        chunks.stream().map(this::toQaSource).forEach(invocation.openedSources()::add);
        List<Map<String, Object>> evidence = chunks.stream().map(EvidenceCard::from).map(EvidenceCard::toToolPayload).toList();
        String status = evidence.isEmpty() ? "NO_MATCH" : "OK";
        return new AgentToolResult(Map.of("status", status, "chunks", evidence), evidence.size(), Map.of(
                "knowledge_status", status,
                "candidate_count", 0,
                "opened_chunk_count", evidence.size()
        ));
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

    private String sourceExcerpt(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= 240 ? content : content.substring(0, 240);
    }
}
