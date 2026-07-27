package com.fragment.labbooking.knowledge.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentModelMapperTest {

    private final AgentModelMapper mapper = new AgentModelMapper(new ObjectMapper());

    @Test
    void shouldUseSnakeCaseAndDerivedContextPlanFields() {
        Map<String, Object> detail = mapper.detail(new ContextPlan(
                2, "model-a", List.of("knowledge_search"), Map.of(), 35));

        assertThat(detail)
                .containsEntry("round", 2)
                .containsEntry("requested_tools", List.of("knowledge_search"))
                .containsEntry("requested_tool_count", 1)
                .containsEntry("model_decision_latency_ms", 35)
                .containsEntry("provider_usage", Map.of("reported", false));
        assertThat(detail).doesNotContainKey("providerUsage");
    }

    @Test
    void shouldExposeSessionIdsWithoutSerializingConversationText() {
        SessionContextPlan plan = new SessionContextPlan(
                48_000, 2_000, 1_000, 45_000, 10, 20, 30,
                List.of(new SessionTurn(11L, "trace-11", "private question", "private answer")),
                List.of(new SessionTurn(12L, "trace-12", "older question", "older answer")),
                1, true);

        Map<String, Object> detail = mapper.detail(plan);

        assertThat(detail)
                .containsEntry("included_record_ids", List.of(11L))
                .containsEntry("deferred_record_ids", List.of(12L))
                .containsEntry("selection_unit", "COMPLETE_TURN");
        assertThat(detail.toString()).doesNotContain("private question", "private answer", "older question");
        assertThat(detail).doesNotContainKeys("included_turns", "deferred_turns", "history_messages");
    }

    @Test
    void shouldKeepPolicyIdentityAndToolInternalsOutOfAuditDetails() {
        Map<String, Object> policy = mapper.attributes(new PolicyContext(7L, "USER", false));
        Map<String, Object> tool = mapper.detail(new AgentToolExecution(
                "knowledge_search", "SUCCEEDED", 18, "tool-trace", "SPRING_AI",
                Map.of("candidate_count", 3)));

        assertThat(policy).containsOnlyKeys("role", "actor_type")
                .containsEntry("actor_type", "USER");
        assertThat(tool).containsOnlyKeys("protocol", "candidate_count")
                .containsEntry("protocol", "SPRING_AI");
    }

    @Test
    void shouldConvertEvidenceCardWithoutManualPayloadCopying() {
        Map<String, Object> payload = mapper.toolPayload(new EvidenceCard(
                "chunk-1", "规则", 3, List.of("比赛", "雷达"), 120, "正文"));

        assertThat(payload)
                .containsEntry("chunk_uid", "chunk-1")
                .containsEntry("section_title", "规则")
                .containsEntry("page_no", 3)
                .containsEntry("title_path", List.of("比赛", "雷达"))
                .containsEntry("token_count", 120)
                .containsEntry("content", "正文");
    }
}
