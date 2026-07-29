package com.fragment.labbooking.knowledge.evaluation;

import java.util.List;

/** One ACL-aware retrieval attempt. expectedRelevantDocumentIds are source-level truth for legal queries. */
public record AclEvaluationCase(
        String caseId,
        String scenario,
        AgentEvaluationCase.Actor actor,
        List<Long> allowedDocumentIds,
        List<Long> expectedRelevantDocumentIds,
        String query,
        boolean unauthorizedAttempt
) {
    public AclEvaluationCase {
        allowedDocumentIds = allowedDocumentIds == null ? List.of() : List.copyOf(allowedDocumentIds);
        expectedRelevantDocumentIds = expectedRelevantDocumentIds == null ? List.of() : List.copyOf(expectedRelevantDocumentIds);
    }
}
