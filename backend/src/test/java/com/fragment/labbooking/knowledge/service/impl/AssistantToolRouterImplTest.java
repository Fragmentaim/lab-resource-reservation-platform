package com.fragment.labbooking.knowledge.service.impl;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.knowledge.service.AiToolCallAuditService;
import com.fragment.labbooking.knowledge.service.ReservationCancellationPreviewToolService;
import com.fragment.labbooking.knowledge.service.ReservationContextToolService;
import com.fragment.labbooking.knowledge.service.ResourceAvailabilityToolService;
import com.fragment.labbooking.knowledge.service.ToolRouteResult;
import com.fragment.labbooking.knowledge.vo.ReservationAssistantContextVO;
import com.fragment.labbooking.knowledge.vo.ResourceAvailabilityToolVO;
import com.fragment.labbooking.knowledge.vo.AvailableResourceSlotVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantToolRouterImplTest {

    @Mock private ReservationContextToolService contextTool;
    @Mock private ResourceAvailabilityToolService availabilityTool;
    @Mock private ReservationCancellationPreviewToolService cancellationTool;
    @Mock private AiToolCallAuditService auditService;
    private AssistantToolRouterImpl router;

    @BeforeEach
    void setUp() {
        router = new AssistantToolRouterImpl();
        ReflectionTestUtils.setField(router, "reservationContextToolService", contextTool);
        ReflectionTestUtils.setField(router, "resourceAvailabilityToolService", availabilityTool);
        ReflectionTestUtils.setField(router, "cancellationPreviewToolService", cancellationTool);
        ReflectionTestUtils.setField(router, "aiToolCallAuditService", auditService);
    }

    @Test
    void shouldRouteAvailabilityQuestionToReadOnlyToolAndExposeTrace() {
        ResourceAvailabilityToolVO availability = new ResourceAvailabilityToolVO();
        AvailableResourceSlotVO slot = new AvailableResourceSlotVO();
        slot.setResourceName("机器人实验室");
        slot.setStartDatetime(LocalDateTime.of(2026, 7, 20, 9, 0));
        slot.setRemainQuota(2);
        availability.setSlots(List.of(slot));
        when(availabilityTool.findAvailableSlots(null, 3)).thenReturn(availability);

        ToolRouteResult result = router.route("现在有什么可用实验室时段？", user()).orElseThrow();

        assertThat(result.answer()).contains("机器人实验室").contains("剩余 2 个名额");
        assertThat(result.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.get("tool_name")).isEqualTo("resource_availability");
            assertThat(call.get("result")).isEqualTo("SUCCESS");
        });
        verify(auditService).recordSuccess(any(), any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void shouldRouteMyReservationQuestionInsteadOfCallingRag() {
        ReservationAssistantContextVO context = new ReservationAssistantContextVO();
        context.setActiveReservationCount(2);
        context.setUpcomingReservations(List.of());
        when(contextTool.getReservationContext(any(), any())).thenReturn(context);

        ToolRouteResult result = router.route("我的预约还有哪些？", user()).orElseThrow();

        assertThat(result.answer()).contains("2 个进行中预约");
        assertThat(result.toolCalls()).singleElement().satisfies(call ->
                assertThat(call.get("tool_name")).isEqualTo("reservation_context"));
    }

    private LoginUser user() {
        return new LoginUser(7L, "user7", "用户7", "USER", "13800000000");
    }
}
