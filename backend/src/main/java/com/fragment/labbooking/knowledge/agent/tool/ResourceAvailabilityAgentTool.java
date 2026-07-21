package com.fragment.labbooking.knowledge.agent.tool;

import com.fragment.labbooking.knowledge.service.ResourceAvailabilityToolService;
import com.fragment.labbooking.knowledge.vo.ResourceAvailabilityToolVO;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ResourceAvailabilityAgentTool implements AgentTool {

    private final ResourceAvailabilityToolService resourceAvailabilityToolService;

    public ResourceAvailabilityAgentTool(ResourceAvailabilityToolService resourceAvailabilityToolService) {
        this.resourceAvailabilityToolService = resourceAvailabilityToolService;
    }

    @Override
    public String name() {
        return "resource_availability";
    }

    @Override
    public String accessScope() {
        return "SELF_READ";
    }

    @Override
    public Map<String, Object> definition() {
        return Map.of("type", "function", "function", Map.of(
                "name", name(),
                "description", "查询未来开放且有剩余名额的实验室资源时段，只读。",
                "parameters", Map.of("type", "object", "properties", Map.of(
                        "keyword", Map.of("type", "string", "description", "可选资源名称关键词")
                ), "additionalProperties", false)
        ));
    }

    @Override
    public AgentToolResult execute(AgentToolInvocation invocation) {
        ResourceAvailabilityToolVO value = resourceAvailabilityToolService.findAvailableSlots(
                AgentToolArguments.optionalText(invocation.arguments().get("keyword"), 40), 5);
        return AgentToolResult.of(Map.of(
                "resultCount", value.getResultCount(),
                "slots", value.getSlots(),
                "generatedAt", value.getGeneratedAt()
        ));
    }
}
