package com.fragment.labbooking.knowledge.service.impl;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.knowledge.agent.AgentState;
import com.fragment.labbooking.knowledge.agent.AgentToolExecution;
import com.fragment.labbooking.knowledge.agent.ContextPack;
import com.fragment.labbooking.knowledge.agent.ContextPlan;
import com.fragment.labbooking.knowledge.agent.PolicyContext;
import com.fragment.labbooking.knowledge.service.AgentRunService;
import com.fragment.labbooking.knowledge.service.AiServiceClient;
import com.fragment.labbooking.knowledge.service.AiToolCallAuditService;
import com.fragment.labbooking.knowledge.service.KbDocumentService;
import com.fragment.labbooking.knowledge.service.NativeToolCallingClient;
import com.fragment.labbooking.knowledge.service.NativeToolCallingService;
import com.fragment.labbooking.knowledge.service.ReservationCancellationPreviewToolService;
import com.fragment.labbooking.knowledge.service.ReservationContextToolService;
import com.fragment.labbooking.knowledge.service.ResourceAvailabilityToolService;
import com.fragment.labbooking.knowledge.service.ToolRouteResult;
import com.fragment.labbooking.knowledge.vo.ReservationAssistantContextVO;
import com.fragment.labbooking.knowledge.vo.ReservationCancellationPreviewVO;
import com.fragment.labbooking.knowledge.vo.ResourceAvailabilityToolVO;
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
    @Autowired private ReservationContextToolService reservationContextToolService;
    @Autowired private ResourceAvailabilityToolService resourceAvailabilityToolService;
    @Autowired private ReservationCancellationPreviewToolService cancellationPreviewToolService;
    @Autowired private AiToolCallAuditService aiToolCallAuditService;
    @Autowired private KbDocumentService kbDocumentService;
    @Autowired private AiServiceClient aiServiceClient;
    @Autowired private AgentRunService agentRunService;

    @Value("${app.knowledge.native-tool-calling.enabled:true}")
    private boolean enabled;

    @Override
    public Optional<ToolRouteResult> tryAnswer(String question, LoginUser actor, String sessionId, String traceId) {
        if (!enabled || !isToolEligible(question) || actor == null || actor.getId() == null) {
            return Optional.empty();
        }
        try {
            List<Map<String, Object>> trace = new ArrayList<>();
            List<NativeToolCallingClient.ExecutedToolCall> executed = new ArrayList<>();
            int sourceCount = 0;
            PolicyContext policy = PolicyContext.from(actor);
            AgentState state = new AgentState(traceId, sessionId, policy);
            boolean runtimeManaged = traceId != null && !traceId.isBlank();
            if (runtimeManaged) {
                agentRunService.beginRuntime(traceId, policy);
            }
            for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
                state.planning(round + 1);
                observeState(runtimeManaged, traceId, state);
                NativeToolCallingClient.ToolRound plan = nativeToolCallingClient.nextRound(question, toolDefinitions(), executed);
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
                    return Optional.of(new ToolRouteResult(plan.answer(), trace, runtimeManaged, sourceCount));
                }
                for (NativeToolCallingClient.PlannedToolCall call : plan.toolCalls()) {
                    state.executingTool();
                    observeState(runtimeManaged, traceId, state);
                    ExecutedCall result = execute(call, actor, question);
                    executed.add(new NativeToolCallingClient.ExecutedToolCall(
                            safeCallId(call.callId()), result.toolName(), result.arguments(), result.output()));
                    trace.add(result.trace());
                    sourceCount += result.sourceCount();
                    observeTool(runtimeManaged, traceId, result.execution());
                    state.completeTool(result.toolName());
                    observeState(runtimeManaged, traceId, state);
                }
            }
            state.answering();
            observeState(runtimeManaged, traceId, state);
            state.succeed();
            observeState(runtimeManaged, traceId, state);
            return Optional.of(new ToolRouteResult(
                    "已完成所需数据查询，但工具调用轮次达到安全上限，请换一种更具体的问法。", trace, runtimeManaged, sourceCount));
        } catch (BusinessException exception) {
            // The rule router remains a safe fallback when the model gateway is temporarily unavailable.
            return Optional.empty();
        }
    }

    private ExecutedCall execute(NativeToolCallingClient.PlannedToolCall call, LoginUser actor, String originalQuestion) {
        String toolName = call.name() == null ? "" : call.name();
        Map<String, Object> arguments = call.arguments() == null ? Collections.emptyMap() : call.arguments();
        String traceId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();
        try {
            Map<String, Object> output = switch (toolName) {
                case "reservation_context" -> contextOutput(reservationContextToolService.getReservationContext(actor, actor.getId()));
                case "resource_availability" -> availabilityOutput(resourceAvailabilityToolService.findAvailableSlots(
                        optionalKeyword(arguments.get("keyword")), 5));
                case "reservation_cancellation_preview" -> cancellationOutput(cancellationPreviewToolService.preview(
                        actor, requiredPositiveLong(arguments.get("reservationId"), "reservationId")));
                case "knowledge_search" -> knowledgeOutput(
                        optionalKeyword(arguments.get("query")) == null ? originalQuestion : optionalKeyword(arguments.get("query")), actor);
                default -> throw new BusinessException("不允许调用的 AI 工具: " + toolName);
            };
            long latencyMs = elapsedMs(startedAt);
            aiToolCallAuditService.recordSuccess(traceId, toolName, actor, actor.getId(), targetType(toolName), latencyMs,
                    summarizeArguments(arguments));
            return new ExecutedCall(toolName, arguments, output, trace(toolName, traceId, latencyMs, "SUCCESS"),
                    execution(toolName, traceId, latencyMs, "SUCCESS", output), listSize(output.get("evidence")));
        } catch (RuntimeException exception) {
            long latencyMs = elapsedMs(startedAt);
            aiToolCallAuditService.recordFailure(traceId, toolName, actor, actor.getId(), latencyMs,
                    summarizeArguments(arguments), exception.getMessage());
            Map<String, Object> output = Map.of("error", "TOOL_EXECUTION_REJECTED", "message", exception.getMessage());
            return new ExecutedCall(toolName, arguments, output, trace(toolName, traceId, latencyMs, "REJECTED"),
                    execution(toolName, traceId, latencyMs, "REJECTED", output), 0);
        }
    }

    private List<Map<String, Object>> toolDefinitions() {
        return List.of(
                Map.of("type", "function", "function", Map.of("name", "reservation_context",
                        "description", "读取当前登录用户自己的预约统计与未来预约，只读。",
                        "parameters", Map.of("type", "object", "properties", Map.of(), "additionalProperties", false))),
                Map.of("type", "function", "function", Map.of("name", "resource_availability",
                        "description", "查询未来开放且有剩余名额的实验室资源时段，只读。",
                        "parameters", Map.of("type", "object", "properties", Map.of("keyword", Map.of("type", "string", "description", "可选资源名称关键词")), "additionalProperties", false))),
                Map.of("type", "function", "function", Map.of("name", "reservation_cancellation_preview",
                        "description", "仅预检当前登录用户自己的指定预约能否取消；不会执行取消。必须先有预约 ID。",
                        "parameters", Map.of("type", "object", "properties", Map.of("reservationId", Map.of("type", "integer", "description", "预约 ID")), "required", List.of("reservationId"), "additionalProperties", false))),
                Map.of("type", "function", "function", Map.of("name", "knowledge_search",
                        "description", "检索当前用户有权限访问的实验室制度、预约规则、设备使用说明和流程。遇到规则、政策、流程、费用、处罚或无法由预约工具直接回答的问题时必须调用；返回受权限过滤的证据卡和依据摘要。",
                        "parameters", Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string", "description", "用于知识库检索的简短具体问题")), "required", List.of("query"), "additionalProperties", false)))
        );
    }

    private Map<String, Object> contextOutput(ReservationAssistantContextVO value) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("activeReservationCount", value.getActiveReservationCount());
        output.put("upcomingReservations", value.getUpcomingReservations());
        output.put("generatedAt", value.getGeneratedAt());
        return output;
    }

    private Map<String, Object> availabilityOutput(ResourceAvailabilityToolVO value) {
        return Map.of("resultCount", value.getResultCount(), "slots", value.getSlots(), "generatedAt", value.getGeneratedAt());
    }

    private Map<String, Object> cancellationOutput(ReservationCancellationPreviewVO value) {
        return Map.of("reservationId", value.getReservationId(), "reservationNo", value.getReservationNo(),
                "resourceName", value.getResourceName(), "canCancel", value.isCanCancel(),
                "requiresUserConfirmation", value.isRequiresUserConfirmation(), "writeExecuted", value.isWriteExecuted(),
                "nextAction", value.getNextAction());
    }

    private Map<String, Object> knowledgeOutput(String query, LoginUser actor) {
        List<Long> documentIds = kbDocumentService.listAccessibleReadyDocumentIds(actor);
        if (documentIds.isEmpty()) {
            return Map.of("status", "NO_ACCESSIBLE_DOCUMENTS", "accessible_document_count", 0, "evidence", List.of());
        }
        ContextPack pack = ContextPack.from(query, documentIds.size(),
                aiServiceClient.askQuestion(query, "", documentIds));
        return pack.toToolPayload();
    }

    private boolean isToolEligible(String question) {
        return question != null && !question.isBlank();
    }

    private String optionalKeyword(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text.substring(0, Math.min(text.length(), 40));
    }

    private Long requiredPositiveLong(Object value, String field) {
        try {
            long number = value instanceof Number numberValue ? numberValue.longValue() : Long.parseLong(String.valueOf(value));
            if (number <= 0) throw new NumberFormatException();
            return number;
        } catch (Exception exception) {
            throw new BusinessException("工具参数 " + field + " 必须是正整数");
        }
    }

    private Map<String, Object> trace(String toolName, String traceId, long latencyMs, String result) {
        return Map.of("tool_name", toolName, "tool_trace_id", traceId, "latency_ms", latencyMs,
                "result", result, "protocol", "native_function_calling");
    }

    private AgentToolExecution execution(String toolName, String traceId, long latencyMs, String status,
                                         Map<String, Object> output) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("result", status);
        if ("knowledge_search".equals(toolName)) {
            detail.put("evidence_count", listSize(output.get("evidence")));
            detail.put("knowledge_status", String.valueOf(output.getOrDefault("status", "")));
        }
        return new AgentToolExecution(toolName, status, (int) Math.min(Integer.MAX_VALUE, latencyMs), traceId,
                "native_function_calling", detail);
    }

    private String targetType(String toolName) {
        return "knowledge_search".equals(toolName) ? "ACL_FILTERED_KNOWLEDGE" : "SELF_READ";
    }

    private int listSize(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private void observeState(boolean runtimeManaged, String traceId, AgentState state) {
        if (runtimeManaged) {
            agentRunService.recordRuntimeState(traceId, state);
        }
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

    private String summarizeArguments(Map<String, Object> arguments) { return arguments.toString().substring(0, Math.min(arguments.toString().length(), 512)); }
    private String safeCallId(String callId) { return callId == null || callId.isBlank() ? UUID.randomUUID().toString() : callId; }
    private long elapsedMs(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000; }

    private record ExecutedCall(String toolName, Map<String, Object> arguments, Map<String, Object> output,
                                Map<String, Object> trace, AgentToolExecution execution, int sourceCount) {}
}
