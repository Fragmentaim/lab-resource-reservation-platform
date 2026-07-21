package com.fragment.labbooking.knowledge.evaluation;

/** Fault-injection result. Duplicate side effects must be zero before the suite can pass. */
public record FaultEvaluationMetrics(
        int totalCases,
        int automaticallyRecoveredCases,
        int finalTaskSucceededCases,
        int totalRetries,
        int duplicateSideEffects,
        int runsRecorded,
        int failingStepsRecorded
) {
    public double automaticRecoveryRate() { return ratio(automaticallyRecoveredCases, totalCases); }
    public double finalTaskSuccessRate() { return ratio(finalTaskSucceededCases, totalCases); }
    public double averageRetryCount() { return ratio(totalRetries, totalCases); }
    public double runAuditCoverage() { return ratio(runsRecorded, totalCases); }
    public double failingStepAuditCoverage() { return ratio(failingStepsRecorded, totalCases); }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0D : (double) numerator / denominator;
    }
}
