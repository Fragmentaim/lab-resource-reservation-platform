package com.fragment.labbooking.knowledge.agent.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Privacy-safe runtime event; raw tool outputs stay in their domain services. */
public record AgentToolExecution(String toolName, String status, int latencyMs, String toolTraceId,
                                 String protocol, Map<String, Object> detail) {

    public AgentToolExecution {
        toolName = AgentModelGuard.text(toolName);
        status = AgentModelGuard.text(status);
        toolTraceId = AgentModelGuard.text(toolTraceId);
        protocol = AgentModelGuard.text(protocol);
        detail = AgentModelGuard.map(detail);
        latencyMs = Math.max(0, latencyMs);
    }

    public Map<String, Object> safeDetail() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocol", protocol);
        result.putAll(detail);
        return result;
    }
}
