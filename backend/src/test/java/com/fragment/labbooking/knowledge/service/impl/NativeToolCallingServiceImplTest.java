package com.fragment.labbooking.knowledge.service.impl;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.knowledge.service.AiToolCallAuditService;
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
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NativeToolCallingServiceImplTest {

    @Mock private NativeToolCallingClient nativeClient;
    @Mock private ReservationContextToolService contextTool;
    @Mock private ResourceAvailabilityToolService availabilityTool;
    @Mock private ReservationCancellationPreviewToolService cancellationTool;
    @Mock private AiToolCallAuditService auditService;
    private NativeToolCallingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NativeToolCallingServiceImpl();
        ReflectionTestUtils.setField(service, "nativeToolCallingClient", nativeClient);
        ReflectionTestUtils.setField(service, "reservationContextToolService", contextTool);
        ReflectionTestUtils.setField(service, "resourceAvailabilityToolService", availabilityTool);
        ReflectionTestUtils.setField(service, "cancellationPreviewToolService", cancellationTool);
        ReflectionTestUtils.setField(service, "aiToolCallAuditService", auditService);
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

    private LoginUser user() {
        return new LoginUser(7L, "user7", "用户7", "USER", "13800000000");
    }
}
