package com.fragment.labbooking.knowledge.service.impl;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.knowledge.service.AiServiceClient;
import com.fragment.labbooking.knowledge.service.AiToolCallAuditService;
import com.fragment.labbooking.knowledge.service.KbDocumentService;
import com.fragment.labbooking.knowledge.service.NativeToolCallingClient;
import com.fragment.labbooking.knowledge.service.ReservationCancellationPreviewToolService;
import com.fragment.labbooking.knowledge.service.ReservationContextToolService;
import com.fragment.labbooking.knowledge.service.ResourceAvailabilityToolService;
import com.fragment.labbooking.knowledge.service.ToolRouteResult;
import com.fragment.labbooking.knowledge.vo.ReservationAssistantContextVO;
import com.fragment.labbooking.knowledge.vo.QaAnswerVO;
import com.fragment.labbooking.knowledge.vo.QaSourceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NativeToolCallingServiceImplTest {

    @Mock private NativeToolCallingClient nativeClient;
    @Mock private ReservationContextToolService contextTool;
    @Mock private ResourceAvailabilityToolService availabilityTool;
    @Mock private ReservationCancellationPreviewToolService cancellationTool;
    @Mock private AiToolCallAuditService auditService;
    @Mock private KbDocumentService kbDocumentService;
    @Mock private AiServiceClient aiServiceClient;
    private NativeToolCallingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NativeToolCallingServiceImpl();
        ReflectionTestUtils.setField(service, "nativeToolCallingClient", nativeClient);
        ReflectionTestUtils.setField(service, "reservationContextToolService", contextTool);
        ReflectionTestUtils.setField(service, "resourceAvailabilityToolService", availabilityTool);
        ReflectionTestUtils.setField(service, "cancellationPreviewToolService", cancellationTool);
        ReflectionTestUtils.setField(service, "aiToolCallAuditService", auditService);
        ReflectionTestUtils.setField(service, "kbDocumentService", kbDocumentService);
        ReflectionTestUtils.setField(service, "aiServiceClient", aiServiceClient);
        ReflectionTestUtils.setField(service, "enabled", true);
    }

    @Test
    void shouldExecuteModelSelectedWhitelistedToolThenReturnModelAnswer() {
        NativeToolCallingClient.PlannedToolCall toolCall = new NativeToolCallingClient.PlannedToolCall(
                "call_context_1", "reservation_context", Map.of());
        when(nativeClient.nextRound(any(), any(), any())).thenReturn(
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
        NativeToolCallingClient.PlannedToolCall toolCall = new NativeToolCallingClient.PlannedToolCall(
                "call_knowledge_1", "knowledge_search", Map.of("query", "取消预约的规则"));
        when(nativeClient.nextRound(any(), any(), any())).thenReturn(
                new NativeToolCallingClient.ToolRound(List.of(toolCall), null, "glm-5.1"),
                new NativeToolCallingClient.ToolRound(List.of(), "取消需要提前操作。", "glm-5.1")
        );
        when(kbDocumentService.listAccessibleReadyDocumentIds(any())).thenReturn(List.of(12L));
        QaSourceVO source = new QaSourceVO();
        source.setDocumentTitle("预约管理制度");
        source.setSectionTitle("取消规则");
        source.setExcerpt("取消预约需要在开始前完成。");
        source.setScore(0.96D);
        QaAnswerVO ragAnswer = new QaAnswerVO();
        ragAnswer.setAnswer("预约开始前可以取消，具体以制度为准。");
        ragAnswer.setSources(List.of(source));
        when(aiServiceClient.askQuestion(eq("取消预约的规则"), eq(""), eq(List.of(12L)))).thenReturn(ragAnswer);

        ToolRouteResult result = service.tryAnswer("取消预约有什么规则？", user()).orElseThrow();

        assertThat(result.answer()).isEqualTo("取消需要提前操作。");
        assertThat(result.sourceCount()).isEqualTo(1);
        assertThat(result.toolCalls()).singleElement().satisfies(trace ->
                assertThat(trace.get("tool_name")).isEqualTo("knowledge_search"));
        verify(aiServiceClient).askQuestion("取消预约的规则", "", List.of(12L));
        verify(auditService).recordSuccess(any(), eq("knowledge_search"), any(), any(),
                eq("ACL_FILTERED_KNOWLEDGE"), anyLong(), any());
        verify(nativeClient, atLeastOnce()).nextRound(any(), any(), any());
    }

    private LoginUser user() {
        return new LoginUser(7L, "user7", "用户7", "USER", "13800000000");
    }
}
