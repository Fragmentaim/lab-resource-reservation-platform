package com.fragment.labbooking.knowledge.service;

import com.fragment.labbooking.knowledge.vo.QaSourceVO;

import java.util.List;
import java.util.Map;

public record ToolRouteResult(String answer, List<Map<String, Object>> toolCalls, boolean runtimeManaged,
                              int sourceCount, List<QaSourceVO> sources, Map<String, Object> providerUsage,
                              List<Map<String, Object>> clientActions) {

    public ToolRouteResult {
        sources = sources == null ? List.of() : List.copyOf(sources);
        providerUsage = providerUsage == null ? Map.of("reported", false) : Map.copyOf(providerUsage);
        clientActions = clientActions == null ? List.of() : clientActions.stream()
                .map(action -> Map.copyOf(action == null ? Map.of() : action))
                .toList();
    }

    public ToolRouteResult(String answer, List<Map<String, Object>> toolCalls, boolean runtimeManaged,
                           int sourceCount, List<QaSourceVO> sources, Map<String, Object> providerUsage) {
        this(answer, toolCalls, runtimeManaged, sourceCount, sources, providerUsage, List.of());
    }

    public ToolRouteResult(String answer, List<Map<String, Object>> toolCalls, boolean runtimeManaged,
                           int sourceCount) {
        this(answer, toolCalls, runtimeManaged, sourceCount, List.of(), Map.of("reported", false), List.of());
    }

    public ToolRouteResult(String answer, List<Map<String, Object>> toolCalls) {
        this(answer, toolCalls, false, 0, List.of(), Map.of("reported", false), List.of());
    }
}
