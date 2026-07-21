package com.fragment.labbooking.knowledge.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPerformanceScorerTest {

    @Test
    void shouldKeepModelTimingCostAndConcurrencyErrorsSeparate() {
        List<AgentPerformanceTrace> traces = List.of(
                trace("a", 10, true, 100, 30, 10, 20, 40, 2, 100, 20, .02D, 1),
                trace("b", 10, false, 200, 50, 20, 30, 80, 3, 120, 30, .03D, 2),
                trace("c", 30, true, 300, 60, 30, 40, 120, 4, 140, 40, .04D, 3)
        );

        AgentPerformanceMetrics metrics = AgentPerformanceScorer.score(traces);

        assertThat(metrics.totalLatencyP50Ms()).isEqualTo(200);
        assertThat(metrics.totalLatencyP95Ms()).isEqualTo(300);
        assertThat(metrics.firstModelDecisionP50Ms()).isEqualTo(50);
        assertThat(metrics.averageToolCalls()).isEqualTo(2D);
        assertThat(metrics.costPerSuccessfulTask()).isEqualTo(.045D);
        assertThat(metrics.errorRateByConcurrency()).containsEntry(10, .5D).containsEntry(30, 0D);
    }

    private AgentPerformanceTrace trace(String id, int concurrency, boolean success, long total, long decision,
                                        long retrieval, long tool, long finalGeneration, int input, long output,
                                        long cached, double cost, int calls) {
        return new AgentPerformanceTrace(id, concurrency, success,
                new AgentEvaluationTrace.Timing(total, decision, retrieval, tool, finalGeneration, 0),
                new AgentEvaluationTrace.Usage(input, output, cached, cost), calls);
    }
}
