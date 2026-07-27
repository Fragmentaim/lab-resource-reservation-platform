package com.fragment.labbooking.knowledge.agent.tool;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.knowledge.agent.AgentState;

import java.util.Collections;
import java.util.Map;

/** Runtime-owned inputs passed to a selected tool after the model call. */
public record AgentToolInvocation(LoginUser actor, String originalQuestion, AgentState state,
                                  Map<String, Object> arguments) {

    public AgentToolInvocation {
        arguments = arguments == null ? Map.of() : Collections.unmodifiableMap(arguments);
    }
}
