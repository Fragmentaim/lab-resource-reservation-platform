package com.fragment.labbooking.knowledge.evaluation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class AgentPerformanceScorer {

    private AgentPerformanceScorer() {}

    public static AgentPerformanceMetrics score(List<AgentPerformanceTrace> traces) {
        List<AgentPerformanceTrace> inputs = traces == null ? List.of() : List.copyOf(traces);
        List<Long> total = inputs.stream().map(value -> value.timing().totalMs()).toList();
        List<Long> firstDecision = inputs.stream().map(value -> value.timing().firstModelDecisionMs()).toList();
        List<Long> retrieval = inputs.stream().map(value -> value.timing().retrievalMs()).toList();
        List<Long> tool = inputs.stream().map(value -> value.timing().businessToolMs()).toList();
        List<Long> finalGeneration = inputs.stream().map(value -> value.timing().finalModelGenerationMs()).toList();
        int success = (int) inputs.stream().filter(AgentPerformanceTrace::succeeded).count();
        int calls = inputs.stream().mapToInt(AgentPerformanceTrace::executedToolCalls).sum();
        long inputTokens = inputs.stream().mapToLong(value -> value.usage().inputTokens()).sum();
        long outputTokens = inputs.stream().mapToLong(value -> value.usage().outputTokens()).sum();
        long cachedTokens = inputs.stream().mapToLong(value -> value.usage().cachedTokens()).sum();
        double cost = inputs.stream().mapToDouble(value -> value.usage().modelCost()).sum();
        Map<Integer, Double> errors = errorRateByConcurrency(inputs);
        return new AgentPerformanceMetrics(inputs.size(), success, percentile(total, .50D), percentile(total, .95D), percentile(total, .99D),
                percentile(firstDecision, .50D), percentile(retrieval, .50D), percentile(tool, .50D), percentile(finalGeneration, .50D),
                inputs.isEmpty() ? 0D : (double) calls / inputs.size(), inputTokens, outputTokens, cachedTokens, cost,
                success == 0 ? 0D : cost / success, errors);
    }

    /** Nearest-rank percentile, documented so repeated benchmark runs are comparable. */
    static long percentile(List<Long> values, double percentile) {
        if (values == null || values.isEmpty()) return 0L;
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(index);
    }

    private static Map<Integer, Double> errorRateByConcurrency(List<AgentPerformanceTrace> traces) {
        Map<Integer, List<AgentPerformanceTrace>> grouped = traces.stream()
                .collect(Collectors.groupingBy(AgentPerformanceTrace::concurrency, LinkedHashMap::new, Collectors.toList()));
        Map<Integer, Double> result = new LinkedHashMap<>();
        grouped.forEach((concurrency, values) -> {
            long failures = values.stream().filter(value -> !value.succeeded()).count();
            result.put(concurrency, values.isEmpty() ? 0D : (double) failures / values.size());
        });
        return Map.copyOf(result);
    }
}
