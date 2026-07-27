package com.fragment.labbooking.knowledge.agent;

import java.util.List;
import java.util.Map;

/**
 * A structured, auditable view of the model's decision for one tool round.
 * It is a proposal only; the server validates every selected tool and argument.
 */
public record ContextPlan(int round, String model, List<String> requestedTools, Map<String, Object> providerUsage,
                          int modelDecisionLatencyMs) {

    public ContextPlan(int round, String model, List<String> requestedTools) {
        this(round, model, requestedTools, Map.of(), 0);
    }

    public ContextPlan(int round, String model, List<String> requestedTools, Map<String, Object> providerUsage) {
        this(round, model, requestedTools, providerUsage, 0);
    }

    public Map<String, Object> safeDetail() {
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("round", round);
        detail.put("model", model);
        detail.put("requested_tools", requestedTools);
        detail.put("requested_tool_count", requestedTools.size());
        detail.put("provider_usage", providerUsage == null ? Map.of("reported", false) : providerUsage);
        detail.put("model_decision_latency_ms", modelDecisionLatencyMs);
        return detail;
    }
}
