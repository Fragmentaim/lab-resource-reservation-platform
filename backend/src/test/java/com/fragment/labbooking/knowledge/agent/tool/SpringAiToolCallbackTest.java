package com.fragment.labbooking.knowledge.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.knowledge.agent.AgentExecutionContext;
import com.fragment.labbooking.knowledge.agent.AgentState;
import com.fragment.labbooking.knowledge.agent.PolicyContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiToolCallbackTest {

    @Test
    void shouldExposeExistingToolSchemaAndDelegateExecution() {
        AgentTool tool = mock(AgentTool.class);
        AgentToolExecutor executor = mock(AgentToolExecutor.class);
        LoginUser actor = new LoginUser(7L, "user7", "用户", "USER", "13800000000");
        AgentExecutionContext context = new AgentExecutionContext(actor, "查询预约", "trace-1",
                new AgentState("trace-1", "session-1", PolicyContext.from(actor)), true, 8);
        when(tool.name()).thenReturn("reservation_context");
        when(tool.definition()).thenReturn(Map.of("type", "function", "function", Map.of(
                "name", "reservation_context",
                "description", "读取当前用户预约",
                "parameters", Map.of("type", "object", "properties", Map.of(),
                        "additionalProperties", false))));
        when(executor.execute(tool, "{}", context)).thenReturn("{\"count\":2}");

        SpringAiToolCallback callback = new SpringAiToolCallback(tool, executor, context, new ObjectMapper());

        assertThat(callback.getToolDefinition().name()).isEqualTo("reservation_context");
        assertThat(callback.getToolDefinition().description()).isEqualTo("读取当前用户预约");
        assertThat(callback.getToolDefinition().inputSchema()).contains("additionalProperties");
        assertThat(callback.call("{}")).isEqualTo("{\"count\":2}");
        verify(executor).execute(tool, "{}", context);
    }
}
