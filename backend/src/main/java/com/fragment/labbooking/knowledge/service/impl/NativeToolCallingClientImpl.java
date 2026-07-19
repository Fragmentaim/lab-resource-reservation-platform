package com.fragment.labbooking.knowledge.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.knowledge.agent.AgentConversationContext;
import com.fragment.labbooking.knowledge.service.NativeToolCallingClient;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class NativeToolCallingClientImpl implements NativeToolCallingClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public NativeToolCallingClientImpl(RestClient aiServiceRestClient, ObjectMapper objectMapper) {
        this.restClient = aiServiceRestClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolRound nextRound(String question, List<Map<String, Object>> tools, List<ExecutedToolCall> executedCalls) {
        return nextRound(question, tools, executedCalls, AgentConversationContext.empty());
    }

    @Override
    public ToolRound nextRound(String question, List<Map<String, Object>> tools, List<ExecutedToolCall> executedCalls,
                               AgentConversationContext context) {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("question", question);
            request.put("tools", tools);
            request.put("executed_calls", executedCalls);
            request.put("conversation_context", Map.of(
                    "working_memory", context == null ? "" : context.workingMemory(),
                    "history", context == null ? List.of() : context.history()
            ));
            String raw = restClient.post()
                    .uri("/api/v1/ai/tool-calling/round")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(request))
                    .retrieve()
                    .body(String.class);
            JsonNode body = objectMapper.readTree(raw);
            List<PlannedToolCall> calls = body.path("tool_calls").isArray()
                    ? objectMapper.convertValue(body.path("tool_calls"), new TypeReference<List<PlannedToolCall>>() {})
                    : Collections.emptyList();
            return new ToolRound(calls, textOrNull(body, "answer"), textOrNull(body, "model"));
        } catch (Exception exception) {
            throw new BusinessException("原生工具调用规划失败: " + exception.getMessage());
        }
    }

    private String textOrNull(JsonNode body, String field) {
        JsonNode value = body.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
