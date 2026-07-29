package com.fragment.labbooking.knowledge.evaluation;

/** Captures recovery and audit facts from one real injected-fault run. */
public record FaultEvaluationTrace(
        String caseId,
        boolean automaticallyRecovered,
        int retryCount,
        boolean finalTaskSucceeded,
        int duplicateSideEffectCount,
        boolean agentRunRecorded,
        boolean failingStepRecorded,
        String failingStepName
) {}
