package com.fragment.labbooking.knowledge.agent.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fragment.labbooking.knowledge.service.AiServiceClient;

/** Complete, source-attributed evidence explicitly opened by the model. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
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

}
