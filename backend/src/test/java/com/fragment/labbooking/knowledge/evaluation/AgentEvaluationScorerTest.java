package com.fragment.labbooking.knowledge.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEvaluationScorerTest {

    @Test
    void shouldBuildTheFixed120CaseTaskSuiteWithRequestedScenarioCoverage() {
        List<AgentEvaluationCase> cases = AgentEvaluationFixtures.taskSuiteV1();

        assertThat(AgentEvaluationScorer.validateDataset(cases, AgentEvaluationFixtures.TOTAL_CASES)).isEmpty();
        assertThat(cases).hasSize(120);
        assertThat(cases.stream().filter(value -> value.scenario().equals("普通问答，不应调用工具"))).hasSize(15);
        assertThat(cases.stream().filter(value -> value.scenario().equals("RAG知识库查询"))).hasSize(20);
        assertThat(cases.stream().filter(value -> value.scenario().equals("查询个人预约"))).hasSize(15);
        assertThat(cases.stream().filter(value -> value.scenario().equals("查询资源可用性"))).hasSize(15);
        assertThat(cases.stream().filter(value -> value.scenario().equals("取消预约预览"))).hasSize(15);
        assertThat(cases.stream().filter(value -> value.scenario().equals("多工具连续调用"))).hasSize(20);
        assertThat(cases.stream().filter(value -> value.scenario().equals("参数缺失、模糊需求"))).hasSize(10);
        assertThat(cases.stream().filter(value -> value.scenario().equals("无权限访问、恶意越权"))).hasSize(10);
    }

    @Test
    void shouldScoreSelectionArgumentsCompletionAndFalseToolCallsSeparately() {
        AgentEvaluationCase direct = AgentEvaluationFixtures.taskSuiteV1().get(0);
        AgentEvaluationCase knowledge = AgentEvaluationFixtures.taskSuiteV1().stream()
                .filter(value -> value.caseId().equals("knowledge-01")).findFirst().orElseThrow();
        AgentEvaluationCase cancellation = AgentEvaluationFixtures.taskSuiteV1().stream()
                .filter(value -> value.caseId().equals("cancel-preview-01")).findFirst().orElseThrow();

        List<AgentEvaluationTrace> traces = List.of(
                new AgentEvaluationTrace(direct.caseId(), "SUCCEEDED", List.of(
                        new AgentEvaluationTrace.ExecutedToolCall("reservation_context", Map.of(), "SUCCESS", Map.of())),
                        Map.of("route", "ANSWER_ONLY"), 2, null, null),
                new AgentEvaluationTrace(knowledge.caseId(), "SUCCEEDED", List.of(
                        new AgentEvaluationTrace.ExecutedToolCall("knowledge_search", Map.of("query", knowledge.question()), "SUCCESS", Map.of()),
                        new AgentEvaluationTrace.ExecutedToolCall("knowledge_open_chunks", Map.of("chunkUids", List.of("opaque-1")), "SUCCESS", Map.of())),
                        Map.of("knowledge_status", "OK", "source_count", 1), 4, null, null),
                new AgentEvaluationTrace(cancellation.caseId(), "SUCCEEDED", List.of(
                        new AgentEvaluationTrace.ExecutedToolCall("reservation_cancellation_preview", Map.of("reservationId", 5001), "SUCCESS", Map.of())),
                        Map.of("result_type", "CANCELLATION_PREVIEW", "reservationId", 5001L, "writeExecuted", false), 2, null, null)
        );

        AgentEvaluationMetrics metrics = AgentEvaluationScorer.score(List.of(direct, knowledge, cancellation), traces);

        assertThat(metrics.toolSelectionCorrect()).isEqualTo(2);
        assertThat(metrics.parameterCorrect()).isEqualTo(2);
        assertThat(metrics.taskCompleted()).isEqualTo(3);
        assertThat(metrics.falseToolCalls()).isEqualTo(1);
        assertThat(metrics.averageToolCalls()).isEqualTo(4D / 3D);
    }
}
