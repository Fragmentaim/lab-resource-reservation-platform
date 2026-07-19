package com.fragment.labbooking.knowledge.agent;

import com.fragment.labbooking.knowledge.service.NativeToolCallingClient;

import java.util.List;
import java.util.Map;

/**
 * A structured, auditable view of the model's decision for one tool round.
 * It is a proposal only; the server validates every selected tool and argument.
 */
public record ContextPlan(int round, String model, List<String> requestedTools) {

    public static ContextPlan from(int round, NativeToolCallingClient.ToolRound result) {
        List<String> tools = result.toolCalls() == null ? List.of() : result.toolCalls().stream()
                .map(NativeToolCallingClient.PlannedToolCall::name)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
        return new ContextPlan(round, result.model() == null ? "" : result.model(), tools);
    }

    public Map<String, Object> safeDetail() {
        return Map.of("round", round, "model", model, "requested_tools", requestedTools,
                "requested_tool_count", requestedTools.size());
    }
}
