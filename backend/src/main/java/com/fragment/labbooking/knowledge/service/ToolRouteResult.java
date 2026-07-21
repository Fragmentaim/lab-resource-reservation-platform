package com.fragment.labbooking.knowledge.service;

import com.fragment.labbooking.knowledge.vo.QaSourceVO;

import java.util.List;
import java.util.Map;

public record ToolRouteResult(String answer, List<Map<String, Object>> toolCalls, boolean runtimeManaged,
                              int sourceCount, List<QaSourceVO> sources) {

    public ToolRouteResult {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public ToolRouteResult(String answer, List<Map<String, Object>> toolCalls, boolean runtimeManaged,
                           int sourceCount) {
        this(answer, toolCalls, runtimeManaged, sourceCount, List.of());
    }

    public ToolRouteResult(String answer, List<Map<String, Object>> toolCalls) {
        this(answer, toolCalls, false, 0, List.of());
    }
}
