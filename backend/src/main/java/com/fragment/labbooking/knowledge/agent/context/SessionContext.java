package com.fragment.labbooking.knowledge.agent.context;

import com.fragment.labbooking.knowledge.service.AiServiceClient;

import java.util.List;
import java.util.Map;

/** The single prepared input for one Agent request. */
public record SessionContext(
        String sessionId,
        String workingMemory,
        List<AiServiceClient.ChatMessage> history,
        SessionContextPlanner.Plan plan,
        Map<String, Object> summaryProviderUsage
) {
    public SessionContext {
        sessionId = sessionId == null ? "" : sessionId;
        workingMemory = workingMemory == null ? "" : workingMemory;
        history = history == null ? List.of() : List.copyOf(history);
        summaryProviderUsage = summaryProviderUsage == null ? Map.of() : Map.copyOf(summaryProviderUsage);
    }

    public static SessionContext empty() {
        return new SessionContext("", "", List.of(), null, Map.of());
    }
}
