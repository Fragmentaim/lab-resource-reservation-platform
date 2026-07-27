package com.fragment.labbooking.knowledge.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.knowledge.agent.AgentConversationContext;
import com.fragment.labbooking.knowledge.agent.PolicyContext;
import com.fragment.labbooking.knowledge.agent.tool.AgentTool;
import com.fragment.labbooking.knowledge.agent.tool.AgentToolExecutor;
import com.fragment.labbooking.knowledge.agent.tool.AgentToolInvocation;
import com.fragment.labbooking.knowledge.agent.tool.AgentToolRegistry;
import com.fragment.labbooking.knowledge.agent.tool.AgentToolResult;
import com.fragment.labbooking.knowledge.service.AgentRunService;
import com.fragment.labbooking.knowledge.service.ToolRouteResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiAgentServiceTest {

    @Test
    void shouldSendPermissionScopedCallbacksAndReturnProviderUsage() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(request);
        when(request.system(any(String.class))).thenReturn(request);
        when(request.messages(any(List.class))).thenReturn(request);
        when(request.user(any(String.class))).thenReturn(request);
        when(request.toolCallbacks(any(List.class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.call()).thenReturn(call);

        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("你当前有 2 个预约。"))),
                ChatResponseMetadata.builder().model("test-model")
                        .usage(new DefaultUsage(120, 20, 140)).build());
        when(call.chatClientResponse()).thenReturn(new ChatClientResponse(response, Map.of()));

        AgentToolExecutor executor = mock(AgentToolExecutor.class);
        AgentRunService runService = mock(AgentRunService.class);
        SpringAiAgentService service = new SpringAiAgentService(
                builder, new AgentToolRegistry(List.of(new TestTool())), executor,
                runService, new ObjectMapper(), 8);
        LoginUser actor = new LoginUser(7L, "user7", "用户", "USER", "13800000000");

        ToolRouteResult result = service.answer("查看我的预约", actor, "session-1", null,
                AgentConversationContext.empty());

        assertThat(result.answer()).isEqualTo("你当前有 2 个预约。");
        assertThat(result.providerUsage()).containsEntry("model", "test-model")
                .containsEntry("total_tokens", 140L);
        verify(request).toolCallbacks(any(List.class));
    }

    private static final class TestTool implements AgentTool {
        @Override public String name() { return "reservation_context"; }
        @Override public String accessScope() { return "SELF"; }
        @Override public boolean isAvailableFor(PolicyContext policy) { return policy.userId() != null; }
        @Override public Map<String, Object> definition() {
            return Map.of("type", "function", "function", Map.of(
                    "name", name(), "description", "读取当前用户预约",
                    "parameters", Map.of("type", "object", "properties", Map.of())));
        }
        @Override public AgentToolResult execute(AgentToolInvocation invocation) {
            return AgentToolResult.of(Map.of("activeCount", 2));
        }
    }
}
