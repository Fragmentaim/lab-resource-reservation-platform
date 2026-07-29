package com.fragment.labbooking.knowledge.evaluation;

import java.util.List;
import java.util.Map;

/** A complete multi-turn evaluation conversation, including the final task whose constraints are scored. */
public record ConversationEvaluationCase(
        String caseId,
        String scenario,
        AgentEvaluationCase.Actor actor,
        List<AgentEvaluationCase.ConversationMessage> turns,
        Map<String, Object> expectedConstraints,
        boolean containsReference,
        boolean requiresSummary,
        String expectedFinalTask
) {
    public ConversationEvaluationCase {
        turns = turns == null ? List.of() : List.copyOf(turns);
        expectedConstraints = expectedConstraints == null ? Map.of() : Map.copyOf(expectedConstraints);
    }
}
