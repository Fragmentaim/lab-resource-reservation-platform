package com.fragment.labbooking.knowledge.service;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.knowledge.agent.AgentConversationContext;

/** Executes one authenticated Agent request, including any model-selected tools. */
public interface AgentChatService {

    ToolRouteResult answer(String question, LoginUser actor, String sessionId, String traceId,
                           AgentConversationContext conversationContext);
}
