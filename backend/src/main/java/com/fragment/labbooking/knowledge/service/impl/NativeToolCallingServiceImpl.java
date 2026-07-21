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
            if (runtimeManaged) {
                agentRunService.beginRuntime(traceId, policy);
            }
            for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
                state.planning(round + 1);
                observeState(runtimeManaged, traceId, state);
                NativeToolCallingClient.ToolRound plan = nativeToolCallingClient.nextRound(
                        question, agentToolRegistry.definitionsFor(policy), executed,
                        conversationContext == null ? AgentConversationContext.empty() : conversationContext);
                observePlan(runtimeManaged, traceId, ContextPlan.from(round + 1, plan));
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
                    return Optional.of(new ToolRouteResult(plan.answer(), trace, runtimeManaged, sourceCount, openedSources));
                }
                for (NativeToolCallingClient.PlannedToolCall call : plan.toolCalls()) {
                    state.executingTool();
                    observeState(runtimeManaged, traceId, state);
                    ExecutedCall result = execute(call, actor, policy, question, state, openedSources);
                    ToolResultContextPacker.PackedToolResult packed = toolResultContextPacker.pack(
                            result.toolName(), result.output());
                    executed.add(new NativeToolCallingClient.ExecutedToolCall(
                            safeCallId(call.callId()), result.toolName(), result.arguments(), packed.modelOutput()));
                    trace.add(result.trace());
                    sourceCount += result.sourceCount();
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
                    "已完成所需数据查询，但工具调用轮次达到安全上限，请换一种更具体的问法。", trace, runtimeManaged, sourceCount, openedSources));
        } catch (BusinessException exception) {
            // The rule router remains a safe fallback when the model gateway is temporarily unavailable.
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
            return new ExecutedCall(tool.name(), arguments, output, trace(tool.name(), traceId, latencyMs, "SUCCESS"),
                    execution(tool.name(), traceId, latencyMs, "SUCCESS", output, result.executionDetail()), result.sourceCount());
        } catch (RuntimeException exception) {
            long latencyMs = elapsedMs(startedAt);
            aiToolCallAuditService.recordFailure(traceId, toolName, actor, actor.getId(), latencyMs,
                    summarizeArguments(arguments), exception.getMessage());
            Map<String, Object> output = Map.of("error", "TOOL_EXECUTION_REJECTED", "message", exception.getMessage());
            return new ExecutedCall(toolName, arguments, output, trace(toolName, traceId, latencyMs, "REJECTED"),
                    execution(toolName, traceId, latencyMs, "REJECTED", output, Map.of()), 0);
        }
    }

    private boolean isToolEligible(String question) {
        return question != null && !question.isBlank();
    }

    private Map<String, Object> trace(String toolName, String traceId, long latencyMs, String result) {
        return Map.of("tool_name", toolName, "tool_trace_id", traceId, "latency_ms", latencyMs,
                "result", result, "protocol", "native_function_calling");
    }

    private AgentToolExecution execution(String toolName, String traceId, long latencyMs, String status,
                                         Map<String, Object> output, Map<String, Object> toolDetail) {
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
                Map.of("query", "checkpoint-resume"), output
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

    private String summarizeArguments(Map<String, Object> arguments) { return arguments.toString().substring(0, Math.min(arguments.toString().length(), 512)); }
    private String safeCallId(String callId) { return callId == null || callId.isBlank() ? UUID.randomUUID().toString() : callId; }
    private long elapsedMs(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000; }

    private record ExecutedCall(String toolName, Map<String, Object> arguments, Map<String, Object> output,
                                Map<String, Object> trace, AgentToolExecution execution, int sourceCount) {}
}
