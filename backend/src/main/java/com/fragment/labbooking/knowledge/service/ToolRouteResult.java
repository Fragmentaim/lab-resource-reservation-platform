package com.fragment.labbooking.knowledge.service;

import java.util.List;
import java.util.Map;

public record ToolRouteResult(String answer, List<Map<String, Object>> toolCalls, boolean runtimeManaged,
                              int sourceCount) {

    public ToolRouteResult(String answer, List<Map<String, Object>> toolCalls) {
        this(answer, toolCalls, false, 0);
    }
}
