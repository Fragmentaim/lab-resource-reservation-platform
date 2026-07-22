package com.fragment.labbooking.knowledge.evaluation;

import com.fragment.labbooking.knowledge.vo.AgentRunVO;
import com.fragment.labbooking.knowledge.vo.AgentStepVO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunTraceAdapterTest {

    @Test
    void shouldBuildEvaluationTraceFromPersistedStepsWithoutGuessingUsage() {
        AgentRunVO run = new AgentRunVO();
        run.setStatus("SUCCEEDED");
        run.setTotalLatencyMs(200);
        run.setStepCount(4);
        run.setSourceCount(1);
        AgentStepVO firstPlan = step("PLAN", "model_tool_plan", "SUCCEEDED", 0,
                Map.of("model_decision_latency_ms", 70, "requested_tools", List.of("knowledge_search"),
                        "provider_usage", Map.of("input_tokens", 100, "output_tokens", 10, "cached_input_tokens", 5)));
        AgentStepVO tool = step("TOOL_CALL", "knowledge_search", "SUCCESS", 30,
                Map.of("evaluation_arguments", Map.of("query", "预约规则")));
        AgentStepVO answerPlan = step("PLAN", "model_tool_plan", "SUCCEEDED", 0,
                Map.of("model_decision_latency_ms", 60, "requested_tools", List.of(),
                        "provider_usage", Map.of("input_tokens", 120, "output_tokens", 20)));

        AgentEvaluationTrace trace = AgentRunTraceAdapter.toTaskTrace("case-1", run, List.of(firstPlan, tool, answerPlan),
                Map.of("knowledge_status", "OK"), .12D);

        assertThat(trace.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.toolName()).isEqualTo("knowledge_search");
            assertThat(call.arguments()).containsEntry("query", "预约规则");
        });
        assertThat(trace.timing().firstModelDecisionMs()).isEqualTo(70);
        assertThat(trace.timing().finalModelGenerationMs()).isEqualTo(60);
        assertThat(trace.timing().retrievalMs()).isEqualTo(30);
        assertThat(trace.timing().databaseAndNetworkMs()).isEqualTo(40);
        assertThat(trace.usage().inputTokens()).isEqualTo(220);
        assertThat(trace.usage().outputTokens()).isEqualTo(30);
        assertThat(trace.businessResult()).containsEntry("source_count", 1);
    }

    @Test
    void shouldInferBusinessOutcomeFromSafeToolStepDetail() {
        AgentRunVO run = new AgentRunVO();
        run.setSourceCount(2);
        AgentStepVO search = step("TOOL_CALL", "knowledge_search", "SUCCESS", 10,
                Map.of("knowledge_status", "OK"));
        AgentStepVO preview = step("TOOL_CALL", "reservation_cancellation_preview", "SUCCESS", 10,
                Map.of("result_type", "CANCELLATION_PREVIEW", "reservationId", 5001L, "writeExecuted", false));

        assertThat(AgentRunTraceAdapter.inferBusinessResult(run, List.of(search, preview)))
                .containsEntry("knowledge_status", "OK")
                .containsEntry("result_type", "CANCELLATION_PREVIEW")
                .containsEntry("reservationId", 5001L)
                .containsEntry("writeExecuted", false)
                .containsEntry("source_count", 2);
    }

    private AgentStepVO step(String type, String name, String status, Integer latency, Map<String, Object> detail) {
        AgentStepVO value = new AgentStepVO();
        value.setStepType(type);
        value.setName(name);
        value.setStatus(status);
        value.setLatencyMs(latency);
        value.setDetail(detail);
        return value;
    }
}
