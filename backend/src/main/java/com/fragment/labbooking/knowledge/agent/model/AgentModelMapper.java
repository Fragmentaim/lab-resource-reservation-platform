package com.fragment.labbooking.knowledge.agent.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Converts agent records to their JSON-shaped tool and observability projections. */
@Component
public class AgentModelMapper {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    public AgentModelMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> detail(ContextPlan plan) {
        return toMap(plan);
    }

    public Map<String, Object> detail(SessionContextPlan plan) {
        return toMap(plan);
    }

    public Map<String, Object> detail(AgentToolExecution execution) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocol", execution.protocol());
        result.putAll(execution.detail());
        return result;
    }

    public Map<String, Object> attributes(PolicyContext policy) {
        return toMap(policy);
    }

    public Map<String, Object> toolPayload(EvidenceCard evidence) {
        return toMap(evidence);
    }

    private Map<String, Object> toMap(Object value) {
        return value == null ? Map.of() : objectMapper.convertValue(value, MAP_TYPE);
    }
}
