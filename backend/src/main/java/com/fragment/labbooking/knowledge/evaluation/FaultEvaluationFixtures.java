package com.fragment.labbooking.knowledge.evaluation;

import java.util.ArrayList;
import java.util.List;

/** Three repeats for each failure mode minimise a single transient provider error skewing the conclusion. */
public final class FaultEvaluationFixtures {

    public static final int TOTAL_CASES = 24;

    private FaultEvaluationFixtures() {}

    public static List<FaultEvaluationCase> suiteV1() {
        List<FaultEvaluationCase> cases = new ArrayList<>(TOTAL_CASES);
        add(cases, FaultEvaluationCase.FaultType.LLM_TIMEOUT, "model_gateway", false);
        add(cases, FaultEvaluationCase.FaultType.LLM_RATE_LIMIT_429, "model_gateway", false);
        add(cases, FaultEvaluationCase.FaultType.LLM_SERVER_ERROR_500, "model_gateway", false);
        add(cases, FaultEvaluationCase.FaultType.TOOL_INVALID_ARGUMENT, "agent_tool_arguments", false);
        add(cases, FaultEvaluationCase.FaultType.TOOL_TIMEOUT, "reservation_context_tool", false);
        add(cases, FaultEvaluationCase.FaultType.DUPLICATE_TOOL_CALL, "native_tool_execution", false);
        add(cases, FaultEvaluationCase.FaultType.ROCKETMQ_DUPLICATE_DELIVERY, "reservation_consumer", true);
        add(cases, FaultEvaluationCase.FaultType.SERVICE_RESTART, "agent_checkpoint", false);
        if (cases.size() != TOTAL_CASES) throw new IllegalStateException("fixture count changed: " + cases.size());
        return List.copyOf(cases);
    }

    private static void add(List<FaultEvaluationCase> cases, FaultEvaluationCase.FaultType type,
                            String injectionPoint, boolean mutationPossible) {
        for (int index = 1; index <= 3; index++) {
            cases.add(new FaultEvaluationCase(type.name().toLowerCase() + "-" + index, type, injectionPoint,
                    true, mutationPossible));
        }
    }
}
