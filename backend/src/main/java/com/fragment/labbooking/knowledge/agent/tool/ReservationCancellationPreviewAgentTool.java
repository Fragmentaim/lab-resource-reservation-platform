package com.fragment.labbooking.knowledge.agent.tool;

import com.fragment.labbooking.knowledge.service.ReservationCancellationPreviewToolService;
import com.fragment.labbooking.knowledge.vo.ReservationCancellationPreviewVO;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ReservationCancellationPreviewAgentTool implements AgentTool {

    private final ReservationCancellationPreviewToolService cancellationPreviewToolService;

    public ReservationCancellationPreviewAgentTool(ReservationCancellationPreviewToolService cancellationPreviewToolService) {
        this.cancellationPreviewToolService = cancellationPreviewToolService;
    }

    @Override
    public String name() {
        return "reservation_cancellation_preview";
    }

    @Override
    public String accessScope() {
        return "SELF_READ";
    }

    @Override
    public Map<String, Object> definition() {
        return Map.of("type", "function", "function", Map.of(
                "name", name(),
                "description", "仅预检当前登录用户自己的指定预约能否取消；不会执行取消。必须先有预约 ID。",
                "parameters", Map.of("type", "object", "properties", Map.of(
                        "reservationId", Map.of("type", "integer", "description", "预约 ID")
                ), "required", java.util.List.of("reservationId"), "additionalProperties", false)
        ));
    }

    @Override
    public AgentToolResult execute(AgentToolInvocation invocation) {
        ReservationCancellationPreviewVO value = cancellationPreviewToolService.preview(
                invocation.actor(), AgentToolArguments.requiredPositiveLong(invocation.arguments().get("reservationId"), "reservationId"));
        return AgentToolResult.of(Map.of(
                "reservationId", value.getReservationId(),
                "reservationNo", value.getReservationNo(),
                "resourceName", value.getResourceName(),
                "canCancel", value.isCanCancel(),
                "requiresUserConfirmation", value.isRequiresUserConfirmation(),
                "writeExecuted", value.isWriteExecuted(),
                "nextAction", value.getNextAction()
        ));
    }
}
