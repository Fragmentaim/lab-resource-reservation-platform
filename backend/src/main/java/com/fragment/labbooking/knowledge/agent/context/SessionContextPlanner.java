package com.fragment.labbooking.knowledge.agent.context;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fragment.labbooking.knowledge.service.AiServiceClient;
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

    public Plan plan(String summary, String question, List<Turn> chronologicalTurns,
                     ContextCapacity capacity) {
        int promptBudget = Math.max(0, capacity.contextWindowTokens()
                - capacity.maxOutputTokens() - capacity.safetyMarginTokens());
        int questionTokens = tokenCounter.estimate(question);
        int summaryTokens = tokenCounter.estimate(summary);
        int roomAfterQuestionAndSummary = Math.max(0, promptBudget - questionTokens - summaryTokens);
        List<Turn> turns = chronologicalTurns == null ? List.of() : chronologicalTurns;
        int allHistoryTokens = turns.stream().mapToInt(turn -> turn.tokenCount(tokenCounter)).sum();
        boolean needsCompaction = allHistoryTokens > roomAfterQuestionAndSummary;
        int historyAllowance = needsCompaction
                ? Math.min(roomAfterQuestionAndSummary,
                (int) Math.floor(roomAfterQuestionAndSummary * capacity.compactionTargetRatio()))
                : roomAfterQuestionAndSummary;

        List<Turn> includedReversed = new ArrayList<>();
        int historyTokens = 0;
        for (int index = turns.size() - 1; index >= 0; index--) {
            Turn turn = turns.get(index);
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
        List<Turn> deferredTurns = deferred == 0 ? List.of() : List.copyOf(turns.subList(0, deferred));
        return new Plan(
                capacity.contextWindowTokens(), capacity.maxOutputTokens(), capacity.safetyMarginTokens(), promptBudget,
                questionTokens, summaryTokens, historyTokens,
                List.copyOf(includedReversed), deferredTurns, deferred, deferred > 0
        );
    }

    public record ContextCapacity(int contextWindowTokens, int maxOutputTokens,
                                  int safetyMarginTokens, double compactionTargetRatio) {
    }

    /** One complete persisted user/assistant exchange. */
    public record Turn(Long recordId, String traceId, String question, String answer) {
        public Turn {
            traceId = traceId == null ? "" : traceId;
            question = question == null ? "" : question;
            answer = answer == null ? "" : answer;
        }

        public List<AiServiceClient.ChatMessage> messages() {
            List<AiServiceClient.ChatMessage> messages = new ArrayList<>();
            if (!question.isBlank()) messages.add(new AiServiceClient.ChatMessage("user", question));
            if (!answer.isBlank()) messages.add(new AiServiceClient.ChatMessage("assistant", answer));
            return messages;
        }

        int tokenCount(ContextTokenCounter counter) {
            return messages().stream().mapToInt(message -> counter.estimate(message.content()) + 4).sum();
        }
    }

    /** Auditable result of selecting the recent complete turns that fit. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Plan(
            int modelContextWindowTokens,
            int maxOutputTokens,
            int safetyMarginTokens,
            int promptBudgetTokens,
            int questionTokens,
            int summaryTokens,
            int historyTokens,
            @JsonIgnore List<Turn> includedTurns,
            @JsonIgnore List<Turn> deferredTurns,
            int deferredTurnCount,
            boolean compactionRecommended
    ) {
        public Plan {
            includedTurns = includedTurns == null ? List.of() : List.copyOf(includedTurns);
            deferredTurns = deferredTurns == null ? List.of() : List.copyOf(deferredTurns);
        }

        @JsonIgnore
        public List<AiServiceClient.ChatMessage> historyMessages() {
            return includedTurns.stream().flatMap(turn -> turn.messages().stream()).toList();
        }

        @JsonProperty("included_turn_count")
        public int includedTurnCount() { return includedTurns.size(); }

        @JsonProperty("included_record_ids")
        public List<Long> includedRecordIds() { return includedTurns.stream().map(Turn::recordId).toList(); }

        @JsonProperty("deferred_record_ids")
        public List<Long> deferredRecordIds() { return deferredTurns.stream().map(Turn::recordId).toList(); }

        @JsonProperty("selection_unit")
        public String selectionUnit() { return "COMPLETE_TURN"; }

        @JsonProperty("history_shape")
        public String historyShape() { return "CONTIGUOUS_RECENT_SUFFIX"; }
    }
}
