package com.fragment.labbooking.knowledge.agent.tool;

import com.fragment.labbooking.knowledge.agent.PolicyContext;

import java.util.Map;

/**
 * A server-owned capability that may be exposed to the model for one agent run.
 * Implementations own their parameter validation and domain authorization; the
 * model only chooses from definitions produced by {@link AgentToolRegistry}.
 */
public interface AgentTool {

    String name();

    String accessScope();

    Map<String, Object> definition();

    default boolean isAvailableFor(PolicyContext policy) {
        return policy != null && policy.userId() != null;
    }

    AgentToolResult execute(AgentToolInvocation invocation);
}
