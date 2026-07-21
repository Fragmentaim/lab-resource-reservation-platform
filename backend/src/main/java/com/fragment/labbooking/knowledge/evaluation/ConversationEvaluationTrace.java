package com.fragment.labbooking.knowledge.evaluation;

import java.util.Map;

/** Captured from the final turn and its SessionContextPlan, not inferred from the model text. */
public record ConversationEvaluationTrace(
        String caseId,
        Map<String, Object> resolvedConstraints,
        boolean referenceResolvedCorrectly,
        boolean finalTaskSucceeded,
        boolean summaryTriggered,
        int historyTokensBeforeSummary,
        int historyTokensAfterSummary,
        boolean taskBeforeSummarySucceeded,
        boolean taskAfterSummarySucceeded
) {
    public ConversationEvaluationTrace {
        resolvedConstraints = resolvedConstraints == null ? Map.of() : Map.copyOf(resolvedConstraints);
    }
}
