package com.fragment.labbooking.knowledge.evaluation;

/** Controlled fault contract. The adapter injects the fault at the named boundary and captures the real Run/Step. */
public record FaultEvaluationCase(String caseId, FaultType faultType, String injectionPoint,
                                  boolean recoveryExpected, boolean mutationPossible) {
    public enum FaultType {
        LLM_TIMEOUT, LLM_RATE_LIMIT_429, LLM_SERVER_ERROR_500,
        TOOL_INVALID_ARGUMENT, TOOL_TIMEOUT, DUPLICATE_TOOL_CALL,
        ROCKETMQ_DUPLICATE_DELIVERY, SERVICE_RESTART
    }
}
