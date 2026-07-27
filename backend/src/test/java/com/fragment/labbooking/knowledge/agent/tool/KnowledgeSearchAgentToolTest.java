package com.fragment.labbooking.knowledge.agent.tool;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.knowledge.agent.AgentState;
import com.fragment.labbooking.knowledge.agent.PolicyContext;
import com.fragment.labbooking.knowledge.service.AiServiceClient;
import com.fragment.labbooking.knowledge.service.KbDocumentService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeSearchAgentToolTest {

    @Test
    void shouldExposeOnlyCandidatesFromCurrentAccessibleDocumentVersions() {
        KbDocumentService documentService = mock(KbDocumentService.class);
        AiServiceClient aiServiceClient = mock(AiServiceClient.class);
        LoginUser actor = new LoginUser(7L, "user7", "用户", "USER", "13800000000");
        AgentState state = new AgentState("trace", "session", PolicyContext.from(actor));
        Map<Long, String> accessibleVersions = Map.of(12L, "v2");
        when(documentService.listAccessibleDocumentVersions(actor)).thenReturn(accessibleVersions);
        when(aiServiceClient.retrieveKnowledge("预约规则", accessibleVersions)).thenReturn(
                new AiServiceClient.KnowledgeSearchResult("预约规则", List.of(
                        candidate("current", 12L, "v2"),
                        candidate("stale", 12L, "v1"),
                        candidate("forbidden", 99L, "v2")
                )));

        AgentToolResult result = new KnowledgeSearchAgentTool(documentService, aiServiceClient).execute(
                new AgentToolInvocation(actor, "预约规则", state, Map.of("query", "预约规则")));

        List<?> candidates = (List<?>) result.output().get("candidates");
        assertThat(candidates).hasSize(1);
        assertThat(((Map<?, ?>) candidates.get(0)).get("chunk_uid")).isEqualTo("current");
        assertThat(state.authorizeKnowledgeChunkOpen(List.of("current", "stale", "forbidden")))
                .containsExactly("current");
    }

    private AiServiceClient.KnowledgeCandidate candidate(String chunkUid, Long documentId, String docVersion) {
        return new AiServiceClient.KnowledgeCandidate(
                chunkUid, documentId, docVersion, 0, 1, "规则", List.of("实验室制度"),
                "hash-" + chunkUid, 80, 0.9D, 0.8D, 0.7D,
                "local", "hybrid", "候选定位");
    }
}
