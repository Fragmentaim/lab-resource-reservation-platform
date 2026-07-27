package com.fragment.labbooking.knowledge.agent.model;

import com.fragment.labbooking.knowledge.service.AiServiceClient;

import java.util.List;

/** Context supplied to the native tool-planning model for the active session only. */
public record AgentConversationContext(String workingMemory, List<AiServiceClient.ChatMessage> history) {
    public AgentConversationContext {
        workingMemory = workingMemory == null ? "" : workingMemory;
        history = history == null ? List.of() : List.copyOf(history);
    }

    public static AgentConversationContext empty() {
        return new AgentConversationContext("", List.of());
    }
}
