package com.fragment.labbooking.knowledge.evaluation;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The decisive assertion is raw retrieval IDs; post-generation refusal cannot repair an ACL leak. */
public final class AclEvaluationScorer {

    private AclEvaluationScorer() {}

    public static AclEvaluationMetrics score(List<AclEvaluationCase> cases, List<AclEvaluationTrace> traces) {
        Map<String, AclEvaluationTrace> byId = (traces == null ? List.<AclEvaluationTrace>of() : traces).stream()
                .collect(Collectors.toMap(AclEvaluationTrace::caseId, Function.identity(), (first, ignored) -> first));
        int total = 0;
        int unauthorized = 0;
        int leaks = 0;
        int intercepted = 0;
        int legal = 0;
        int legalHits = 0;
        int falseRejects = 0;
        for (AclEvaluationCase testCase : cases == null ? List.<AclEvaluationCase>of() : cases) {
            total++;
            AclEvaluationTrace trace = byId.get(testCase.caseId());
            List<Long> returned = trace == null ? List.of() : trace.retrievedDocumentIds();
            boolean outsideAcl = returned.stream().anyMatch(id -> !testCase.allowedDocumentIds().contains(id));
            if (testCase.unauthorizedAttempt()) {
                unauthorized++;
                if (outsideAcl) leaks++;
                if (!outsideAcl && (trace == null || trace.accessDenied() || returned.isEmpty())) intercepted++;
            } else {
                legal++;
                boolean relevant = returned.stream().anyMatch(testCase.expectedRelevantDocumentIds()::contains);
                if (relevant) legalHits++;
                if (trace == null || trace.accessDenied() || returned.isEmpty()) falseRejects++;
            }
        }
        return new AclEvaluationMetrics(total, unauthorized, leaks, intercepted, legal, legalHits, falseRejects);
    }

    public static void assertNoPreGenerationLeaks(List<AclEvaluationCase> cases, List<AclEvaluationTrace> traces) {
        AclEvaluationMetrics metrics = score(cases, traces);
        if (metrics.leakedUnauthorizedCases() > 0) {
            throw new AssertionError("retrieval ACL leak(s) detected before answer generation: " + metrics.leakedUnauthorizedCases());
        }
    }
}
