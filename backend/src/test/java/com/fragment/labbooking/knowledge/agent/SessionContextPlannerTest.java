package com.fragment.labbooking.knowledge.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SessionContextPlannerTest {

    private final SessionContextPlanner planner = new SessionContextPlanner(new ContextTokenCounter());

    @Test
    void shouldKeepWholeNewestTurnsAndRecommendCompactionWhenOlderTurnsDoNotFit() {
        List<SessionTurn> turns = List.of(
                new SessionTurn(1L, "trace-1", "第一轮问题".repeat(40), "第一轮回答".repeat(40)),
                new SessionTurn(2L, "trace-2", "第二轮问题", "第二轮回答"),
                new SessionTurn(3L, "trace-3", "第三轮问题", "第三轮回答")
        );

        SessionContextPlan plan = planner.plan("已有摘要", "继续解释第三轮", turns,
                new SessionContextPlanner.ContextCapacity(400, 100, 180, 120));

        assertThat(plan.includedTurns()).extracting(SessionTurn::recordId).containsExactly(2L, 3L);
        assertThat(plan.historyMessages()).extracting(message -> message.role())
                .containsExactly("user", "assistant", "user", "assistant");
        assertThat(plan.deferredTurnCount()).isEqualTo(1);
        assertThat(plan.deferredTurns()).extracting(SessionTurn::recordId).containsExactly(1L);
        assertThat(plan.compactionRecommended()).isTrue();
        assertThat(plan.safeDetail()).containsEntry("selection_unit", "COMPLETE_TURN");
        assertThat(plan.safeDetail()).containsEntry("history_shape", "CONTIGUOUS_RECENT_SUFFIX");
    }

    @Test
    void shouldAvoidPartialTurnWhenTheAvailableHistoryBudgetCannotFitIt() {
        SessionTurn oversized = new SessionTurn(1L, "trace-1", "问题".repeat(300), "回答".repeat(300));

        SessionContextPlan plan = planner.plan("", "新问题", List.of(oversized),
                new SessionContextPlanner.ContextCapacity(180, 100, 80, 0));

        assertThat(plan.historyMessages()).isEmpty();
        assertThat(plan.deferredTurnCount()).isEqualTo(1);
        assertThat(plan.compactionRecommended()).isTrue();
    }

    @Test
    void shouldNotSkipAnOversizedMiddleTurnToIncludeOlderHistory() {
        List<SessionTurn> turns = List.of(
                new SessionTurn(1L, "trace-1", "第一轮问题", "第一轮回答"),
                new SessionTurn(2L, "trace-2", "第二轮问题".repeat(180), "第二轮回答".repeat(180)),
                new SessionTurn(3L, "trace-3", "第三轮问题", "第三轮回答")
        );

        SessionContextPlan plan = planner.plan("", "继续说第三轮", turns,
                new SessionContextPlanner.ContextCapacity(800, 100, 220, 0));

        assertThat(plan.includedTurns()).extracting(SessionTurn::recordId).containsExactly(3L);
        assertThat(plan.deferredTurns()).extracting(SessionTurn::recordId).containsExactly(1L, 2L);
    }
}
