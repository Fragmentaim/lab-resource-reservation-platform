package com.fragment.labbooking.knowledge.agent.model;

import com.fragment.labbooking.knowledge.service.AiServiceClient;

import java.util.LinkedHashMap;
import java.util.Map;

/** Complete, source-attributed evidence explicitly opened by the model. */
public record EvidenceCard(String chunkUid, String sectionTitle, Integer pageNo,
                           java.util.List<String> titlePath, Integer tokenCount, String content) {

    public EvidenceCard {
        chunkUid = AgentModelGuard.text(chunkUid);
        sectionTitle = AgentModelGuard.text(sectionTitle);
        titlePath = AgentModelGuard.list(titlePath);
        content = AgentModelGuard.text(content);
    }

    public static EvidenceCard from(AiServiceClient.KnowledgeChunk source) {
        return new EvidenceCard(
                source.chunkUid(), source.sectionTitle(), source.pageNo(), source.titlePath(),
                source.tokenCount(), source.content()
        );
    }

    public Map<String, Object> toToolPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chunk_uid", chunkUid);
        payload.put("section_title", sectionTitle);
        payload.put("page_no", pageNo);
        payload.put("title_path", titlePath);
        payload.put("token_count", tokenCount);
        payload.put("content", content);
        return payload;
    }
}
