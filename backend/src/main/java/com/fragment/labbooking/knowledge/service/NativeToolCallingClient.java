package com.fragment.labbooking.knowledge.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fragment.labbooking.knowledge.agent.AgentConversationContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public interface NativeToolCallingClient {

    ToolRound nextRound(String question, List<Map<String, Object>> tools, List<ExecutedToolCall> executedCalls);

    default ToolRound nextRound(String question, List<Map<String, Object>> tools, List<ExecutedToolCall> executedCalls,
                                AgentConversationContext context) {
        return nextRound(question, tools, executedCalls);
    }

    record ToolRound(List<PlannedToolCall> toolCalls, String answer, String model,
                     Map<String, Object> providerUsage) {
        public ToolRound(List<PlannedToolCall> toolCalls, String answer, String model) {
            this(toolCalls, answer, model, Map.of());
        }

        public ToolRound {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            // Gateways commonly omit cache fields as null. Preserve that
            // distinction rather than turning an unknown value into zero.
            providerUsage = providerUsage == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(providerUsage));
        }
    }

    record PlannedToolCall(@JsonProperty("call_id") String callId, String name, Map<String, Object> arguments) {}

    record ExecutedToolCall(@JsonProperty("call_id") String callId, String name,
                            Map<String, Object> arguments, Map<String, Object> output) {}
}
