package com.fragment.labbooking.knowledge.agent;

import com.fragment.labbooking.knowledge.service.NativeToolCallingClient;

import java.util.List;
import java.util.Map;

/**
 * A structured, auditable view of the model's decision for one tool round.
 * It is a proposal only; the server validates every selected tool and argument.
 */
public record ContextPlan(int round, String model, List<String> requestedTools, Map<String, Object> providerUsage) {

    public ContextPlan(int round, String model, List<String> requestedTools) {
        this(round, model, requestedTools, Map.of());
    }

    public static ContextPlan from(int round, NativeToolCallingClient.ToolRound result) {
        List<String> tools = result.toolCalls() == null ? List.of() : result.toolCalls().stream()
                .map(NativeToolCallingClient.PlannedToolCall::name)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
        return new ContextPlan(round, result.model() == null ? "" : result.model(), tools, result.providerUsage());
    }

    public Map<String, Object> safeDetail() {
        Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("round", round);
        detail.put("model", model);
        detail.put("requested_tools", requestedTools);
        detail.put("requested_tool_count", requestedTools.size());
        detail.put("provider_usage", providerUsage == null ? Map.of("reported", false) : providerUsage);
        return detail;
    }
}
