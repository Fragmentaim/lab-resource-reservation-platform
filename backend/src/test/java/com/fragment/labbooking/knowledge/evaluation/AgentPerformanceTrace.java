package com.fragment.labbooking.knowledge.evaluation;

/** One completed or failed end-to-end agent request, tagged with the load level used to create it. */
public record AgentPerformanceTrace(
        String traceId,
        int concurrency,
        boolean succeeded,
        AgentEvaluationTrace.Timing timing,
        AgentEvaluationTrace.Usage usage,
        int executedToolCalls
) {
    public AgentPerformanceTrace {
        timing = timing == null ? AgentEvaluationTrace.Timing.empty() : timing;
        usage = usage == null ? AgentEvaluationTrace.Usage.empty() : usage;
    }
}
