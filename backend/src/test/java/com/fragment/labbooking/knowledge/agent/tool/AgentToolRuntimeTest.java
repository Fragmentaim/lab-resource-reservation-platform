package com.fragment.labbooking.knowledge.agent.tool;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.knowledge.agent.context.ToolResultContextPacker;
import com.fragment.labbooking.knowledge.agent.model.PolicyContext;
import com.fragment.labbooking.knowledge.agent.runtime.AgentExecutionContext;
import com.fragment.labbooking.knowledge.agent.runtime.AgentState;
import com.fragment.labbooking.knowledge.service.AgentRunService;
import com.fragment.labbooking.knowledge.service.AiToolCallAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentToolRuntimeTest {

    @Mock private ToolResultContextPacker contextPacker;
    @Mock private AiToolCallAuditService auditService;
    @Mock private AgentRunService runService;
    private AgentToolRuntime runtime;
    private AgentExecutionContext context;

    @BeforeEach
    void setUp() {
        runtime = new AgentToolRuntime(contextPacker, auditService, runService, true);
        LoginUser actor = new LoginUser(7L, "user7", "用户", "USER", "13800000000");
        context = new AgentExecutionContext(actor, "查看我的预约", "trace-1",
                new AgentState("trace-1", "session-1", PolicyContext.from(actor)), true, 8);
    }

    @Test
    void shouldPackAndRecordSuccessfulToolCall() {
        Map<String, Object> output = Map.of("activeCount", 2);
        when(contextPacker.pack("reservation_context", output)).thenReturn(
                new ToolResultContextPacker.PackedToolResult(output, Map.of("strategy", "DIRECT")));

        Map<String, Object> result = runtime.execute("reservation_context", "SELF", Map.of(), context,
                () -> AgentToolResult.of(output));

        assertThat(result).containsEntry("activeCount", 2);
        assertThat(context.toolCallCount()).isEqualTo(1);
        assertThat(context.toolCalls()).singleElement().satisfies(trace ->
                assertThat(trace).containsEntry("protocol", "spring_ai_annotated_tool")
                        .containsEntry("result", "SUCCESS"));
        verify(auditService).recordSuccess(any(), eq("reservation_context"), eq(context.actor()),
                eq(7L), eq("SELF"), anyLong(), any());
        verify(runService, atLeastOnce()).recordRuntimeState(eq("trace-1"), eq(context.state()));
        verify(runService).recordToolExecution(eq("trace-1"), any());
    }

    @Test
    void shouldReturnStructuredRejectionSoModelCanClarify() {
        Map<String, Object> result = runtime.execute("reservation_context", "SELF", Map.of(), context,
                () -> { throw new BusinessException("缺少预约 ID"); });

        assertThat(result).containsEntry("error", "TOOL_EXECUTION_REJECTED")
                .containsEntry("message", "缺少预约 ID");
        assertThat(context.toolCalls()).singleElement().satisfies(trace ->
                assertThat(trace).containsEntry("result", "REJECTED"));
        verify(runService).recordToolExecution(eq("trace-1"), any());
    }
}
