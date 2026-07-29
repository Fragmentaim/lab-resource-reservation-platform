package com.fragment.labbooking.knowledge.evaluation;

/** Security metrics collected before the answer model receives any document content. */
public record AclEvaluationMetrics(
        int totalCases,
        int unauthorizedAttemptCount,
        int leakedUnauthorizedCases,
        int interceptedUnauthorizedCases,
        int legalCaseCount,
        int legalCasesWithRelevantDocument,
        int falselyRejectedLegalCases
) {
    public double unauthorizedDocumentLeakRate() { return ratio(leakedUnauthorizedCases, unauthorizedAttemptCount); }
    public double unauthorizedRequestInterceptionRate() { return ratio(interceptedUnauthorizedCases, unauthorizedAttemptCount); }
    public double legalDocumentRecallRate() { return ratio(legalCasesWithRelevantDocument, legalCaseCount); }
    public double falseRejectionRate() { return ratio(falselyRejectedLegalCases, legalCaseCount); }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0D : (double) numerator / denominator;
    }
}
