package com.fragment.labbooking.knowledge.evaluation;

/** Aggregate results for the fixed multi-turn suite. Rates are fractions in [0, 1]. */
public record ConversationEvaluationMetrics(
        int conversationCount,
        int expectedConstraintCount,
        int retainedConstraintCount,
        int referenceCaseCount,
        int referenceCorrectCount,
        int successfulTaskCount,
        int summaryCaseCount,
        int summaryTriggeredCount,
        long historyTokensBeforeSummary,
        long historyTokensAfterSummary,
        int summaryTaskPairs,
        int tasksBeforeSummarySucceeded,
        int tasksAfterSummarySucceeded
) {
    public double keyConstraintRetentionRate() { return ratio(retainedConstraintCount, expectedConstraintCount); }
    public double referenceResolutionAccuracy() { return ratio(referenceCorrectCount, referenceCaseCount); }
    public double multiTurnTaskSuccessRate() { return ratio(successfulTaskCount, conversationCount); }
    public double summaryTriggerRate() { return ratio(summaryTriggeredCount, summaryCaseCount); }
    public double historyTokenReductionRate() {
        return historyTokensBeforeSummary == 0 ? 0D
                : 1D - ((double) historyTokensAfterSummary / historyTokensBeforeSummary);
    }
    public double taskAccuracyBeforeSummary() { return ratio(tasksBeforeSummarySucceeded, summaryTaskPairs); }
    public double taskAccuracyAfterSummary() { return ratio(tasksAfterSummarySucceeded, summaryTaskPairs); }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0D : (double) numerator / denominator;
    }
}
