package com.fragment.labbooking.knowledge.agent.tool;

import com.fragment.labbooking.knowledge.agent.PolicyContext;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Collections;
import java.util.Map;

/**
 * The only registry that exposes tools to model planning and resolves a model
 * selection for execution. It prevents a model from invoking a tool that was
 * not available for the authenticated run policy.
 */
@Component
public class AgentToolRegistry {

    private final Map<String, AgentTool> toolsByName;

    public AgentToolRegistry(List<AgentTool> tools) {
        Map<String, AgentTool> collected = new LinkedHashMap<>();
        tools.stream()
                .sorted(Comparator.comparing(AgentTool::name))
                .forEach(tool -> {
                    AgentTool previous = collected.putIfAbsent(tool.name(), tool);
                    if (previous != null) {
                        throw new IllegalStateException("Duplicate agent tool name: " + tool.name());
                    }
                });
        this.toolsByName = Collections.unmodifiableMap(new LinkedHashMap<>(collected));
    }

    public List<AgentTool> toolsFor(PolicyContext policy) {
        return toolsByName.values().stream()
                .filter(tool -> tool.isAvailableFor(policy))
                .toList();
    }

}
