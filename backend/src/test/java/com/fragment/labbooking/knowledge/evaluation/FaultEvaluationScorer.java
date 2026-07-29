package com.fragment.labbooking.knowledge.evaluation;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class FaultEvaluationScorer {

    private FaultEvaluationScorer() {}

    public static FaultEvaluationMetrics score(List<FaultEvaluationCase> cases, List<FaultEvaluationTrace> traces) {
        Map<String, FaultEvaluationTrace> byId = (traces == null ? List.<FaultEvaluationTrace>of() : traces).stream()
                .collect(Collectors.toMap(FaultEvaluationTrace::caseId, Function.identity(), (first, ignored) -> first));
        int total = 0, recovered = 0, succeeded = 0, retries = 0, sideEffects = 0, runs = 0, steps = 0;
        for (FaultEvaluationCase testCase : cases == null ? List.<FaultEvaluationCase>of() : cases) {
            total++;
            FaultEvaluationTrace trace = byId.get(testCase.caseId());
            if (trace == null) continue;
            if (trace.automaticallyRecovered()) recovered++;
            if (trace.finalTaskSucceeded()) succeeded++;
            retries += Math.max(0, trace.retryCount());
            sideEffects += Math.max(0, trace.duplicateSideEffectCount());
            if (trace.agentRunRecorded()) runs++;
            if (trace.failingStepRecorded()) steps++;
        }
        return new FaultEvaluationMetrics(total, recovered, succeeded, retries, sideEffects, runs, steps);
    }

    public static void assertSideEffectSafety(List<FaultEvaluationCase> cases, List<FaultEvaluationTrace> traces) {
        FaultEvaluationMetrics metrics = score(cases, traces);
        if (metrics.duplicateSideEffects() != 0) {
            throw new AssertionError("duplicate business side effects detected: " + metrics.duplicateSideEffects());
        }
    }
}
