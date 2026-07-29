package com.fragment.labbooking.knowledge.evaluation;

/** Aggregate measurements for a fixed Agent task set. Rates are fractions in [0, 1]. */
public record AgentEvaluationMetrics(
        int totalCases,
        int toolSelectionCorrect,
        int parameterCorrect,
        int taskCompleted,
        int multiStepCases,
        int multiStepCompleted,
        int noToolCases,
        int falseToolCalls,
        int totalExecutedToolCalls
) {
    public double toolSelectionAccuracy() { return ratio(toolSelectionCorrect, totalCases); }
    public double parameterAccuracy() { return ratio(parameterCorrect, totalCases); }
    public double taskCompletionRate() { return ratio(taskCompleted, totalCases); }
    public double multiStepTaskSuccessRate() { return ratio(multiStepCompleted, multiStepCases); }
    public double falseToolCallRate() { return ratio(falseToolCalls, noToolCases); }
    public double averageToolCalls() { return ratio(totalExecutedToolCalls, totalCases); }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0D : (double) numerator / denominator;
    }
}
