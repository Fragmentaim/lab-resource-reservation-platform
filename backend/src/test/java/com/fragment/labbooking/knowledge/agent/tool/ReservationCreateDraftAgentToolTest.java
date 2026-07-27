package com.fragment.labbooking.knowledge.agent.tool;

import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.knowledge.agent.AgentState;
import com.fragment.labbooking.knowledge.agent.PolicyContext;
import com.fragment.labbooking.knowledge.service.ReservationDraftToolService;
import com.fragment.labbooking.knowledge.vo.ReservationDraftVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReservationCreateDraftAgentToolTest {

    @Test
    void shouldExposeDraftOnlyToolAndRequireExplicitConfirmation() {
        ReservationDraftToolService service = mock(ReservationDraftToolService.class);
        ReservationDraftVO draft = new ReservationDraftVO();
        draft.setConfirmationToken("0123456789abcdef0123456789abcdef");
        draft.setResourceId(11L);
        draft.setSlotId(22L);
        draft.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        draft.setConfirmationEndpoint("POST /knowledge/tools/reservation-drafts/token/confirm");
        draft.setNextAction("等待用户确认");
        when(service.createDraft(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(11L),
                org.mockito.ArgumentMatchers.eq(22L))).thenReturn(draft);

        ReservationCreateDraftAgentTool tool = new ReservationCreateDraftAgentTool(service);
        var result = tool.execute(new AgentToolInvocation(user(), "帮我预约", state(),
                Map.of("resourceId", 11, "slotId", 22)));

        assertThat(tool.name()).isEqualTo("reservation_create_draft");
        assertThat(tool.accessScope()).isEqualTo("SELF_WRITE_DRAFT");
        assertThat(result.output()).containsEntry("writeExecuted", false)
                .containsEntry("requiresUserConfirmation", true)
                .containsEntry("confirmationToken", "0123456789abcdef0123456789abcdef");
        assertThat(((Map<?, ?>) tool.definition().get("function")).get("description").toString())
                .contains("不会创建预约").contains("等待用户");
    }

    private LoginUser user() {
        return new LoginUser(7L, "user7", "用户", "USER", "13800000000");
    }

    private AgentState state() {
        return new AgentState("trace", "session", PolicyContext.from(user()));
    }
}
