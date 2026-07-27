package com.fragment.labbooking.knowledge.agent.tool;

import com.fragment.labbooking.knowledge.vo.QaSourceVO;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Immutable output returned from a domain tool to the agent runtime. */
public record AgentToolResult(Map<String, Object> output, List<QaSourceVO> sources,
                              Map<String, Object> executionDetail) {

    public AgentToolResult {
        output = output == null ? Map.of() : Collections.unmodifiableMap(output);
        sources = sources == null ? List.of() : List.copyOf(sources);
        executionDetail = executionDetail == null ? Map.of() : Collections.unmodifiableMap(executionDetail);
    }

    public static AgentToolResult of(Map<String, Object> output) {
        return new AgentToolResult(output, List.of(), Map.of());
    }

    public static AgentToolResult of(Map<String, Object> output, Map<String, Object> executionDetail) {
        return new AgentToolResult(output, List.of(), executionDetail);
    }

    public static AgentToolResult withSources(Map<String, Object> output, List<QaSourceVO> sources,
                                              Map<String, Object> executionDetail) {
        return new AgentToolResult(output, sources, executionDetail);
    }

}
