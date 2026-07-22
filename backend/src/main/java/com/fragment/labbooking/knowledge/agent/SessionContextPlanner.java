package com.fragment.labbooking.knowledge.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Keeps the newest complete turns that fit in the model window. */
@Component
public class SessionContextPlanner {

    private final ContextTokenCounter tokenCounter;

    public SessionContextPlanner(ContextTokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter;
    }

    public SessionContextPlan plan(String summary, String question, List<SessionTurn> chronologicalTurns,
                                   ContextCapacity capacity) {
        int promptBudget = Math.max(0, capacity.contextWindowTokens()
                - capacity.maxOutputTokens() - capacity.safetyMarginTokens());
        int questionTokens = tokenCounter.estimate(question);
        int summaryTokens = tokenCounter.estimate(summary);
        int roomAfterQuestionAndSummary = Math.max(0, promptBudget - questionTokens - summaryTokens);
        List<SessionTurn> turns = chronologicalTurns == null ? List.of() : chronologicalTurns;
        int allHistoryTokens = turns.stream().mapToInt(turn -> turn.tokenCount(tokenCounter)).sum();
        boolean needsCompaction = allHistoryTokens > roomAfterQuestionAndSummary;
        int historyAllowance = needsCompaction
                ? Math.min(roomAfterQuestionAndSummary,
                (int) Math.floor(roomAfterQuestionAndSummary * capacity.compactionTargetRatio()))
                : roomAfterQuestionAndSummary;

        List<SessionTurn> includedReversed = new ArrayList<>();
        int historyTokens = 0;
        for (int index = turns.size() - 1; index >= 0; index--) {
            SessionTurn turn = turns.get(index);
            int turnTokens = turn.tokenCount(tokenCounter);
            if (historyTokens + turnTokens <= historyAllowance) {
                includedReversed.add(turn);
                historyTokens += turnTokens;
            } else {
                // Keep history as one contiguous recent suffix.
                break;
            }
        }
        Collections.reverse(includedReversed);
        int deferred = turns.size() - includedReversed.size();
        List<SessionTurn> deferredTurns = deferred == 0 ? List.of() : List.copyOf(turns.subList(0, deferred));
        return new SessionContextPlan(
                capacity.contextWindowTokens(), capacity.maxOutputTokens(), capacity.safetyMarginTokens(), promptBudget,
                questionTokens, summaryTokens, historyTokens,
                List.copyOf(includedReversed), deferredTurns, deferred, deferred > 0
        );
    }

    public record ContextCapacity(int contextWindowTokens, int maxOutputTokens,
                                  int safetyMarginTokens, double compactionTargetRatio) {
    }
}
