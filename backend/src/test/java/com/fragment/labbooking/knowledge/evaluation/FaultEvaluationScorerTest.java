package com.fragment.labbooking.knowledge.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FaultEvaluationScorerTest {

    @Test
    void shouldCoverAllRequestedFaultBoundariesThreeTimes() {
        assertThat(FaultEvaluationFixtures.suiteV1()).hasSize(24)
                .extracting(FaultEvaluationCase::faultType).containsExactlyInAnyOrder(
                        FaultEvaluationCase.FaultType.LLM_TIMEOUT, FaultEvaluationCase.FaultType.LLM_TIMEOUT, FaultEvaluationCase.FaultType.LLM_TIMEOUT,
                        FaultEvaluationCase.FaultType.LLM_RATE_LIMIT_429, FaultEvaluationCase.FaultType.LLM_RATE_LIMIT_429, FaultEvaluationCase.FaultType.LLM_RATE_LIMIT_429,
                        FaultEvaluationCase.FaultType.LLM_SERVER_ERROR_500, FaultEvaluationCase.FaultType.LLM_SERVER_ERROR_500, FaultEvaluationCase.FaultType.LLM_SERVER_ERROR_500,
                        FaultEvaluationCase.FaultType.TOOL_INVALID_ARGUMENT, FaultEvaluationCase.FaultType.TOOL_INVALID_ARGUMENT, FaultEvaluationCase.FaultType.TOOL_INVALID_ARGUMENT,
                        FaultEvaluationCase.FaultType.TOOL_TIMEOUT, FaultEvaluationCase.FaultType.TOOL_TIMEOUT, FaultEvaluationCase.FaultType.TOOL_TIMEOUT,
                        FaultEvaluationCase.FaultType.DUPLICATE_TOOL_CALL, FaultEvaluationCase.FaultType.DUPLICATE_TOOL_CALL, FaultEvaluationCase.FaultType.DUPLICATE_TOOL_CALL,
                        FaultEvaluationCase.FaultType.ROCKETMQ_DUPLICATE_DELIVERY, FaultEvaluationCase.FaultType.ROCKETMQ_DUPLICATE_DELIVERY, FaultEvaluationCase.FaultType.ROCKETMQ_DUPLICATE_DELIVERY,
                        FaultEvaluationCase.FaultType.SERVICE_RESTART, FaultEvaluationCase.FaultType.SERVICE_RESTART, FaultEvaluationCase.FaultType.SERVICE_RESTART);
    }

    @Test
    void shouldFailWhenAFaultCreatesDuplicateBusinessSideEffects() {
        FaultEvaluationCase testCase = FaultEvaluationFixtures.suiteV1().get(0);
        List<FaultEvaluationTrace> traces = List.of(new FaultEvaluationTrace(testCase.caseId(), true, 1, true, 1, true, true, "model_gateway"));

        FaultEvaluationMetrics metrics = FaultEvaluationScorer.score(List.of(testCase), traces);
        assertThat(metrics.automaticRecoveryRate()).isEqualTo(1D);
        assertThatThrownBy(() -> FaultEvaluationScorer.assertSideEffectSafety(List.of(testCase), traces))
                .isInstanceOf(AssertionError.class).hasMessageContaining("side effects");
    }
}
