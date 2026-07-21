package com.fragment.labbooking.knowledge.evaluation;

import java.util.Map;

/** Model latency and service latency remain separate to avoid presenting provider time as backend performance. */
public record AgentPerformanceMetrics(
        int requestCount,
        int successCount,
        long totalLatencyP50Ms,
        long totalLatencyP95Ms,
        long totalLatencyP99Ms,
        long firstModelDecisionP50Ms,
        long retrievalP50Ms,
        long businessToolP50Ms,
        long finalModelGenerationP50Ms,
        double averageToolCalls,
        long inputTokens,
        long outputTokens,
        long cachedTokens,
        double totalModelCost,
        double costPerSuccessfulTask,
        Map<Integer, Double> errorRateByConcurrency
) {
    public double successRate() { return requestCount == 0 ? 0D : (double) successCount / requestCount; }
}
