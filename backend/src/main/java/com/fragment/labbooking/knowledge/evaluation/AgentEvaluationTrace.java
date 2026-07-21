package com.fragment.labbooking.knowledge.evaluation;

import java.util.List;
import java.util.Map;

/** Actual result collected from one real AgentRun/AgentStep execution. */
public record AgentEvaluationTrace(
        String caseId,
        String finalStatus,
        List<ExecutedToolCall> toolCalls,
        Map<String, Object> businessResult,
        int agentStepCount,
        Timing timing,
        Usage usage
) {
    public AgentEvaluationTrace {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        businessResult = businessResult == null ? Map.of() : Map.copyOf(businessResult);
        timing = timing == null ? Timing.empty() : timing;
        usage = usage == null ? Usage.empty() : usage;
    }

    public record ExecutedToolCall(String toolName, Map<String, Object> arguments,
                                   String status, Map<String, Object> output) {
        public ExecutedToolCall {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
            output = output == null ? Map.of() : Map.copyOf(output);
        }
    }

    /** All times are wall-clock milliseconds from a single trace. */
    public record Timing(long totalMs, long firstModelDecisionMs, long retrievalMs,
                         long businessToolMs, long finalModelGenerationMs, long databaseAndNetworkMs) {
        public static Timing empty() { return new Timing(0, 0, 0, 0, 0, 0); }
    }

    /** Provider-reported values; a missing provider report is represented by zero rather than guessed. */
    public record Usage(long inputTokens, long outputTokens, long cachedTokens, double modelCost) {
        public static Usage empty() { return new Usage(0, 0, 0, 0D); }
    }
}
