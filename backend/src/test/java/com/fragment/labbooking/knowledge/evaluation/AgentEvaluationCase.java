package com.fragment.labbooking.knowledge.evaluation;

import java.util.List;
import java.util.Map;

/**
 * Immutable, reviewable contract for one end-to-end Agent task.
 *
 * <p>The expected outcome is deliberately separate from the expected tool
 * sequence: selecting a tool is not treated as task success unless the
 * execution result also satisfies {@link #expectedBusinessResult()}.</p>
 */
public record AgentEvaluationCase(
        String caseId,
        String scenario,
        Actor actor,
        List<ConversationMessage> history,
        String question,
        List<ExpectedToolCall> expectedToolCalls,
        Map<String, Object> expectedBusinessResult,
        List<Long> allowedDocumentIds,
        boolean multiStep
) {
    public AgentEvaluationCase {
        history = history == null ? List.of() : List.copyOf(history);
        expectedToolCalls = expectedToolCalls == null ? List.of() : List.copyOf(expectedToolCalls);
        expectedBusinessResult = expectedBusinessResult == null ? Map.of() : Map.copyOf(expectedBusinessResult);
        allowedDocumentIds = allowedDocumentIds == null ? List.of() : List.copyOf(allowedDocumentIds);
    }

    public record Actor(long userId, String role, boolean admin) {}

    public record ConversationMessage(String role, String content) {}

    public record ExpectedToolCall(String toolName, Map<String, Object> arguments) {
        public ExpectedToolCall {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }
}
