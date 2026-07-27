package com.fragment.labbooking.knowledge.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fragment.labbooking.knowledge.service.AiServiceClient;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SessionContextPlan(
        int modelContextWindowTokens,
        int maxOutputTokens,
        int safetyMarginTokens,
        int promptBudgetTokens,
        int questionTokens,
        int summaryTokens,
        int historyTokens,
        @JsonIgnore List<SessionTurn> includedTurns,
        @JsonIgnore List<SessionTurn> deferredTurns,
        int deferredTurnCount,
        boolean compactionRecommended
) {
    public SessionContextPlan {
        includedTurns = AgentModelGuard.list(includedTurns);
        deferredTurns = AgentModelGuard.list(deferredTurns);
    }

    @JsonIgnore
    public List<AiServiceClient.ChatMessage> historyMessages() {
        return includedTurns.stream().flatMap(turn -> turn.messages().stream()).toList();
    }

    @JsonProperty("included_turn_count")
    public int includedTurnCount() {
        return includedTurns.size();
    }

    @JsonProperty("included_record_ids")
    public List<Long> includedRecordIds() {
        return includedTurns.stream().map(SessionTurn::recordId).toList();
    }

    @JsonProperty("deferred_record_ids")
    public List<Long> deferredRecordIds() {
        return deferredTurns.stream().map(SessionTurn::recordId).toList();
    }

    @JsonProperty("selection_unit")
    public String selectionUnit() {
        return "COMPLETE_TURN";
    }

    @JsonProperty("history_shape")
    public String historyShape() {
        return "CONTIGUOUS_RECENT_SUFFIX";
    }
}
