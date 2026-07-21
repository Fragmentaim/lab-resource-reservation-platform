package com.fragment.labbooking.knowledge.evaluation;

import java.util.List;

/** Raw document IDs returned by the vector/keyword retrieval layer before model generation. */
public record AclEvaluationTrace(
        String caseId,
        List<Long> retrievedDocumentIds,
        boolean accessDenied,
        boolean finalAnswerWithheld
) {
    public AclEvaluationTrace {
        retrievedDocumentIds = retrievedDocumentIds == null ? List.of() : List.copyOf(retrievedDocumentIds);
    }
}
