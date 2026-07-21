package com.fragment.labbooking.knowledge.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/** Privacy-safe runtime event; raw tool outputs stay in their domain services. */
public record AgentToolExecution(String toolName, String status, int latencyMs, String toolTraceId,
                                 String protocol, Map<String, Object> detail) {

    public Map<String, Object> safeDetail() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocol", protocol);
        if (detail != null) {
            result.putAll(detail);
        }
        return result;
    }
}
