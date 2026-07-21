package com.fragment.labbooking.knowledge.evaluation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Scores traces produced by the real runtime; it intentionally never plans or executes a task itself. */
public final class AgentEvaluationScorer {

    /** A dynamic chunk UID is valid only if the runtime also validates it against this run's ACL-scoped candidates. */
    public static final String ANY_AUTHORIZED_CANDIDATE = "$ANY_AUTHORIZED_CANDIDATE";

    private AgentEvaluationScorer() {}

    public static AgentEvaluationMetrics score(List<AgentEvaluationCase> cases, List<AgentEvaluationTrace> traces) {
        Map<String, AgentEvaluationTrace> traceById = (traces == null ? List.<AgentEvaluationTrace>of() : traces).stream()
                .collect(Collectors.toMap(AgentEvaluationTrace::caseId, Function.identity(), (first, ignored) -> first,
                        LinkedHashMap::new));
        List<AgentEvaluationCase> inputs = cases == null ? List.of() : cases;
        int toolSelection = 0;
        int parameter = 0;
        int complete = 0;
        int multiStep = 0;
        int multiStepComplete = 0;
        int noTool = 0;
        int falseTool = 0;
        int executedCalls = 0;

        for (AgentEvaluationCase testCase : inputs) {
            AgentEvaluationTrace trace = traceById.get(testCase.caseId());
            List<AgentEvaluationTrace.ExecutedToolCall> actualCalls = trace == null ? List.of() : trace.toolCalls();
            executedCalls += actualCalls.size();
            boolean toolsMatch = sameToolSequence(testCase.expectedToolCalls(), actualCalls);
            boolean parametersMatch = toolsMatch && sameExpectedArguments(testCase.expectedToolCalls(), actualCalls);
            boolean completed = trace != null && "SUCCEEDED".equals(trace.finalStatus())
                    && matchesExpectedSubset(testCase.expectedBusinessResult(), trace.businessResult());
            if (toolsMatch) toolSelection++;
            if (parametersMatch) parameter++;
            if (completed) complete++;
            if (testCase.multiStep()) {
                multiStep++;
                if (completed) multiStepComplete++;
            }
            if (testCase.expectedToolCalls().isEmpty()) {
                noTool++;
                if (!actualCalls.isEmpty()) falseTool++;
            }
        }
        return new AgentEvaluationMetrics(inputs.size(), toolSelection, parameter, complete, multiStep,
                multiStepComplete, noTool, falseTool, executedCalls);
    }

    public static List<String> validateDataset(List<AgentEvaluationCase> cases, int expectedCaseCount) {
        List<String> problems = new ArrayList<>();
        List<AgentEvaluationCase> inputs = cases == null ? List.of() : cases;
        if (inputs.size() != expectedCaseCount) {
            problems.add("expected " + expectedCaseCount + " cases but got " + inputs.size());
        }
        Map<String, Long> duplicateIds = inputs.stream().collect(Collectors.groupingBy(AgentEvaluationCase::caseId,
                Collectors.counting()));
        duplicateIds.forEach((caseId, count) -> {
            if (count > 1) problems.add("duplicate case id: " + caseId);
        });
        for (AgentEvaluationCase testCase : inputs) {
            if (testCase.question() == null || testCase.question().isBlank()) {
                problems.add(testCase.caseId() + " has no question");
            }
            if (testCase.actor() == null || testCase.actor().userId() <= 0) {
                problems.add(testCase.caseId() + " has no authenticated actor");
            }
        }
        return List.copyOf(problems);
    }

    private static boolean sameToolSequence(List<AgentEvaluationCase.ExpectedToolCall> expected,
                                             List<AgentEvaluationTrace.ExecutedToolCall> actual) {
        if (expected.size() != actual.size()) return false;
        for (int index = 0; index < expected.size(); index++) {
            if (!Objects.equals(expected.get(index).toolName(), actual.get(index).toolName())) return false;
        }
        return true;
    }

    private static boolean sameExpectedArguments(List<AgentEvaluationCase.ExpectedToolCall> expected,
                                                 List<AgentEvaluationTrace.ExecutedToolCall> actual) {
        for (int index = 0; index < expected.size(); index++) {
            if (!matchesExpectedSubset(expected.get(index).arguments(), actual.get(index).arguments())) return false;
        }
        return true;
    }

    /** Expected values are a subset so a trace may safely contain timestamps, trace IDs and extra result fields. */
    @SuppressWarnings("unchecked")
    static boolean matchesExpectedSubset(Map<String, Object> expected, Map<String, Object> actual) {
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            Object actualValue = actual.get(entry.getKey());
            Object expectedValue = entry.getValue();
            if (expectedValue instanceof Map<?, ?> expectedMap) {
                if (!(actualValue instanceof Map<?, ?> actualMap)
                        || !matchesExpectedSubset((Map<String, Object>) expectedMap, (Map<String, Object>) actualMap)) {
                    return false;
                }
            } else if (expectedValue instanceof List<?> expectedList) {
                if (!(actualValue instanceof List<?> actualList)) return false;
                if (expectedList.size() == 1 && ANY_AUTHORIZED_CANDIDATE.equals(expectedList.get(0))) {
                    if (actualList.isEmpty() || actualList.stream().anyMatch(value -> !(value instanceof String text) || text.isBlank())) {
                        return false;
                    }
                } else if (!actualList.equals(expectedList)) {
                    return false;
                }
            } else if (!equivalent(expectedValue, actualValue)) {
                return false;
            }
        }
        return true;
    }

    private static boolean equivalent(Object expected, Object actual) {
        if (expected instanceof Number left && actual instanceof Number right) {
            return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
        }
        return Objects.equals(expected, actual);
    }
}
