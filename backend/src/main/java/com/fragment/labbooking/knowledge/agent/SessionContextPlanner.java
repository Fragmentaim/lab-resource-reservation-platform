package com.fragment.labbooking.knowledge.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Selects complete turns against the active model's context capacity. It does
 * not use a fixed number of turns: the available budget changes with the
 * current question and persisted summary.
 */
@Component
public class SessionContextPlanner {

    private final ContextTokenCounter tokenCounter;

    public SessionContextPlanner(ContextTokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter;
    }

    public SessionContextPlan plan(String summary, String question, List<SessionTurn> chronologicalTurns,
                                   ContextCapacity capacity) {
        int promptBudget = Math.max(0, capacity.maxPromptTokens() - capacity.answerReserveTokens());
        int questionTokens = tokenCounter.estimate(question);
        int summaryTokens = tokenCounter.estimate(summary);
        int roomAfterQuestionAndSummary = Math.max(0, promptBudget - questionTokens - summaryTokens);
        int evidenceReservation = Math.min(capacity.evidenceBudgetTokens(), roomAfterQuestionAndSummary);
        int historyAllowance = Math.min(capacity.historyBudgetTokens(),
                Math.max(0, roomAfterQuestionAndSummary - evidenceReservation));

        List<SessionTurn> includedReversed = new ArrayList<>();
        int historyTokens = 0;
        int deferred = 0;
        List<SessionTurn> turns = chronologicalTurns == null ? List.of() : chronologicalTurns;
        for (int index = turns.size() - 1; index >= 0; index--) {
            SessionTurn turn = turns.get(index);
            int turnTokens = turn.tokenCount(tokenCounter);
            if (historyTokens + turnTokens <= historyAllowance) {
                includedReversed.add(turn);
                historyTokens += turnTokens;
            } else {
                deferred++;
            }
        }
        Collections.reverse(includedReversed);
        return new SessionContextPlan(
                capacity.maxPromptTokens(), capacity.answerReserveTokens(), promptBudget,
                questionTokens, summaryTokens, historyTokens, evidenceReservation,
                List.copyOf(includedReversed), deferred, deferred > 0
        );
    }

    public record ContextCapacity(int maxPromptTokens, int answerReserveTokens,
                                  int historyBudgetTokens, int evidenceBudgetTokens) {
    }
}
