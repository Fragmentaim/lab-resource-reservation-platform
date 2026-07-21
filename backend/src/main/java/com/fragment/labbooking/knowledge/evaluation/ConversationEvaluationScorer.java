package com.fragment.labbooking.knowledge.evaluation;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Scores constraint preservation and compaction quality from recorded session traces. */
public final class ConversationEvaluationScorer {

    private ConversationEvaluationScorer() {}

    public static ConversationEvaluationMetrics score(List<ConversationEvaluationCase> cases,
                                                       List<ConversationEvaluationTrace> traces) {
        Map<String, ConversationEvaluationTrace> byId = (traces == null ? List.<ConversationEvaluationTrace>of() : traces)
                .stream().collect(Collectors.toMap(ConversationEvaluationTrace::caseId, Function.identity(), (first, ignored) -> first));
        int conversations = 0;
        int expectedConstraints = 0;
        int retained = 0;
        int referenceCases = 0;
        int referencesCorrect = 0;
        int successful = 0;
        int summaryCases = 0;
        int summaryTriggered = 0;
        long beforeTokens = 0;
        long afterTokens = 0;
        int summaryPairs = 0;
        int beforeSuccess = 0;
        int afterSuccess = 0;

        for (ConversationEvaluationCase testCase : cases == null ? List.<ConversationEvaluationCase>of() : cases) {
            conversations++;
            ConversationEvaluationTrace trace = byId.get(testCase.caseId());
            expectedConstraints += testCase.expectedConstraints().size();
            if (trace != null) {
                retained += retainedCount(testCase.expectedConstraints(), trace.resolvedConstraints());
                if (trace.finalTaskSucceeded()) successful++;
            }
            if (testCase.containsReference()) {
                referenceCases++;
                if (trace != null && trace.referenceResolvedCorrectly()) referencesCorrect++;
            }
            if (testCase.requiresSummary()) {
                summaryCases++;
                if (trace != null && trace.summaryTriggered()) {
                    summaryTriggered++;
                    beforeTokens += trace.historyTokensBeforeSummary();
                    afterTokens += trace.historyTokensAfterSummary();
                    summaryPairs++;
                    if (trace.taskBeforeSummarySucceeded()) beforeSuccess++;
                    if (trace.taskAfterSummarySucceeded()) afterSuccess++;
                }
            }
        }
        return new ConversationEvaluationMetrics(conversations, expectedConstraints, retained, referenceCases,
                referencesCorrect, successful, summaryCases, summaryTriggered, beforeTokens, afterTokens,
                summaryPairs, beforeSuccess, afterSuccess);
    }

    private static int retainedCount(Map<String, Object> expected, Map<String, Object> actual) {
        return (int) expected.entrySet().stream()
                .filter(entry -> AgentEvaluationScorer.matchesExpectedSubset(Map.of(entry.getKey(), entry.getValue()), actual))
                .count();
    }
}
