package com.fragment.labbooking.knowledge.agent.tool;

import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.knowledge.agent.AgentContext;
import com.fragment.labbooking.knowledge.service.ReservationCancellationPreviewToolService;
import com.fragment.labbooking.knowledge.service.ReservationContextToolService;
import com.fragment.labbooking.knowledge.service.ReservationDraftToolService;
import com.fragment.labbooking.knowledge.service.ResourceAvailabilityToolService;
import com.fragment.labbooking.knowledge.vo.ReservationAssistantContextVO;
import com.fragment.labbooking.knowledge.vo.ReservationCancellationPreviewVO;
import com.fragment.labbooking.knowledge.vo.ReservationDraftVO;
import com.fragment.labbooking.knowledge.vo.ResourceAvailabilityToolVO;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reservation capabilities exposed through Spring AI's native annotated tools. */
@Component
public class ReservationAgentTools {

    private final AgentToolRuntime runtime;
    private final ReservationContextToolService reservationContextService;
    private final ResourceAvailabilityToolService availabilityService;
    private final ReservationCancellationPreviewToolService cancellationPreviewService;
    private final ReservationDraftToolService reservationDraftService;

    public ReservationAgentTools(AgentToolRuntime runtime,
                                 ReservationContextToolService reservationContextService,
                                 ResourceAvailabilityToolService availabilityService,
                                 ReservationCancellationPreviewToolService cancellationPreviewService,
                                 ReservationDraftToolService reservationDraftService) {
        this.runtime = runtime;
        this.reservationContextService = reservationContextService;
        this.availabilityService = availabilityService;
        this.cancellationPreviewService = cancellationPreviewService;
        this.reservationDraftService = reservationDraftService;
    }

    @Tool(name = "reservation_context", description = "读取当前登录用户自己的预约统计与未来预约，只读。")
    public Map<String, Object> reservationContext(ToolContext toolContext) {
        AgentContext context = runtime.requireExecutionContext(toolContext);
        return runtime.execute("reservation_context", "SELF_READ", Map.of(), context, () -> {
            ReservationAssistantContextVO value = reservationContextService.getReservationContext(
                    context.actor(), context.actor().getId());
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("activeReservationCount", value.getActiveReservationCount());
            output.put("upcomingReservations", value.getUpcomingReservations());
            output.put("generatedAt", value.getGeneratedAt());
            return AgentToolResult.of(output, Map.of(
                    "result_type", "RESERVATION_CONTEXT",
                    "actor_user_id", context.actor().getId()));
        });
    }

    @Tool(name = "resource_availability", description = "查询未来开放且有剩余名额的实验室资源时段，只读。")
    public Map<String, Object> resourceAvailability(
            @ToolParam(description = "可选资源名称关键词", required = false) String keyword,
            ToolContext toolContext) {
        AgentContext context = runtime.requireExecutionContext(toolContext);
        String normalizedKeyword = optionalText(keyword, 40);
        return runtime.execute("resource_availability", "SELF_READ",
                arguments("keyword", normalizedKeyword), context, () -> {
                    ResourceAvailabilityToolVO value = availabilityService.findAvailableSlots(normalizedKeyword, 5);
                    return AgentToolResult.of(Map.of(
                            "resultCount", value.getResultCount(),
                            "slots", value.getSlots(),
                            "generatedAt", value.getGeneratedAt()), Map.of(
                            "result_type", "RESOURCE_AVAILABILITY",
                            "result_count", value.getResultCount(),
                            "keyword", StringUtils.hasText(normalizedKeyword) ? normalizedKeyword : ""));
                });
    }

    @Tool(name = "reservation_cancellation_preview", description = "仅预检当前登录用户自己的指定预约能否取消；不会执行取消。必须先有预约 ID。")
    public Map<String, Object> reservationCancellationPreview(
            @ToolParam(description = "预约 ID") Long reservationId,
            ToolContext toolContext) {
        AgentContext context = runtime.requireExecutionContext(toolContext);
        return runtime.execute("reservation_cancellation_preview", "SELF_READ",
                arguments("reservationId", reservationId), context, () -> {
                    Long validReservationId = requiredPositive(reservationId, "reservationId");
                    ReservationCancellationPreviewVO value = cancellationPreviewService.preview(
                            context.actor(), validReservationId);
                    return AgentToolResult.of(Map.of(
                            "reservationId", value.getReservationId(),
                            "reservationNo", value.getReservationNo(),
                            "resourceName", value.getResourceName(),
                            "canCancel", value.isCanCancel(),
                            "requiresUserConfirmation", value.isRequiresUserConfirmation(),
                            "writeExecuted", value.isWriteExecuted(),
                            "nextAction", value.getNextAction()), Map.of(
                            "result_type", "CANCELLATION_PREVIEW",
                            "reservationId", value.getReservationId(),
                            "writeExecuted", value.isWriteExecuted()));
                });
    }

    @Tool(name = "reservation_create_draft", description = "为当前登录用户生成预约草案，不会创建预约或扣减库存。仅当 resourceId 与 slotId 已由用户明确提供，或刚由 resource_availability 返回时调用。调用后必须展示草案并等待用户在界面显式确认；禁止自行确认或声称预约已创建。")
    public Map<String, Object> reservationCreateDraft(
            @ToolParam(description = "资源 ID") Long resourceId,
            @ToolParam(description = "时段 ID") Long slotId,
            ToolContext toolContext) {
        AgentContext context = runtime.requireExecutionContext(toolContext);
        return runtime.execute("reservation_create_draft", "SELF_WRITE_DRAFT",
                arguments("resourceId", resourceId, "slotId", slotId), context, () -> {
                    Long validResourceId = requiredPositive(resourceId, "resourceId");
                    Long validSlotId = requiredPositive(slotId, "slotId");
                    ReservationDraftVO draft = reservationDraftService.createDraft(
                            context.actor(), validResourceId, validSlotId);
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("confirmationToken", draft.getConfirmationToken());
                    output.put("resourceId", draft.getResourceId());
                    output.put("resourceName", draft.getResourceName());
                    output.put("location", draft.getLocation());
                    output.put("slotId", draft.getSlotId());
                    output.put("startDatetime", draft.getStartDatetime());
                    output.put("endDatetime", draft.getEndDatetime());
                    output.put("remainQuota", draft.getRemainQuota());
                    output.put("expiresAt", draft.getExpiresAt());
                    output.put("writeExecuted", false);
                    output.put("requiresUserConfirmation", true);
                    output.put("confirmationEndpoint", draft.getConfirmationEndpoint());
                    output.put("nextAction", draft.getNextAction());
                    return AgentToolResult.of(output, Map.of(
                            "result_type", "RESERVATION_DRAFT",
                            "resource_id", validResourceId,
                            "slot_id", validSlotId,
                            "write_executed", false,
                            "requires_user_confirmation", true));
                });
    }

    private Map<String, Object> arguments(Object... keyValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < keyValues.length; index += 2) {
            if (keyValues[index + 1] != null) {
                result.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
            }
        }
        return result;
    }

    private String optionalText(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String text = value.trim();
        return text.substring(0, Math.min(text.length(), maxLength));
    }

    private Long requiredPositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new BusinessException("工具参数 " + field + " 必须是正整数");
        }
        return value;
    }
}
