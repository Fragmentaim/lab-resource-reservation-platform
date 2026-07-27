package com.fragment.labbooking.knowledge.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.knowledge.agent.AgentContext;
import com.fragment.labbooking.knowledge.service.AiServiceClient;
import com.fragment.labbooking.knowledge.service.KbDocumentService;
import com.fragment.labbooking.knowledge.service.ReservationCancellationPreviewToolService;
import com.fragment.labbooking.knowledge.service.ReservationContextToolService;
import com.fragment.labbooking.knowledge.service.ReservationDraftToolService;
import com.fragment.labbooking.knowledge.service.ResourceAvailabilityToolService;
import com.fragment.labbooking.knowledge.vo.ReservationDraftVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpringAiAnnotatedToolsTest {

    private AgentToolRuntime runtime;
    private ReservationDraftToolService draftService;
    private KbDocumentService documentService;
    private AiServiceClient aiServiceClient;
    private ReservationAgentTools reservationTools;
    private KnowledgeAgentTools knowledgeTools;
    private AgentContext execution;
    private ToolContext toolContext;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        runtime = mock(AgentToolRuntime.class);
        draftService = mock(ReservationDraftToolService.class);
        documentService = mock(KbDocumentService.class);
        aiServiceClient = mock(AiServiceClient.class);
        reservationTools = new ReservationAgentTools(runtime,
                mock(ReservationContextToolService.class),
                mock(ResourceAvailabilityToolService.class),
                mock(ReservationCancellationPreviewToolService.class),
                draftService);
        knowledgeTools = new KnowledgeAgentTools(runtime, documentService, aiServiceClient, new ObjectMapper());
        when(runtime.execute(anyString(), anyString(), anyMap(), any(), any())).thenAnswer(invocation -> {
            Supplier<AgentToolResult> action = invocation.getArgument(4);
            return action.get().output();
        });
        LoginUser actor = new LoginUser(7L, "user7", "用户", "USER", "13800000000");
        execution = new AgentContext(actor, "预约规则", "trace", 8);
        toolContext = new ToolContext(Map.of(AgentToolRuntime.EXECUTION_CONTEXT_KEY, execution));
        when(runtime.requireExecutionContext(any())).thenReturn(execution);
    }

    @Test
    void shouldLetSpringAiGenerateToolDefinitionsAndHideServerContext() {
        ToolCallback[] callbacks = ToolCallbacks.from(reservationTools, knowledgeTools);

        assertThat(Arrays.stream(callbacks).map(callback -> callback.getToolDefinition().name()))
                .containsExactlyInAnyOrder("reservation_context", "resource_availability",
                        "reservation_cancellation_preview", "reservation_create_draft",
                        "knowledge_search", "knowledge_open_chunks");
        ToolCallback draft = callback(callbacks, "reservation_create_draft");
        assertThat(draft.getToolDefinition().description()).contains("不会创建预约").contains("显式确认");
        assertThat(draft.getToolDefinition().inputSchema())
                .contains("resourceId", "slotId")
                .doesNotContain("toolContext", AgentToolRuntime.EXECUTION_CONTEXT_KEY);
    }

    @Test
    void shouldCreateConfirmationOnlyDraft() {
        ReservationDraftVO draft = new ReservationDraftVO();
        draft.setConfirmationToken("0123456789abcdef0123456789abcdef");
        draft.setResourceId(11L);
        draft.setSlotId(22L);
        draft.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        draft.setConfirmationEndpoint("POST /knowledge/tools/reservation-drafts/token/confirm");
        draft.setNextAction("等待用户确认");
        when(draftService.createDraft(execution.actor(), 11L, 22L)).thenReturn(draft);

        String result = callback(ToolCallbacks.from(reservationTools, knowledgeTools), "reservation_create_draft")
                .call("{\"resourceId\":11,\"slotId\":22}", toolContext);

        assertThat(result).contains("\"writeExecuted\":false")
                .contains("\"requiresUserConfirmation\":true")
                .contains("0123456789abcdef0123456789abcdef");
    }

    @Test
    void shouldExposeOnlyCurrentAccessibleKnowledgeCandidates() {
        Map<Long, String> versions = Map.of(12L, "v2");
        when(documentService.listAccessibleDocumentVersions(execution.actor())).thenReturn(versions);
        when(aiServiceClient.retrieveKnowledge("预约规则", versions)).thenReturn(
                new AiServiceClient.KnowledgeSearchResult("预约规则", List.of(
                        candidate("current", 12L, "v2"),
                        candidate("stale", 12L, "v1"),
                        candidate("forbidden", 99L, "v2"))));

        Map<String, Object> result = knowledgeTools.knowledgeSearch("预约规则", toolContext);

        List<?> candidates = (List<?>) result.get("candidates");
        assertThat(candidates).hasSize(1);
        assertThat(((Map<?, ?>) candidates.get(0)).get("chunk_uid")).isEqualTo("current");
        assertThat(execution.authorizeKnowledgeChunks(List.of("current", "stale", "forbidden")))
                .containsExactly("current");
    }

    @Test
    void shouldSerializeOpenedEvidenceWithStableJsonFields() {
        Map<Long, String> versions = Map.of(12L, "v2");
        execution.registerKnowledgeCandidates(List.of(Map.entry("chunk-1", 12L)));
        when(documentService.listAccessibleDocumentVersions(execution.actor())).thenReturn(versions);
        when(aiServiceClient.openKnowledgeChunks(List.of("chunk-1"), versions)).thenReturn(List.of(
                new AiServiceClient.KnowledgeChunk(
                        "chunk-1", 12L, "v2", 0, 3, "规则", List.of("比赛", "雷达"),
                        "hash-1", 120, "证据正文")));

        Map<String, Object> result = knowledgeTools.knowledgeOpenChunks(List.of("chunk-1"), toolContext);

        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) ((List<?>) result.get("chunks")).get(0);
        assertThat(evidence).containsEntry("chunk_uid", "chunk-1")
                .containsEntry("section_title", "规则")
                .containsEntry("page_no", 3)
                .containsEntry("content", "证据正文");
    }

    private AiServiceClient.KnowledgeCandidate candidate(String chunkUid, Long documentId, String docVersion) {
        return new AiServiceClient.KnowledgeCandidate(
                chunkUid, documentId, docVersion, 0, 1, "规则", List.of("实验室制度"),
                "hash-" + chunkUid, 80, 0.9D, 0.8D, 0.7D,
                "local", "hybrid", "候选定位");
    }

    private ToolCallback callback(ToolCallback[] callbacks, String name) {
        return Arrays.stream(callbacks)
                .filter(callback -> callback.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
