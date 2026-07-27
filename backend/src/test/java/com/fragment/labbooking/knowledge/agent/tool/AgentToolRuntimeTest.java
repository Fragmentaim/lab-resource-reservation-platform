package com.fragment.labbooking.knowledge.agent.tool;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.knowledge.agent.AgentContext;
import com.fragment.labbooking.knowledge.service.AgentRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AgentToolRuntimeTest {

    @Mock private AgentRunService runService;
    private AgentToolRuntime runtime;
    private AgentContext context;

    @BeforeEach
    void setUp() {
        runtime = new AgentToolRuntime(runService, true);
        LoginUser actor = new LoginUser(7L, "user7", "用户", "USER", "13800000000");
        context = new AgentContext(actor, "查看我的预约", "trace-1", 8);
    }

    @Test
    void shouldRecordSuccessfulToolCall() {
        Map<String, Object> output = Map.of("activeCount", 2);

        Map<String, Object> result = runtime.execute("reservation_context", "SELF", Map.of(), context,
                () -> AgentToolResult.of(output));

        assertThat(result).containsEntry("activeCount", 2);
        assertThat(context.toolCallCount()).isEqualTo(1);
        assertThat(context.toolCalls()).singleElement().satisfies(trace ->
                assertThat(trace).containsEntry("protocol", "spring_ai_annotated_tool")
                        .containsEntry("result", "SUCCESS"));
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
