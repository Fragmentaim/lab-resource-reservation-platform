package com.fragment.labbooking.knowledge.service.impl;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.knowledge.agent.AgentConversationContext;
import com.fragment.labbooking.knowledge.agent.AgentState;
import com.fragment.labbooking.knowledge.agent.AgentToolExecution;
import com.fragment.labbooking.knowledge.agent.ContextPlan;
import com.fragment.labbooking.knowledge.agent.PolicyContext;
import com.fragment.labbooking.knowledge.agent.ToolResultContextPacker;
import com.fragment.labbooking.knowledge.agent.tool.AgentTool;
import com.fragment.labbooking.knowledge.agent.tool.AgentToolInvocation;
import com.fragment.labbooking.knowledge.agent.tool.AgentToolRegistry;
import com.fragment.labbooking.knowledge.agent.tool.AgentToolResult;
import com.fragment.labbooking.knowledge.service.AgentRunService;
import com.fragment.labbooking.knowledge.service.AiToolCallAuditService;
import com.fragment.labbooking.knowledge.service.NativeToolCallingClient;
import com.fragment.labbooking.knowledge.service.NativeToolCallingService;
import com.fragment.labbooking.knowledge.service.ToolRouteResult;
import com.fragment.labbooking.knowledge.vo.QaSourceVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class NativeToolCallingServiceImpl implements NativeToolCallingService {

    private static final int MAX_TOOL_ROUNDS = 3;

    @Autowired private NativeToolCallingClient nativeToolCallingClient;
    @Autowired private AiToolCallAuditService aiToolCallAuditService;
    @Autowired private AgentToolRegistry agentToolRegistry;
    @Autowired private AgentRunService agentRunService;
    @Autowired private ToolResultContextPacker toolResultContextPacker;

    @Value("${app.knowledge.native-tool-calling.enabled:true}")
    private boolean enabled;

    // Optional sanitized arguments for evaluation runs.
    @Value("${app.knowledge.agent-evaluation.capture-arguments:false}")
    private boolean captureEvaluationArguments;

    @Override
    public Optional<ToolRouteResult> tryAnswer(String question, LoginUser actor, String sessionId, String traceId,
                                               AgentConversationContext conversationContext) {
        if (!enabled || !isToolEligible(question) || actor == null || actor.getId() == null) {
            return Optional.empty();
        }
        try {
            List<Map<String, Object>> trace = new ArrayList<>();
            List<QaSourceVO> openedSources = new ArrayList<>();
            int sourceCount = 0;
            PolicyContext policy = PolicyContext.from(actor);
            boolean runtimeManaged = traceId != null && !traceId.isBlank();
            AgentState state = runtimeManaged
                    ? agentRunService.restoreRuntimeCheckpoint(traceId, policy)
                    .orElseGet(() -> new AgentState(traceId, sessionId, policy))
                    : new AgentState(traceId, sessionId, policy);
            List<NativeToolCallingClient.ExecutedToolCall> executed = new ArrayList<>(resumeCandidateCalls(state));
            List<Map<String, Object>> providerUsages = new ArrayList<>();
            List<Map<String, Object>> clientActions = new ArrayList<>();
            if (runtimeManaged) {
                agentRunService.beginRuntime(traceId, policy);
            }
            for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
                state.planning(round + 1);
                observeState(runtimeManaged, traceId, state);
                long planningStartedAt = System.nanoTime();
                NativeToolCallingClient.ToolRound plan = nativeToolCallingClient.nextRound(
                        question, agentToolRegistry.definitionsFor(policy), executed,
                        conversationContext == null ? AgentConversationContext.empty() : conversationContext);
                providerUsages.add(plan.providerUsage());
                observePlan(runtimeManaged, traceId, ContextPlan.from(round + 1, plan,
                        (int) Math.min(Integer.MAX_VALUE, elapsedMs(planningStartedAt))));
                if (plan.toolCalls() == null || plan.toolCalls().isEmpty()) {
                    if (plan.answer() == null || plan.answer().isBlank()) {
                        state.fallingBackToRag();
                        observeState(runtimeManaged, traceId, state);
                        return Optional.empty();
                    }
                    state.answering();
                    observeState(runtimeManaged, traceId, state);
                    state.succeed();
                    observeState(runtimeManaged, traceId, state);
                    return Optional.of(new ToolRouteResult(plan.answer(), trace, runtimeManaged, sourceCount, openedSources,
                            aggregateProviderUsage(providerUsages), clientActions));
                }
                for (NativeToolCallingClient.PlannedToolCall call : plan.toolCalls()) {
                    state.executingTool();
                    observeState(runtimeManaged, traceId, state);
                    ExecutedCall result = execute(call, actor, policy, question, state, openedSources);
                    ToolResultContextPacker.PackedToolResult packed = toolResultContextPacker.pack(
                            result.toolName(), result.output());
                    executed.add(new NativeToolCallingClient.ExecutedToolCall(
                            safeCallId(call.callId()), result.toolName(), result.arguments(), packed.modelOutput(), round + 1));
                    trace.add(result.trace());
                    sourceCount += result.sourceCount();
                    reservationDraftAction(result.toolName(), result.output()).ifPresent(clientActions::add);
                    observeTool(runtimeManaged, traceId, withContextPacking(result.execution(), packed.safeDetail()));
                    state.completeTool(result.toolName());
                    observeState(runtimeManaged, traceId, state);
                }
            }
            state.answering();
            observeState(runtimeManaged, traceId, state);
            state.succeed();
            observeState(runtimeManaged, traceId, state);
            return Optional.of(new ToolRouteResult(
                    "已完成所需数据查询，但工具调用轮次达到安全上限，请换一种更具体的问法。", trace, runtimeManaged, sourceCount, openedSources,
                    aggregateProviderUsage(providerUsages), clientActions));
        } catch (BusinessException exception) {
            // Fall back to deterministic routing if the model gateway is unavailable.
            return Optional.empty();
        }
    }

    private ExecutedCall execute(NativeToolCallingClient.PlannedToolCall call, LoginUser actor, PolicyContext policy,
                                 String originalQuestion, AgentState state, List<QaSourceVO> openedSources) {
        String toolName = call.name() == null ? "" : call.name();
        Map<String, Object> arguments = call.arguments() == null ? Collections.emptyMap() : call.arguments();
        String traceId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();
        try {
            AgentTool tool = agentToolRegistry.resolve(toolName, policy);
            AgentToolResult result = tool.execute(new AgentToolInvocation(actor, originalQuestion, state, openedSources, arguments));
            Map<String, Object> output = result.output();
            long latencyMs = elapsedMs(startedAt);
            aiToolCallAuditService.recordSuccess(traceId, tool.name(), actor, actor.getId(), tool.accessScope(), latencyMs,
                    summarizeArguments(arguments));
            return new ExecutedCall(tool.name(), arguments, output, trace(tool.name(), traceId, latencyMs, "SUCCESS", arguments),
                    execution(tool.name(), traceId, latencyMs, "SUCCESS", output, result.executionDetail(), arguments), result.sourceCount());
        } catch (BusinessException exception) {
            long latencyMs = elapsedMs(startedAt);
            aiToolCallAuditService.recordFailure(traceId, toolName, actor, actor.getId(), latencyMs,
                    summarizeArguments(arguments), exception.getMessage());
            Map<String, Object> output = Map.of("error", "TOOL_EXECUTION_REJECTED", "message", exception.getMessage());
            return new ExecutedCall(toolName, arguments, output, trace(toolName, traceId, latencyMs, "REJECTED", arguments),
                    execution(toolName, traceId, latencyMs, "REJECTED", output, Map.of(), arguments), 0);
        } catch (RuntimeException exception) {
            long latencyMs = elapsedMs(startedAt);
            aiToolCallAuditService.recordFailure(traceId, toolName, actor, actor.getId(), latencyMs,
                    summarizeArguments(arguments), exception.getMessage());
            throw exception;
        }
    }

    private Map<String, Object> aggregateProviderUsage(List<Map<String, Object>> usages) {
        long inputTokens = 0;
        long outputTokens = 0;
        long totalTokens = 0;
        long cachedInputTokens = 0;
        boolean reported = false;
        for (Map<String, Object> usage : usages == null ? List.<Map<String, Object>>of() : usages) {
            if (usage == null) continue;
            reported |= Boolean.TRUE.equals(usage.get("reported"));
            inputTokens += numberValue(usage.get("input_tokens"));
            outputTokens += numberValue(usage.get("output_tokens"));
            totalTokens += numberValue(usage.get("total_tokens"));
            cachedInputTokens += numberValue(usage.get("cached_input_tokens"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reported", reported);
        result.put("model_round_count", usages == null ? 0 : usages.size());
        result.put("input_tokens", inputTokens);
        result.put("output_tokens", outputTokens);
        result.put("total_tokens", totalTokens > 0 ? totalTokens : inputTokens + outputTokens);
        result.put("cached_input_tokens", cachedInputTokens);
        return result;
    }

    private long numberValue(Object value) {
        if (value instanceof Number number) return Math.max(0L, number.longValue());
        try {
            return Math.max(0L, Long.parseLong(String.valueOf(value)));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private boolean isToolEligible(String question) {
        return question != null && !question.isBlank();
    }

    private Map<String, Object> trace(String toolName, String traceId, long latencyMs, String result,
                                      Map<String, Object> arguments) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("tool_name", toolName);
        trace.put("tool_trace_id", traceId);
        trace.put("latency_ms", latencyMs);
        trace.put("result", result);
        trace.put("protocol", "native_function_calling");
        if (captureEvaluationArguments) {
            trace.put("evaluation_arguments", sanitizeArguments(arguments));
        }
        return trace;
    }

    private AgentToolExecution execution(String toolName, String traceId, long latencyMs, String status,
                                         Map<String, Object> output, Map<String, Object> toolDetail,
                                         Map<String, Object> arguments) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("result", status);
        if (toolDetail != null) {
            detail.putAll(toolDetail);
        }
        if ("knowledge_search".equals(toolName) || "knowledge_open_chunks".equals(toolName)) {
            detail.putIfAbsent("candidate_count", listSize(output.get("candidates")));
            detail.putIfAbsent("opened_chunk_count", listSize(output.get("chunks")));
            detail.putIfAbsent("knowledge_status", String.valueOf(output.getOrDefault("status", "")));
        }
        if (captureEvaluationArguments) {
            detail.put("evaluation_arguments", sanitizeArguments(arguments));
        }
        return new AgentToolExecution(toolName, status, (int) Math.min(Integer.MAX_VALUE, latencyMs), traceId,
                "native_function_calling", detail);
    }

    private int listSize(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private void observeState(boolean runtimeManaged, String traceId, AgentState state) {
        if (runtimeManaged) {
            agentRunService.recordRuntimeState(traceId, state);
        }
    }

    private Optional<Map<String, Object>> reservationDraftAction(String toolName, Map<String, Object> output) {
        if (!"reservation_create_draft".equals(toolName) || output == null) {
            return Optional.empty();
        }
        Object token = output.get("confirmationToken");
        Object endpoint = output.get("confirmationEndpoint");
        if (!(token instanceof String confirmationToken) || confirmationToken.isBlank()
                || !(endpoint instanceof String confirmationEndpoint) || confirmationEndpoint.isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "RESERVATION_CONFIRMATION");
        action.put("label", "确认预约");
        action.put("confirmationToken", confirmationToken);
        action.put("confirmationEndpoint", confirmationEndpoint);
        copyActionField(output, action, "resourceId");
        copyActionField(output, action, "resourceName");
        copyActionField(output, action, "location");
        copyActionField(output, action, "slotId");
        copyActionField(output, action, "startDatetime");
        copyActionField(output, action, "endDatetime");
        copyActionField(output, action, "remainQuota");
        copyActionField(output, action, "expiresAt");
        return Optional.of(action);
    }

    private void copyActionField(Map<String, Object> output, Map<String, Object> action, String field) {
        Object value = output.get(field);
        if (value != null) {
            action.put(field, value);
        }
    }

    private Map<String, Object> sanitizeArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        arguments.forEach((key, value) -> safe.put(key, sanitizeArgumentValue(key, value)));
        return safe;
    }

    private Object sanitizeArgumentValue(String key, Object value) {
        if (isSensitiveArgumentKey(key)) {
            return "<redacted>";
        }
        if (value instanceof Map<?, ?> values) {
            Map<String, Object> safe = new LinkedHashMap<>();
            values.forEach((nestedKey, nestedValue) -> {
                String normalizedKey = nestedKey == null ? "" : String.valueOf(nestedKey);
                safe.put(normalizedKey, sanitizeArgumentValue(normalizedKey, nestedValue));
            });
            return safe;
        }
        if (value instanceof Iterable<?> values) {
            List<Object> safe = new ArrayList<>();
            values.forEach(item -> safe.add(sanitizeArgumentValue("", item)));
            return safe;
        }
        if (value instanceof String text) {
            return com.fragment.labbooking.common.util.TruncateUtil.truncate(text, 240);
        }
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return com.fragment.labbooking.common.util.TruncateUtil.truncate(String.valueOf(value), 240);
    }

    private boolean isSensitiveArgumentKey(String key) {
        String normalizedKey = key == null ? "" : key.toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "");
        return normalizedKey.contains("password")
                || normalizedKey.contains("token")
                || normalizedKey.contains("secret")
                || normalizedKey.contains("authorization")
                || normalizedKey.contains("credential")
                || normalizedKey.contains("privatekey")
                || normalizedKey.contains("accesskey");
    }

    private List<NativeToolCallingClient.ExecutedToolCall> resumeCandidateCalls(AgentState state) {
        List<String> chunkUids = state.resumableKnowledgeChunkUids();
        if (chunkUids.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> candidates = chunkUids.stream()
                .map(chunkUid -> Map.<String, Object>of("chunk_uid", chunkUid))
                .toList();
        Map<String, Object> output = Map.of(
                "status", "RESUMED_CANDIDATES",
                "candidate_count", candidates.size(),
                "candidates", candidates,
                "checkpoint_replayed", true
        );
        return List.of(new NativeToolCallingClient.ExecutedToolCall(
                "checkpoint-knowledge-search", "knowledge_search",
                Map.of("query", "checkpoint-resume"), output, 0
        ));
    }

    private void observePlan(boolean runtimeManaged, String traceId, ContextPlan plan) {
        if (runtimeManaged) {
            agentRunService.recordContextPlan(traceId, plan);
        }
    }

    private void observeTool(boolean runtimeManaged, String traceId, AgentToolExecution execution) {
        if (runtimeManaged) {
            agentRunService.recordToolExecution(traceId, execution);
        }
    }

    private AgentToolExecution withContextPacking(AgentToolExecution execution, Map<String, Object> contextPacking) {
        Map<String, Object> detail = new LinkedHashMap<>(execution.detail());
        detail.put("context_pack", contextPacking);
        return new AgentToolExecution(execution.toolName(), execution.status(), execution.latencyMs(),
                execution.toolTraceId(), execution.protocol(), detail);
    }

    private String summarizeArguments(Map<String, Object> arguments) {
        return com.fragment.labbooking.common.util.TruncateUtil.truncate(String.valueOf(sanitizeArguments(arguments)), 512);
    }
    private String safeCallId(String callId) { return callId == null || callId.isBlank() ? UUID.randomUUID().toString() : callId; }
    private long elapsedMs(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000; }

    private record ExecutedCall(String toolName, Map<String, Object> arguments, Map<String, Object> output,
                                Map<String, Object> trace, AgentToolExecution execution, int sourceCount) {}
}
