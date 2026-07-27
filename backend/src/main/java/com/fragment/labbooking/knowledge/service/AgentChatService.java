package com.fragment.labbooking.knowledge.service;

import com.fragment.labbooking.common.auth.LoginUser;

import java.util.List;

/** Executes one authenticated Agent request, including any model-selected tools. */
public interface AgentChatService {

    ToolRouteResult answer(String question, LoginUser actor, String sessionId, String traceId,
                           ConversationContext conversationContext);

    record ConversationContext(String workingMemory, List<AiServiceClient.ChatMessage> history) {
        public ConversationContext {
            workingMemory = workingMemory == null ? "" : workingMemory;
            history = history == null ? List.of() : List.copyOf(history);
        }

        public static ConversationContext empty() {
            return new ConversationContext("", List.of());
        }
    }
}
