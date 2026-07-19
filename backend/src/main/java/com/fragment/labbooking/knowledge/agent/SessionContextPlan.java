package com.fragment.labbooking.knowledge.agent;

import com.fragment.labbooking.knowledge.service.AiServiceClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A backend-owned, explainable decision about the history supplied to a model call. */
public record SessionContextPlan(
        int modelContextWindowTokens,
        int answerReserveTokens,
        int promptBudgetTokens,
        int questionTokens,
        int summaryTokens,
        int historyTokens,
        int evidenceReservationTokens,
        List<SessionTurn> includedTurns,
        List<SessionTurn> deferredTurns,
        int deferredTurnCount,
        boolean compactionRecommended
) {
    public List<AiServiceClient.ChatMessage> historyMessages() {
        return includedTurns.stream().flatMap(turn -> turn.messages().stream()).toList();
    }

    public Map<String, Object> safeDetail() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("model_context_window_tokens", modelContextWindowTokens);
        detail.put("answer_reserve_tokens", answerReserveTokens);
        detail.put("prompt_budget_tokens", promptBudgetTokens);
        detail.put("question_tokens", questionTokens);
        detail.put("summary_tokens", summaryTokens);
        detail.put("history_tokens", historyTokens);
        detail.put("evidence_reservation_tokens", evidenceReservationTokens);
        detail.put("included_turn_count", includedTurns.size());
        detail.put("included_record_ids", includedTurns.stream().map(SessionTurn::recordId).toList());
        detail.put("deferred_record_ids", deferredTurns.stream().map(SessionTurn::recordId).toList());
        detail.put("deferred_turn_count", deferredTurnCount);
        detail.put("compaction_recommended", compactionRecommended);
        detail.put("selection_unit", "COMPLETE_TURN");
        detail.put("history_shape", "CONTIGUOUS_RECENT_SUFFIX");
        return detail;
    }
}
