package com.fragment.labbooking.knowledge.agent.tool;

import java.util.Collections;
import java.util.Map;

/** Privacy-safe output metadata returned from a domain tool to the agent runtime. */
public record AgentToolResult(Map<String, Object> output, int sourceCount, Map<String, Object> executionDetail) {

    public AgentToolResult {
        output = output == null ? Map.of() : Collections.unmodifiableMap(output);
        sourceCount = Math.max(0, sourceCount);
        executionDetail = executionDetail == null ? Map.of() : Collections.unmodifiableMap(executionDetail);
    }

    public static AgentToolResult of(Map<String, Object> output) {
        return new AgentToolResult(output, 0, Map.of());
    }
}
