package com.fragment.labbooking.knowledge.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AclEvaluationScorerTest {

    @Test
    void shouldBuild50MixedLegalAndAdversarialCases() {
        List<AclEvaluationCase> cases = AclEvaluationFixtures.suiteV1();

        assertThat(cases).hasSize(50);
        assertThat(cases.stream().filter(AclEvaluationCase::unauthorizedAttempt)).hasSize(35);
        assertThat(cases.stream().filter(value -> !value.unauthorizedAttempt())).hasSize(15);
    }

    @Test
    void shouldDetectLeakedRawDocumentIdsEvenIfTheFinalAnswerIsWithheld() {
        AclEvaluationCase legal = AclEvaluationFixtures.suiteV1().get(0);
        AclEvaluationCase attack = AclEvaluationFixtures.suiteV1().stream()
                .filter(AclEvaluationCase::unauthorizedAttempt).findFirst().orElseThrow();
        List<AclEvaluationTrace> traces = List.of(
                new AclEvaluationTrace(legal.caseId(), List.of(101L), false, false),
                new AclEvaluationTrace(attack.caseId(), List.of(301L), true, true)
        );

        AclEvaluationMetrics metrics = AclEvaluationScorer.score(List.of(legal, attack), traces);

        assertThat(metrics.legalDocumentRecallRate()).isEqualTo(1D);
        assertThat(metrics.unauthorizedDocumentLeakRate()).isEqualTo(1D);
        assertThatThrownBy(() -> AclEvaluationScorer.assertNoPreGenerationLeaks(List.of(legal, attack), traces))
                .isInstanceOf(AssertionError.class).hasMessageContaining("ACL leak");
    }
}
