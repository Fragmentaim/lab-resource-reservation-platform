package com.fragment.labbooking.knowledge.agent.tool;

import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.common.util.TruncateUtil;
import com.fragment.labbooking.knowledge.agent.AgentContext;
import com.fragment.labbooking.knowledge.service.AgentRunService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Adds domain auditing and context packing around a Spring AI annotated tool. */
@Component
public class AgentToolRuntime {

    public static final String EXECUTION_CONTEXT_KEY = "agentExecutionContext";

    private final AgentRunService runService;
    private final boolean captureArguments;

    public AgentToolRuntime(AgentRunService runService,
                            @Value("${app.knowledge.agent-evaluation.capture-arguments:false}")
                            boolean captureArguments) {
        this.runService = runService;
        this.captureArguments = captureArguments;
    }

    public Map<String, Object> execute(String toolName, String accessScope, Map<String, Object> arguments,
                                       AgentContext context, Supplier<AgentToolResult> action) {
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        context.beginToolCall();
        String toolTraceId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();

        try {
            AgentToolResult result = action.get();
            long latencyMs = elapsedMs(startedAt);
            context.addToolResult(trace(toolName, toolTraceId, latencyMs, "SUCCESS", safeArguments),
                    result.sources(), reservationDraftAction(toolName, result.output()));
            recordExecution(context, toolName, toolTraceId, latencyMs, "SUCCESS", result.output(),
                    accessScope, result.executionDetail(), safeArguments);
            return result.output();
        } catch (BusinessException exception) {
            long latencyMs = elapsedMs(startedAt);
            Map<String, Object> rejected = Map.of(
                    "error", "TOOL_EXECUTION_REJECTED",
                    "message", exception.getMessage());
            context.addToolResult(trace(toolName, toolTraceId, latencyMs, "REJECTED", safeArguments), List.of(), null);
            recordExecution(context, toolName, toolTraceId, latencyMs, "REJECTED", rejected,
                    accessScope, Map.of(), safeArguments);
            return rejected;
        } catch (RuntimeException exception) {
            throw exception;
        }
    }

    public AgentContext requireExecutionContext(ToolContext toolContext) {
        Object value = toolContext == null ? null : toolContext.getContext().get(EXECUTION_CONTEXT_KEY);
        if (value instanceof AgentContext context) {
            return context;
        }
        throw new BusinessException("工具缺少服务端执行上下文");
    }

    private void recordExecution(AgentContext context, String toolName, String toolTraceId,
                                 long latencyMs, String status, Map<String, Object> output,
                                 String accessScope, Map<String, Object> toolDetail,
                                 Map<String, Object> arguments) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("result", status);
        detail.put("access_scope", accessScope);
        if (toolDetail != null) {
            detail.putAll(toolDetail);
        }
        if ("knowledge_search".equals(toolName) || "knowledge_open_chunks".equals(toolName)) {
            detail.putIfAbsent("candidate_count", listSize(output.get("candidates")));
            detail.putIfAbsent("opened_chunk_count", listSize(output.get("chunks")));
            detail.putIfAbsent("knowledge_status", String.valueOf(output.getOrDefault("status", "")));
        }
        if (captureArguments) {
            detail.put("evaluation_arguments", sanitize(arguments));
        }
        runService.recordToolExecution(context.traceId(), new AgentRunService.ToolExecution(
                toolName, status, (int) Math.min(Integer.MAX_VALUE, latencyMs), toolTraceId,
                "spring_ai_annotated_tool", detail));
    }

    private Map<String, Object> trace(String toolName, String toolTraceId, long latencyMs, String result,
                                      Map<String, Object> arguments) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("tool_name", toolName);
        trace.put("tool_trace_id", toolTraceId);
        trace.put("latency_ms", latencyMs);
        trace.put("result", result);
        trace.put("protocol", "spring_ai_annotated_tool");
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
        arguments.forEach((key, value) -> safe.put(key, sanitizeValue(key, value)));
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

    private int listSize(Object value) { return value instanceof List<?> list ? list.size() : 0; }
    private long elapsedMs(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000L; }
}
