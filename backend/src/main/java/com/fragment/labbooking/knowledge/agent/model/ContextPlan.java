package com.fragment.labbooking.knowledge.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;

/**
 * A structured, auditable view of the model's decision for one tool round.
 * It is a proposal only; the server validates every selected tool and argument.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ContextPlan(int round, String model, List<String> requestedTools,
                          @JsonIgnore Map<String, Object> providerUsage,
                          int modelDecisionLatencyMs) {

    public ContextPlan {
        round = Math.max(0, round);
        model = AgentModelGuard.text(model);
        requestedTools = AgentModelGuard.list(requestedTools);
        providerUsage = AgentModelGuard.map(providerUsage);
        modelDecisionLatencyMs = Math.max(0, modelDecisionLatencyMs);
    }

    public ContextPlan(int round, String model, List<String> requestedTools) {
        this(round, model, requestedTools, Map.of(), 0);
    }

    public ContextPlan(int round, String model, List<String> requestedTools, Map<String, Object> providerUsage) {
        this(round, model, requestedTools, providerUsage, 0);
    }

    @JsonProperty("requested_tool_count")
    public int requestedToolCount() {
        return requestedTools.size();
    }

    @JsonProperty("provider_usage")
    public Map<String, Object> observableProviderUsage() {
        return providerUsage.isEmpty() ? Map.of("reported", false) : providerUsage;
    }
}
