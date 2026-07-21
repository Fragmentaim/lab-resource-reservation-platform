package com.fragment.labbooking.knowledge.agent.tool;

import com.fragment.labbooking.knowledge.service.ReservationContextToolService;
import com.fragment.labbooking.knowledge.vo.ReservationAssistantContextVO;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ReservationContextAgentTool implements AgentTool {

    private final ReservationContextToolService reservationContextToolService;

    public ReservationContextAgentTool(ReservationContextToolService reservationContextToolService) {
        this.reservationContextToolService = reservationContextToolService;
    }

    @Override
    public String name() {
        return "reservation_context";
    }

    @Override
    public String accessScope() {
        return "SELF_READ";
    }

    @Override
    public Map<String, Object> definition() {
        return Map.of("type", "function", "function", Map.of(
                "name", name(),
                "description", "读取当前登录用户自己的预约统计与未来预约，只读。",
                "parameters", Map.of("type", "object", "properties", Map.of(), "additionalProperties", false)
        ));
    }

    @Override
    public AgentToolResult execute(AgentToolInvocation invocation) {
        ReservationAssistantContextVO value = reservationContextToolService.getReservationContext(
                invocation.actor(), invocation.actor().getId());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("activeReservationCount", value.getActiveReservationCount());
        output.put("upcomingReservations", value.getUpcomingReservations());
        output.put("generatedAt", value.getGeneratedAt());
        return new AgentToolResult(output, 0, Map.of(
                "result_type", "RESERVATION_CONTEXT",
                "actor_user_id", invocation.actor().getId()
        ));
    }
}
