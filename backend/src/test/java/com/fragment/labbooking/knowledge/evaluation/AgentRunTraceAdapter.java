package com.fragment.labbooking.knowledge.evaluation;

import com.fragment.labbooking.knowledge.vo.AgentRunVO;
import com.fragment.labbooking.knowledge.vo.AgentStepVO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts persisted, admin-only AgentRun/Step observations into evaluation traces. */
public final class AgentRunTraceAdapter {

    private AgentRunTraceAdapter() {}

    /**
     * Builds a trace using only persisted, privacy-safe Step detail.  Evaluation
     * harnesses may still pass a stricter expected-result projection through the
     * overload below, but must not re-query mutable business tables after a run.
     */
    public static AgentEvaluationTrace toTaskTrace(String caseId, AgentRunVO run, List<AgentStepVO> steps,
                                                   double modelCost) {
        return toTaskTrace(caseId, run, steps, inferBusinessResult(run, steps), modelCost);
    }

    public static AgentEvaluationTrace toTaskTrace(String caseId, AgentRunVO run, List<AgentStepVO> steps,
                                                   Map<String, Object> finalBusinessResult, double modelCost) {
        List<AgentStepVO> safeSteps = steps == null ? List.of() : steps;
        List<AgentEvaluationTrace.ExecutedToolCall> toolCalls = new ArrayList<>();
        long firstDecision = 0;
        long finalGeneration = 0;
        long retrieval = 0;
        long businessTools = 0;
        long inputTokens = 0;
        long outputTokens = 0;
        long cachedTokens = 0;
        for (AgentStepVO step : safeSteps) {
            Map<String, Object> detail = step.getDetail() == null ? Map.of() : step.getDetail();
            if ("PLAN".equals(step.getStepType()) && "model_tool_plan".equals(step.getName())) {
                long latency = longValue(detail.get("model_decision_latency_ms"));
                if (firstDecision == 0) firstDecision = latency;
                Object requested = detail.get("requested_tools");
                if (requested instanceof List<?> calls && calls.isEmpty()) finalGeneration = latency;
                Map<String, Object> usage = mapValue(detail.get("provider_usage"));
                inputTokens += longValue(usage.get("input_tokens"));
                outputTokens += longValue(usage.get("output_tokens"));
                cachedTokens += longValue(usage.get("cached_input_tokens"));
            }
            if (!"TOOL_CALL".equals(step.getStepType())) continue;
            Map<String, Object> arguments = mapValue(detail.get("evaluation_arguments"));
            toolCalls.add(new AgentEvaluationTrace.ExecutedToolCall(step.getName(), arguments, step.getStatus(), Map.of()));
            if ("knowledge_search".equals(step.getName()) || "knowledge_open_chunks".equals(step.getName())) {
                retrieval += safeLatency(step.getLatencyMs());
            } else {
                businessTools += safeLatency(step.getLatencyMs());
            }
        }
        long total = run == null ? 0 : safeLatency(run.getTotalLatencyMs());
        long accounted = firstDecision + finalGeneration + retrieval + businessTools;
        long databaseAndNetwork = Math.max(0, total - accounted);
        Map<String, Object> business = new LinkedHashMap<>(finalBusinessResult == null ? Map.of() : finalBusinessResult);
        if (run != null) business.putIfAbsent("source_count", safeLatency(run.getSourceCount()));
        return new AgentEvaluationTrace(caseId, run == null ? "MISSING" : run.getStatus(), toolCalls, business,
                run == null ? 0 : safeLatency(run.getStepCount()),
                new AgentEvaluationTrace.Timing(total, firstDecision, retrieval, businessTools, finalGeneration, databaseAndNetwork),
                new AgentEvaluationTrace.Usage(inputTokens, outputTokens, cachedTokens, Math.max(0D, modelCost)));
    }

    /** Reconstructs stable tool outcome fields that are deliberately persisted in AgentStep.detail. */
    public static Map<String, Object> inferBusinessResult(AgentRunVO run, List<AgentStepVO> steps) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<AgentStepVO> safeSteps = steps == null ? List.of() : steps;
        for (AgentStepVO step : safeSteps) {
            if (!"TOOL_CALL".equals(step.getStepType()) || !"SUCCESS".equals(step.getStatus())) {
                continue;
            }
            Map<String, Object> detail = step.getDetail() == null ? Map.of() : step.getDetail();
            copyIfPresent(detail, result, "result_type");
            copyIfPresent(detail, result, "actor_user_id");
            copyIfPresent(detail, result, "reservationId");
            copyIfPresent(detail, result, "writeExecuted");
            copyIfPresent(detail, result, "keyword");
            copyIfPresent(detail, result, "knowledge_status");
        }
        if (run != null) {
            result.put("source_count", safeLatency(run.getSourceCount()));
        }
        return Map.copyOf(result);
    }

    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> converted = new LinkedHashMap<>();
        raw.forEach((key, item) -> converted.put(String.valueOf(key), item));
        return Map.copyOf(converted);
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) return Math.max(0L, number.longValue());
        try { return value == null ? 0L : Math.max(0L, Long.parseLong(String.valueOf(value))); }
        catch (NumberFormatException ignored) { return 0L; }
    }

    private static int safeLatency(Integer value) { return value == null ? 0 : Math.max(0, value); }
}
