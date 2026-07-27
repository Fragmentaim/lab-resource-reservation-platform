package com.fragment.labbooking.knowledge.agent.tool;

import com.fragment.labbooking.knowledge.agent.PolicyContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentToolRegistryTest {

    @Test
    void shouldExposeOnlyToolsAllowedByRunPolicyAndKeepStableOrder() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(
                new TestTool("admin_only", true),
                new TestTool("reservation_context", false)
        ));

        PolicyContext userPolicy = new PolicyContext(7L, "USER", false);
        assertThat(toolNames(registry, userPolicy)).containsExactly("reservation_context");

        PolicyContext adminPolicy = new PolicyContext(1L, "ADMIN", true);
        assertThat(toolNames(registry, adminPolicy)).containsExactly("admin_only", "reservation_context");
    }

    @Test
    void shouldRejectDuplicateToolNamesAtStartup() {
        assertThatThrownBy(() -> new AgentToolRegistry(List.of(
                new TestTool("same_name", false), new TestTool("same_name", false)
        ))).isInstanceOf(IllegalStateException.class).hasMessageContaining("Duplicate agent tool name");
    }

    private record TestTool(String name, boolean adminOnly) implements AgentTool {

        @Override
        public String accessScope() {
            return "TEST";
        }

        @Override
        public Map<String, Object> definition() {
            return Map.of("type", "function", "function", Map.of("name", name));
        }

        @Override
        public boolean isAvailableFor(PolicyContext policy) {
            return AgentTool.super.isAvailableFor(policy) && (!adminOnly || policy.admin());
        }

        @Override
        public AgentToolResult execute(AgentToolInvocation invocation) {
            return AgentToolResult.of(Map.of());
        }
    }

    private List<String> toolNames(AgentToolRegistry registry, PolicyContext policy) {
        return registry.toolsFor(policy).stream()
                .map(AgentTool::name)
                .toList();
    }
}
