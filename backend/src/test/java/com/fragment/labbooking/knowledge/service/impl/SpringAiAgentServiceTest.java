package com.fragment.labbooking.knowledge.service.impl;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.knowledge.agent.tool.KnowledgeAgentTools;
import com.fragment.labbooking.knowledge.agent.tool.ReservationAgentTools;
import com.fragment.labbooking.knowledge.service.AgentChatService;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringAiAgentServiceTest {

    @Test
    @SuppressWarnings("unchecked")
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
        when(request.tools(any(Object[].class))).thenReturn(request);
        when(request.toolContext(any(Map.class))).thenReturn(request);
        when(request.call()).thenReturn(call);

        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("你当前有 2 个预约。"))),
                ChatResponseMetadata.builder().model("test-model")
                        .usage(new DefaultUsage(120, 20, 140)).build());
        when(call.chatClientResponse()).thenReturn(new ChatClientResponse(response, Map.of()));

        ReservationAgentTools reservationTools = mock(ReservationAgentTools.class);
        KnowledgeAgentTools knowledgeTools = mock(KnowledgeAgentTools.class);
        AgentRunService runService = mock(AgentRunService.class);
        SpringAiAgentService service = new SpringAiAgentService(
                builder, reservationTools, knowledgeTools, runService, 8);
        LoginUser actor = new LoginUser(7L, "user7", "用户", "USER", "13800000000");

        ToolRouteResult result = service.answer("查看我的预约", actor, "session-1", null,
                AgentChatService.ConversationContext.empty());

        assertThat(result.answer()).isEqualTo("你当前有 2 个预约。");
        assertThat(result.providerUsage()).containsEntry("model", "test-model")
                .containsEntry("total_tokens", 140L);
        verify(request).tools(any(Object[].class));
    }
}
