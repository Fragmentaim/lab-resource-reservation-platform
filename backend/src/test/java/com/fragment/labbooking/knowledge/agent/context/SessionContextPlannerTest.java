package com.fragment.labbooking.knowledge.agent.context;

import com.fragment.labbooking.knowledge.agent.model.SessionContextPlan;
import com.fragment.labbooking.knowledge.agent.model.SessionTurn;
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
                new SessionContextPlanner.ContextCapacity(400, 100, 20, 0.70D));

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
                new SessionContextPlanner.ContextCapacity(180, 100, 10, 0.70D));

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
                new SessionContextPlanner.ContextCapacity(800, 100, 10, 0.70D));

        assertThat(plan.includedTurns()).extracting(SessionTurn::recordId).containsExactly(3L);
        assertThat(plan.deferredTurns()).extracting(SessionTurn::recordId).containsExactly(1L, 2L);
    }

    @Test
    void shouldKeepAllRawHistoryUntilTheConfiguredLargeWindowIsActuallyFull() {
        List<SessionTurn> turns = List.of(
                new SessionTurn(1L, "trace-1", "问题一".repeat(4000), "回答一".repeat(4000)),
                new SessionTurn(2L, "trace-2", "问题二".repeat(4000), "回答二".repeat(4000))
        );

        SessionContextPlan plan = planner.plan("", "继续", turns,
                new SessionContextPlanner.ContextCapacity(256_000, 8_000, 4_096, 0.70D));

        assertThat(plan.includedTurns()).extracting(SessionTurn::recordId).containsExactly(1L, 2L);
        assertThat(plan.deferredTurns()).isEmpty();
        assertThat(plan.compactionRecommended()).isFalse();
    }
}
