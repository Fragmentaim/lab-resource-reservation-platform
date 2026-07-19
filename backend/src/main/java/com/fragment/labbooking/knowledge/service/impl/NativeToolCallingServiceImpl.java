package com.fragment.labbooking.knowledge.service.impl;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.knowledge.service.AiToolCallAuditService;
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

    @Value("${app.knowledge.native-tool-calling.enabled:true}")
    private boolean enabled;

    @Override
    public Optional<ToolRouteResult> tryAnswer(String question, LoginUser actor) {
        if (!enabled || !isToolEligible(question) || actor == null || actor.getId() == null) {
            return Optional.empty();
        }
        try {
            List<Map<String, Object>> trace = new ArrayList<>();
            List<NativeToolCallingClient.ExecutedToolCall> executed = new ArrayList<>();
            for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
                NativeToolCallingClient.ToolRound plan = nativeToolCallingClient.nextRound(question, toolDefinitions(), executed);
                if (plan.toolCalls() == null || plan.toolCalls().isEmpty()) {
                    if (executed.isEmpty() || plan.answer() == null || plan.answer().isBlank()) {
                        return Optional.empty();
                    }
                    return Optional.of(new ToolRouteResult(plan.answer(), trace));
                }
                for (NativeToolCallingClient.PlannedToolCall call : plan.toolCalls()) {
                    ExecutedCall result = execute(call, actor);
                    executed.add(new NativeToolCallingClient.ExecutedToolCall(
                            safeCallId(call.callId()), result.toolName(), result.arguments(), result.output()));
                    trace.add(result.trace());
                }
            }
            return Optional.of(new ToolRouteResult(
                    "已完成业务数据查询，但工具调用轮次达到安全上限，请换一种更具体的问法。", trace));
        } catch (BusinessException exception) {
            // The rule router remains a safe fallback when the model gateway is temporarily unavailable.
            return Optional.empty();
        }
    }

    private ExecutedCall execute(NativeToolCallingClient.PlannedToolCall call, LoginUser actor) {
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
                default -> throw new BusinessException("不允许调用的 AI 工具: " + toolName);
            };
            long latencyMs = elapsedMs(startedAt);
            aiToolCallAuditService.recordSuccess(traceId, toolName, actor, actor.getId(), "SELF_READ", latencyMs,
                    summarizeArguments(arguments));
            return new ExecutedCall(toolName, arguments, output, trace(toolName, traceId, latencyMs, "SUCCESS"));
        } catch (RuntimeException exception) {
            long latencyMs = elapsedMs(startedAt);
            aiToolCallAuditService.recordFailure(traceId, toolName, actor, actor.getId(), latencyMs,
                    summarizeArguments(arguments), exception.getMessage());
            Map<String, Object> output = Map.of("error", "TOOL_EXECUTION_REJECTED", "message", exception.getMessage());
            return new ExecutedCall(toolName, arguments, output, trace(toolName, traceId, latencyMs, "REJECTED"));
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
                        "parameters", Map.of("type", "object", "properties", Map.of("reservationId", Map.of("type", "integer", "description", "预约 ID")), "required", List.of("reservationId"), "additionalProperties", false)))
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

    private boolean isToolEligible(String question) {
        if (question == null) return false;
        return (question.contains("预约") || question.contains("预订") || question.contains("时段")
                || question.contains("名额") || question.contains("可用") || question.contains("取消"))
                && (question.contains("我") || question.contains("资源") || question.contains("实验室")
                || question.contains("设备") || question.contains("预约"));
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

    private String summarizeArguments(Map<String, Object> arguments) { return arguments.toString().substring(0, Math.min(arguments.toString().length(), 512)); }
    private String safeCallId(String callId) { return callId == null || callId.isBlank() ? UUID.randomUUID().toString() : callId; }
    private long elapsedMs(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000; }

    private record ExecutedCall(String toolName, Map<String, Object> arguments, Map<String, Object> output,
                                Map<String, Object> trace) {}
}
