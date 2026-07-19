package com.fragment.labbooking.knowledge.service.impl;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.knowledge.service.AiToolCallAuditService;
import com.fragment.labbooking.knowledge.service.AssistantToolRouter;
import com.fragment.labbooking.knowledge.service.ReservationCancellationPreviewToolService;
import com.fragment.labbooking.knowledge.service.ReservationContextToolService;
import com.fragment.labbooking.knowledge.service.ResourceAvailabilityToolService;
import com.fragment.labbooking.knowledge.service.ToolRouteResult;
import com.fragment.labbooking.knowledge.vo.ReservationAssistantContextVO;
import com.fragment.labbooking.knowledge.vo.ReservationCancellationPreviewVO;
import com.fragment.labbooking.knowledge.vo.ResourceAvailabilityToolVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AssistantToolRouterImpl implements AssistantToolRouter {

    private static final Pattern RESERVATION_ID = Pattern.compile("(?:预约|订单|记录)\\s*(?:号|ID|id)?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    @Autowired private ReservationContextToolService reservationContextToolService;
    @Autowired private ResourceAvailabilityToolService resourceAvailabilityToolService;
    @Autowired private ReservationCancellationPreviewToolService cancellationPreviewToolService;
    @Autowired private AiToolCallAuditService aiToolCallAuditService;

    @Override
    public Optional<ToolRouteResult> route(String question, LoginUser actor) {
        String normalized = question == null ? "" : question.trim();
        if (normalized.isEmpty() || actor == null || actor.getId() == null) {
            return Optional.empty();
        }
        List<Map<String, Object>> calls = new ArrayList<>();
        if (isCancellationIntent(normalized)) {
            return Optional.of(cancelPreview(normalized, actor, calls));
        }
        if (isAvailabilityIntent(normalized)) {
            ResourceAvailabilityToolVO availability = invoke("resource_availability", actor,
                    "question=" + compact(normalized), () -> resourceAvailabilityToolService.findAvailableSlots(null, 3), calls);
            return Optional.of(new ToolRouteResult(formatAvailability(availability), calls));
        }
        if (isMyReservationIntent(normalized)) {
            ReservationAssistantContextVO context = invoke("reservation_context", actor,
                    "subjectUserId=" + actor.getId(), () -> reservationContextToolService.getReservationContext(actor, actor.getId()), calls);
            return Optional.of(new ToolRouteResult(formatContext(context), calls));
        }
        return Optional.empty();
    }

    private ToolRouteResult cancelPreview(String question, LoginUser actor, List<Map<String, Object>> calls) {
        Long reservationId = extractReservationId(question);
        if (reservationId == null) {
            ReservationAssistantContextVO context = invoke("reservation_context", actor,
                    "subjectUserId=" + actor.getId() + ",reason=cancel_preview", () -> reservationContextToolService.getReservationContext(actor, actor.getId()), calls);
            if (context.getUpcomingReservations() == null || context.getUpcomingReservations().isEmpty()) {
                return new ToolRouteResult("我没有找到可用于取消预检的未来预约。请在“我的预约”中选择具体预约后再确认取消。", calls);
            }
            reservationId = context.getUpcomingReservations().get(0).getReservationId();
        }
        Long resolvedReservationId = reservationId;
        ReservationCancellationPreviewVO preview = invoke("reservation_cancellation_preview", actor,
                "reservationId=" + resolvedReservationId,
                () -> cancellationPreviewToolService.preview(actor, resolvedReservationId), calls);
        String answer = preview.isCanCancel()
                ? "已完成取消前检查：" + nullSafe(preview.getResourceName()) + "（" + nullSafe(preview.getReservationNo()) + "）当前可以取消。"
                : "已完成取消前检查：该预约当前状态为 " + nullSafe(preview.getCurrentStatus()) + "，不能取消。";
        return new ToolRouteResult(answer + "\n" + preview.getNextAction(), calls);
    }

    private String formatContext(ReservationAssistantContextVO context) {
        StringBuilder answer = new StringBuilder("已查询你的预约上下文：当前有 ")
                .append(context.getActiveReservationCount()).append(" 个进行中预约。");
        if (context.getUpcomingReservations() == null || context.getUpcomingReservations().isEmpty()) {
            return answer.append("目前没有未来待使用预约。").toString();
        }
        answer.append("\n近期预约：");
        context.getUpcomingReservations().forEach(item -> answer.append("\n- ")
                .append(nullSafe(item.getResourceName())).append("，")
                .append(item.getStartDatetime() == null ? "时间待定" : TIME.format(item.getStartDatetime())));
        return answer.toString();
    }

    private String formatAvailability(ResourceAvailabilityToolVO availability) {
        if (availability.getSlots() == null || availability.getSlots().isEmpty()) {
            return "已查询实时可用时段，但暂未找到未来开放且仍有名额的资源。你可以稍后刷新，或前往资源页查看完整列表。";
        }
        StringBuilder answer = new StringBuilder("已查询实时可用时段：");
        availability.getSlots().forEach(slot -> answer.append("\n- ")
                .append(nullSafe(slot.getResourceName())).append("，")
                .append(slot.getStartDatetime() == null ? "时间待定" : TIME.format(slot.getStartDatetime()))
                .append("，剩余 ").append(slot.getRemainQuota()).append(" 个名额"));
        return answer.toString();
    }

    private <T> T invoke(String toolName, LoginUser actor, String parameterSummary, ToolCall<T> call,
                         List<Map<String, Object>> calls) {
        String traceId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();
        try {
            T result = call.execute();
            long latencyMs = elapsedMs(startedAt);
            aiToolCallAuditService.recordSuccess(traceId, toolName, actor, actor.getId(), "SELF_READ", latencyMs, parameterSummary);
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("tool_name", toolName);
            trace.put("tool_trace_id", traceId);
            trace.put("latency_ms", latencyMs);
            trace.put("result", "SUCCESS");
            calls.add(trace);
            return result;
        } catch (RuntimeException exception) {
            aiToolCallAuditService.recordFailure(traceId, toolName, actor, actor.getId(), elapsedMs(startedAt), parameterSummary, exception.getMessage());
            throw exception;
        }
    }

    private boolean isMyReservationIntent(String question) {
        return (question.contains("我的") || question.contains("我"))
                && (question.contains("预约") || question.contains("预订") || question.contains("安排"));
    }

    private boolean isAvailabilityIntent(String question) {
        return (question.contains("可用") || question.contains("空闲") || question.contains("有空") || question.contains("名额"))
                && (question.contains("资源") || question.contains("实验室") || question.contains("设备") || question.contains("时段") || question.contains("预约"));
    }

    private boolean isCancellationIntent(String question) {
        return (question.contains("取消") || question.contains("撤销"))
                && (question.contains("预约") || question.contains("预订") || question.contains("订单"));
    }

    private Long extractReservationId(String question) {
        Matcher matcher = RESERVATION_ID.matcher(question);
        return matcher.find() ? Long.valueOf(matcher.group(1)) : null;
    }

    private String compact(String text) { return text.replaceAll("[\\r\\n]", " ").substring(0, Math.min(text.length(), 180)); }
    private String nullSafe(String value) { return value == null ? "未命名资源" : value; }
    private long elapsedMs(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000; }

    @FunctionalInterface
    private interface ToolCall<T> { T execute(); }
}
