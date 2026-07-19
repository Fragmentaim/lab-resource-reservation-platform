package com.fragment.labbooking.knowledge.service;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public interface NativeToolCallingClient {

    ToolRound nextRound(String question, List<Map<String, Object>> tools, List<ExecutedToolCall> executedCalls);

    record ToolRound(List<PlannedToolCall> toolCalls, String answer, String model) {}

    record PlannedToolCall(@JsonProperty("call_id") String callId, String name, Map<String, Object> arguments) {}

    record ExecutedToolCall(@JsonProperty("call_id") String callId, String name,
                            Map<String, Object> arguments, Map<String, Object> output) {}
}
