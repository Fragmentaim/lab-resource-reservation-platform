package com.fragment.labbooking.knowledge.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.common.util.TruncateUtil;
import com.fragment.labbooking.knowledge.agent.AgentExecutionContext;
import com.fragment.labbooking.knowledge.agent.AgentToolExecution;
import com.fragment.labbooking.knowledge.agent.ToolResultContextPacker;
import com.fragment.labbooking.knowledge.service.AgentRunService;
import com.fragment.labbooking.knowledge.service.AiToolCallAuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Executes one model-selected domain tool and records its auditable result. */
@Component
public class AgentToolExecutor {

    private final ObjectMapper objectMapper;
    private final ToolResultContextPacker contextPacker;
    private final AiToolCallAuditService auditService;
    private final AgentRunService runService;
    private final boolean captureArguments;

    public AgentToolExecutor(ObjectMapper objectMapper, ToolResultContextPacker contextPacker,
                             AiToolCallAuditService auditService, AgentRunService runService,
                             @Value("${app.knowledge.agent-evaluation.capture-arguments:false}")
                             boolean captureArguments) {
        this.objectMapper = objectMapper;
        this.contextPacker = contextPacker;
        this.auditService = auditService;
        this.runService = runService;
        this.captureArguments = captureArguments;
    }

    public String execute(AgentTool tool, String jsonInput, AgentExecutionContext context) {
        Map<String, Object> arguments = parseArguments(jsonInput);
        int callNumber = context.beginToolCall();
        String toolTraceId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();

        context.state().planning(callNumber);
        recordState(context);
        context.state().executingTool();
        recordState(context);

        try {
            AgentToolResult result = tool.execute(new AgentToolInvocation(
                    context.actor(), context.question(), context.state(), arguments));
            long latencyMs = elapsedMs(startedAt);
            auditService.recordSuccess(toolTraceId, tool.name(), context.actor(), context.actor().getId(),
                    tool.accessScope(), latencyMs, summarize(arguments));

            ToolResultContextPacker.PackedToolResult packed = contextPacker.pack(tool.name(), result.output());
            Map<String, Object> trace = trace(tool.name(), toolTraceId, latencyMs, "SUCCESS", arguments);
            context.addToolResult(trace, result.sources(), reservationDraftAction(tool.name(), result.output()));
            recordExecution(context, tool.name(), toolTraceId, latencyMs, "SUCCESS", result.output(),
                    result.executionDetail(), packed.safeDetail(), arguments);
            context.state().completeTool(tool.name());
            recordState(context);
            return objectMapper.writeValueAsString(packed.modelOutput());
        } catch (BusinessException exception) {
            long latencyMs = elapsedMs(startedAt);
            auditService.recordFailure(toolTraceId, tool.name(), context.actor(), context.actor().getId(), latencyMs,
                    summarize(arguments), exception.getMessage());
            Map<String, Object> rejected = Map.of(
                    "error", "TOOL_EXECUTION_REJECTED",
                    "message", exception.getMessage());
            context.addToolResult(trace(tool.name(), toolTraceId, latencyMs, "REJECTED", arguments), List.of(), null);
            recordExecution(context, tool.name(), toolTraceId, latencyMs, "REJECTED", rejected,
                    Map.of(), Map.of("strategy", "DIRECT"), arguments);
            context.state().completeTool(tool.name());
            recordState(context);
            return writeJson(rejected);
        } catch (RuntimeException exception) {
            long latencyMs = elapsedMs(startedAt);
            auditService.recordFailure(toolTraceId, tool.name(), context.actor(), context.actor().getId(), latencyMs,
                    summarize(arguments), exception.getMessage());
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("工具结果序列化失败: " + exception.getMessage());
        }
    }

