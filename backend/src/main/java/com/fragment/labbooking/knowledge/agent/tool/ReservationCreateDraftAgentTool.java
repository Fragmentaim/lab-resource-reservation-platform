package com.fragment.labbooking.knowledge.agent.tool;

import com.fragment.labbooking.knowledge.service.ReservationDraftToolService;
import com.fragment.labbooking.knowledge.vo.ReservationDraftVO;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ReservationCreateDraftAgentTool implements AgentTool {

    private final ReservationDraftToolService reservationDraftToolService;

    public ReservationCreateDraftAgentTool(ReservationDraftToolService reservationDraftToolService) {
        this.reservationDraftToolService = reservationDraftToolService;
    }

    @Override
    public String name() {
        return "reservation_create_draft";
    }

    @Override
    public String accessScope() {
        return "SELF_WRITE_DRAFT";
    }

    @Override
    public Map<String, Object> definition() {
        return Map.of("type", "function", "function", Map.of(
                "name", name(),
                "description", "为当前登录用户生成预约草案，不会创建预约或扣减库存。仅当 resourceId 与 slotId 已由用户明确提供，或刚由 resource_availability 返回时调用。调用后必须展示草案并等待用户在界面显式确认；禁止自行确认或声称预约已创建。",
                "parameters", Map.of("type", "object", "properties", Map.of(
                        "resourceId", Map.of("type", "integer", "description", "资源 ID"),
                        "slotId", Map.of("type", "integer", "description", "时段 ID")
                ), "required", List.of("resourceId", "slotId"), "additionalProperties", false)
        ));
    }

    @Override
    public AgentToolResult execute(AgentToolInvocation invocation) {
        Long resourceId = AgentToolArguments.requiredPositiveLong(invocation.arguments().get("resourceId"), "resourceId");
        Long slotId = AgentToolArguments.requiredPositiveLong(invocation.arguments().get("slotId"), "slotId");
        ReservationDraftVO draft = reservationDraftToolService.createDraft(invocation.actor(), resourceId, slotId);
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
                "resource_id", resourceId,
                "slot_id", slotId,
                "write_executed", false,
                "requires_user_confirmation", true
        ));
    }
}
