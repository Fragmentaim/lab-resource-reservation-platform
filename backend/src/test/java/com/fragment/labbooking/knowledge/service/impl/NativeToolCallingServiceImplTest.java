package com.fragment.labbooking.knowledge.service.impl;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.knowledge.agent.ContextTokenCounter;
import com.fragment.labbooking.knowledge.agent.ModelContextProfileProperties;
import com.fragment.labbooking.knowledge.agent.AgentState;
import com.fragment.labbooking.knowledge.agent.AgentToolExecution;
import com.fragment.labbooking.knowledge.agent.PolicyContext;
import com.fragment.labbooking.knowledge.agent.ToolResultContextPacker;
import com.fragment.labbooking.knowledge.agent.tool.AgentToolRegistry;
import com.fragment.labbooking.knowledge.agent.tool.KnowledgeOpenChunksAgentTool;
import com.fragment.labbooking.knowledge.agent.tool.KnowledgeSearchAgentTool;
import com.fragment.labbooking.knowledge.agent.tool.ReservationCancellationPreviewAgentTool;
import com.fragment.labbooking.knowledge.agent.tool.ReservationContextAgentTool;
import com.fragment.labbooking.knowledge.agent.tool.ResourceAvailabilityAgentTool;
import com.fragment.labbooking.knowledge.service.AiServiceClient;
import com.fragment.labbooking.knowledge.service.AiToolCallAuditService;
import com.fragment.labbooking.knowledge.service.AgentRunService;
import com.fragment.labbooking.knowledge.service.KbDocumentService;
import com.fragment.labbooking.knowledge.service.NativeToolCallingClient;
import com.fragment.labbooking.knowledge.service.ReservationCancellationPreviewToolService;
import com.fragment.labbooking.knowledge.service.ReservationContextToolService;
import com.fragment.labbooking.knowledge.service.ResourceAvailabilityToolService;
import com.fragment.labbooking.knowledge.service.ToolRouteResult;
import com.fragment.labbooking.knowledge.vo.ReservationAssistantContextVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NativeToolCallingServiceImplTest {

    @Mock private NativeToolCallingClient nativeClient;
    @Mock private ReservationContextToolService contextTool;
    @Mock private ResourceAvailabilityToolService availabilityTool;
    @Mock private ReservationCancellationPreviewToolService cancellationTool;
    @Mock private AiToolCallAuditService auditService;
    @Mock private AgentRunService agentRunService;
    @Mock private KbDocumentService kbDocumentService;
    @Mock private AiServiceClient aiServiceClient;
    private NativeToolCallingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NativeToolCallingServiceImpl();
        ReflectionTestUtils.setField(service, "nativeToolCallingClient", nativeClient);
        ReflectionTestUtils.setField(service, "aiToolCallAuditService", auditService);
        ReflectionTestUtils.setField(service, "agentRunService", agentRunService);
        ReflectionTestUtils.setField(service, "agentToolRegistry", new AgentToolRegistry(List.of(
                new ReservationContextAgentTool(contextTool),
                new ResourceAvailabilityAgentTool(availabilityTool),
                new ReservationCancellationPreviewAgentTool(cancellationTool),
                new KnowledgeSearchAgentTool(kbDocumentService, aiServiceClient),
                new KnowledgeOpenChunksAgentTool(kbDocumentService, aiServiceClient)
        )));
        ReflectionTestUtils.setField(service, "toolResultContextPacker", new ToolResultContextPacker(
                new ContextTokenCounter(), new ModelContextProfileProperties()));
        ReflectionTestUtils.setField(service, "enabled", true);
    }

    @Test
    void shouldExecuteModelSelectedWhitelistedToolThenReturnModelAnswer() {
        NativeToolCallingClient.PlannedToolCall toolCall = new NativeToolCallingClient.PlannedToolCall(
                "call_context_1", "reservation_context", Map.of());
        when(nativeClient.nextRound(any(), any(), any(), any())).thenReturn(
                new NativeToolCallingClient.ToolRound(List.of(toolCall), null, "glm-5.1"),
                new NativeToolCallingClient.ToolRound(List.of(), "你当前有 2 个进行中预约。", "glm-5.1")
        );
        ReservationAssistantContextVO context = new ReservationAssistantContextVO();
        context.setActiveReservationCount(2);
        context.setUpcomingReservations(List.of());
        when(contextTool.getReservationContext(any(), any())).thenReturn(context);

        ToolRouteResult result = service.tryAnswer("我的预约还有哪些？", user()).orElseThrow();

        assertThat(result.answer()).isEqualTo("你当前有 2 个进行中预约。");
        assertThat(result.toolCalls()).singleElement().satisfies(trace -> {
            assertThat(trace.get("tool_name")).isEqualTo("reservation_context");
            assertThat(trace.get("protocol")).isEqualTo("native_function_calling");
            assertThat(trace.get("result")).isEqualTo("SUCCESS");
        });
        verify(auditService).recordSuccess(any(), any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void shouldExposeAclFilteredKnowledgeAsAnOptionalNativeTool() {
        NativeToolCallingClient.PlannedToolCall searchCall = new NativeToolCallingClient.PlannedToolCall(
                "call_knowledge_1", "knowledge_search", Map.of("query", "取消预约的规则"));
        NativeToolCallingClient.PlannedToolCall openCall = new NativeToolCallingClient.PlannedToolCall(
                "call_knowledge_2", "knowledge_open_chunks", Map.of("chunkUids", List.of("chunk-12-3")));
        when(nativeClient.nextRound(any(), any(), any(), any())).thenReturn(
                new NativeToolCallingClient.ToolRound(List.of(searchCall), null, "glm-5.1"),
                new NativeToolCallingClient.ToolRound(List.of(openCall), null, "glm-5.1"),
                new NativeToolCallingClient.ToolRound(List.of(), "取消需要提前操作。", "glm-5.1")
        );
        when(kbDocumentService.listAccessibleReadyDocumentIds(any())).thenReturn(List.of(12L));
        AiServiceClient.KnowledgeCandidate candidate = new AiServiceClient.KnowledgeCandidate(
                "chunk-12-3", 12L, "v1", 3, 2, "取消规则", List.of("预约管理制度"),
                "hash", 120, 0.96D, 0.90D, 0.95D, "local", "hybrid", "提前取消的要求");
        when(aiServiceClient.retrieveKnowledge("取消预约的规则", List.of(12L)))
                .thenReturn(new AiServiceClient.KnowledgeSearchResult("取消预约的规则", List.of(candidate)));
        AiServiceClient.KnowledgeChunk chunk = new AiServiceClient.KnowledgeChunk(
                "chunk-12-3", 12L, "v1", 3, 2, "取消规则", List.of("预约管理制度"),
                "hash", 120, "取消预约需要在开始前完成。");
        when(aiServiceClient.openKnowledgeChunks(List.of("chunk-12-3"), List.of(12L))).thenReturn(List.of(chunk));

        ToolRouteResult result = service.tryAnswer("取消预约有什么规则？", user()).orElseThrow();

        assertThat(result.answer()).isEqualTo("取消需要提前操作。");
        assertThat(result.sourceCount()).isEqualTo(1);
        assertThat(result.sources()).singleElement().satisfies(source -> {
            assertThat(source.getChunkUid()).isEqualTo("chunk-12-3");
            assertThat(source.getDocumentId()).isEqualTo(12L);
        });
        assertThat(result.toolCalls()).extracting(trace -> trace.get("tool_name"))
                .containsExactly("knowledge_search", "knowledge_open_chunks");
        verify(aiServiceClient).retrieveKnowledge("取消预约的规则", List.of(12L));
        verify(aiServiceClient).openKnowledgeChunks(List.of("chunk-12-3"), List.of(12L));
        verify(auditService).recordSuccess(any(), eq("knowledge_search"), any(), any(),
                eq("ACL_FILTERED_KNOWLEDGE"), anyLong(), any());
        verify(nativeClient, atLeastOnce()).nextRound(any(), any(), any(), any());
    }

    @Test
    void shouldReuseSameActorCheckpointBeforePlanningAResumedToolRound() {
        AgentState checkpoint = new AgentState("trace-1", "session-1", new PolicyContext(7L, "USER", false));
        checkpoint.registerKnowledgeCandidates(List.of(Map.entry("chunk-12-3", 12L)));
        when(agentRunService.restoreRuntimeCheckpoint(eq("trace-1"), any())).thenReturn(Optional.of(checkpoint));
        NativeToolCallingClient.PlannedToolCall openCall = new NativeToolCallingClient.PlannedToolCall(
                "call_open", "knowledge_open_chunks", Map.of("chunkUids", List.of("chunk-12-3")));
        when(nativeClient.nextRound(any(), any(), any(), any())).thenReturn(
                new NativeToolCallingClient.ToolRound(List.of(openCall), null, "glm-5.1"),
                new NativeToolCallingClient.ToolRound(List.of(), "已读取恢复的证据。", "glm-5.1")
        );
        when(kbDocumentService.listAccessibleReadyDocumentIds(any())).thenReturn(List.of(12L));
        when(aiServiceClient.openKnowledgeChunks(List.of("chunk-12-3"), List.of(12L))).thenReturn(List.of());

        ToolRouteResult result = service.tryAnswer("继续上次问题", user(), "session-1", "trace-1").orElseThrow();

        assertThat(result.answer()).isEqualTo("已读取恢复的证据。");
        verify(agentRunService).restoreRuntimeCheckpoint(eq("trace-1"), any());
        verify(aiServiceClient).openKnowledgeChunks(List.of("chunk-12-3"), List.of(12L));
    }

    @Test
    void shouldCaptureSanitizedArgumentsOnlyWhenDedicatedEvaluationModeIsEnabled() {
        ReflectionTestUtils.setField(service, "captureEvaluationArguments", true);
        NativeToolCallingClient.PlannedToolCall searchCall = new NativeToolCallingClient.PlannedToolCall(
                "call_eval_1", "knowledge_search", Map.of(
                "query", "预约规则",
                "apiToken", "not-for-storage",
                "metadata", Map.of("authorization", "Bearer nested-secret", "region", "cn")));
        when(nativeClient.nextRound(any(), any(), any(), any())).thenReturn(
                new NativeToolCallingClient.ToolRound(List.of(searchCall), null, "glm-5.1"),
                new NativeToolCallingClient.ToolRound(List.of(), "已完成", "glm-5.1")
        );
        when(kbDocumentService.listAccessibleReadyDocumentIds(any())).thenReturn(List.of(12L));
        when(aiServiceClient.retrieveKnowledge("预约规则", List.of(12L)))
                .thenReturn(new AiServiceClient.KnowledgeSearchResult("预约规则", List.of()));

        service.tryAnswer("测试评测参数", user(), "session-eval", "trace-eval").orElseThrow();

        ArgumentCaptor<AgentToolExecution> execution = ArgumentCaptor.forClass(AgentToolExecution.class);
        verify(agentRunService).recordToolExecution(eq("trace-eval"), execution.capture());
        assertThat(execution.getValue().safeDetail().get("evaluation_arguments"))
                .isEqualTo(Map.of(
                        "query", "预约规则",
                        "apiToken", "<redacted>",
                        "metadata", Map.of("authorization", "<redacted>", "region", "cn")));

        ArgumentCaptor<String> auditSummary = ArgumentCaptor.forClass(String.class);
        verify(auditService).recordSuccess(any(), eq("knowledge_search"), any(), any(),
                eq("ACL_FILTERED_KNOWLEDGE"), anyLong(), auditSummary.capture());
        assertThat(auditSummary.getValue())
                .contains("apiToken=<redacted>", "authorization=<redacted>")
                .doesNotContain("not-for-storage", "nested-secret");
    }

    @Test
    void shouldPropagateUnexpectedToolFailureAfterAuditingIt() {
        NativeToolCallingClient.PlannedToolCall toolCall = new NativeToolCallingClient.PlannedToolCall(
                "call_context_failure", "reservation_context", Map.of());
        when(nativeClient.nextRound(any(), any(), any(), any())).thenReturn(
                new NativeToolCallingClient.ToolRound(List.of(toolCall), null, "glm-5.1")
        );
        when(contextTool.getReservationContext(any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.tryAnswer("我的预约还有哪些？", user()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");

        verify(auditService).recordFailure(any(), eq("reservation_context"), any(), any(),
                anyLong(), any(), eq("database unavailable"));
    }

    private LoginUser user() {
        return new LoginUser(7L, "user7", "用户7", "USER", "13800000000");
    }
}