    private Map<String, Object> parseArguments(String jsonInput) {
        if (jsonInput == null || jsonInput.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(jsonInput, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new BusinessException("工具参数不是合法 JSON");
        }
    }

    private void recordExecution(AgentExecutionContext context, String toolName, String toolTraceId,
                                 long latencyMs, String status, Map<String, Object> output,
                                 Map<String, Object> toolDetail, Map<String, Object> packing,
                                 Map<String, Object> arguments) {
        if (!context.runtimeManaged()) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("result", status);
        if (toolDetail != null) {
            detail.putAll(toolDetail);
        }
        detail.put("context_pack", packing);
        if ("knowledge_search".equals(toolName) || "knowledge_open_chunks".equals(toolName)) {
            detail.putIfAbsent("candidate_count", listSize(output.get("candidates")));
            detail.putIfAbsent("opened_chunk_count", listSize(output.get("chunks")));
            detail.putIfAbsent("knowledge_status", String.valueOf(output.getOrDefault("status", "")));
        }
        if (captureArguments) {
            detail.put("evaluation_arguments", sanitize(arguments));
        }
        runService.recordToolExecution(context.traceId(), new AgentToolExecution(
                toolName, status, (int) Math.min(Integer.MAX_VALUE, latencyMs), toolTraceId,
                "spring_ai_tool_callback", detail));
    }

    private void recordState(AgentExecutionContext context) {
        if (context.runtimeManaged()) {
            runService.recordRuntimeState(context.traceId(), context.state());
        }
    }

    private Map<String, Object> trace(String toolName, String toolTraceId, long latencyMs, String result,
                                      Map<String, Object> arguments) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("tool_name", toolName);
        trace.put("tool_trace_id", toolTraceId);
        trace.put("latency_ms", latencyMs);
        trace.put("result", result);
        trace.put("protocol", "spring_ai_tool_callback");
        if (captureArguments) {
            trace.put("evaluation_arguments", sanitize(arguments));
        }
        return trace;
    }

    private Map<String, Object> reservationDraftAction(String toolName, Map<String, Object> output) {
        if (!"reservation_create_draft".equals(toolName) || output == null) {
            return null;
        }
        Object token = output.get("confirmationToken");
        Object endpoint = output.get("confirmationEndpoint");
        if (!(token instanceof String confirmationToken) || confirmationToken.isBlank()
                || !(endpoint instanceof String confirmationEndpoint) || confirmationEndpoint.isBlank()) {
            return null;
        }
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "RESERVATION_CONFIRMATION");
        action.put("label", "确认预约");
        action.put("confirmationToken", confirmationToken);
        action.put("confirmationEndpoint", confirmationEndpoint);
        for (String field : List.of("resourceId", "resourceName", "location", "slotId", "startDatetime",
                "endDatetime", "remainQuota", "expiresAt")) {
            if (output.get(field) != null) {
                action.put(field, output.get(field));
            }
        }
        return action;
    }

    private Map<String, Object> sanitize(Map<String, Object> arguments) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (arguments != null) {
            arguments.forEach((key, value) -> safe.put(key, sanitizeValue(key, value)));
        }
        return safe;
    }

    private Object sanitizeValue(String key, Object value) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        if (List.of("password", "token", "secret", "authorization", "credential", "privatekey", "accesskey")
                .stream().anyMatch(normalized::contains)) {
            return "<redacted>";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            map.forEach((nestedKey, nestedValue) -> safe.put(String.valueOf(nestedKey),
                    sanitizeValue(String.valueOf(nestedKey), nestedValue)));
            return safe;
        }
        if (value instanceof Iterable<?> values) {
            List<Object> safe = new ArrayList<>();
            values.forEach(item -> safe.add(sanitizeValue("", item)));
            return safe;
        }
        return value instanceof String text ? TruncateUtil.truncate(text, 240) : value;
    }

    private String summarize(Map<String, Object> arguments) {
        return TruncateUtil.truncate(String.valueOf(sanitize(arguments)), 512);
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return "{\"error\":\"TOOL_RESULT_SERIALIZATION_FAILED\"}";
        }
    }

    private int listSize(Object value) { return value instanceof List<?> list ? list.size() : 0; }
    private long elapsedMs(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000L; }
}
