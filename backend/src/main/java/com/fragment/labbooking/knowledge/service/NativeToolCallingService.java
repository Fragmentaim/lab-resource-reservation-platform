package com.fragment.labbooking.knowledge.service;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.knowledge.agent.AgentConversationContext;

import java.util.Optional;

public interface NativeToolCallingService {

    default Optional<ToolRouteResult> tryAnswer(String question, LoginUser actor) {
        return tryAnswer(question, actor, null, null, AgentConversationContext.empty());
    }

    default Optional<ToolRouteResult> tryAnswer(String question, LoginUser actor, String sessionId, String traceId) {
        return tryAnswer(question, actor, sessionId, traceId, AgentConversationContext.empty());
    }

    Optional<ToolRouteResult> tryAnswer(String question, LoginUser actor, String sessionId, String traceId,
                                        AgentConversationContext context);
}
