package com.fragment.labbooking.knowledge.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationEvaluationScorerTest {

    @Test
    void shouldBuild30EightTurnConversationsWithRequiredReferenceAndSummaryCoverage() {
        List<ConversationEvaluationCase> cases = ConversationEvaluationFixtures.suiteV1();

        assertThat(cases).hasSize(30);
        assertThat(cases).allSatisfy(value -> assertThat(value.turns()).hasSize(16));
        assertThat(cases.stream().filter(ConversationEvaluationCase::containsReference)).hasSize(30);
        assertThat(cases.stream().filter(ConversationEvaluationCase::requiresSummary)).hasSize(18);
        assertThat(cases.stream().filter(value -> value.scenario().equals("先指定日期，后续仅说下午"))).hasSize(10);
        assertThat(cases.stream().filter(value -> value.scenario().equals("用户中途更换资源"))).hasSize(8);
        assertThat(cases.stream().filter(value -> value.scenario().equals("刚才那个、第二个等指代"))).hasSize(6);
        assertThat(cases.stream().filter(value -> value.scenario().equals("先查询预约，后续要求取消"))).hasSize(6);
    }

    @Test
    void shouldMeasureConstraintRetentionAndSummaryAccuracySeparately() {
        ConversationEvaluationCase first = ConversationEvaluationFixtures.suiteV1().get(0);
        ConversationEvaluationCase summarized = ConversationEvaluationFixtures.suiteV1().stream()
                .filter(ConversationEvaluationCase::requiresSummary).findFirst().orElseThrow();
        List<ConversationEvaluationTrace> traces = List.of(
                new ConversationEvaluationTrace(first.caseId(), first.expectedConstraints(), true, true,
                        false, 0, 0, false, false),
                new ConversationEvaluationTrace(summarized.caseId(), summarized.expectedConstraints(), true, true,
                        true, 1000, 350, true, true)
        );

        ConversationEvaluationMetrics metrics = ConversationEvaluationScorer.score(List.of(first, summarized), traces);

        assertThat(metrics.keyConstraintRetentionRate()).isEqualTo(1D);
        assertThat(metrics.referenceResolutionAccuracy()).isEqualTo(1D);
        assertThat(metrics.multiTurnTaskSuccessRate()).isEqualTo(1D);
        assertThat(metrics.summaryTriggerRate()).isEqualTo(1D);
        assertThat(metrics.historyTokenReductionRate()).isEqualTo(0.65D);
        assertThat(metrics.taskAccuracyBeforeSummary()).isEqualTo(1D);
        assertThat(metrics.taskAccuracyAfterSummary()).isEqualTo(1D);
    }
}
