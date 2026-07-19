package com.fragment.labbooking.knowledge.agent;

import com.fragment.labbooking.knowledge.vo.QaAnswerVO;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bounded knowledge-search result sent back into a function-calling loop.
 * Accessible document identifiers remain server-side; the model sees only cards.
 */
public record ContextPack(String query, int accessibleDocumentCount, List<EvidenceCard> evidence,
                          String groundedAnswer) {

    private static final int MAX_ANSWER_CHARS = 1200;

    public static ContextPack from(String query, int accessibleDocumentCount, QaAnswerVO answer) {
        List<EvidenceCard> cards = answer.getSources() == null ? List.of() : answer.getSources().stream()
                .limit(5)
                .map(EvidenceCard::from)
                .toList();
        String text = answer.getAnswer() == null ? "" : answer.getAnswer();
        return new ContextPack(query, accessibleDocumentCount, cards,
                text.length() <= MAX_ANSWER_CHARS ? text : text.substring(0, MAX_ANSWER_CHARS));
    }

    public Map<String, Object> toToolPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", evidence.isEmpty() ? "NO_MATCH" : "OK");
        payload.put("accessible_document_count", accessibleDocumentCount);
        payload.put("evidence", evidence.stream().map(EvidenceCard::toToolPayload).toList());
        payload.put("grounded_answer", groundedAnswer);
        return payload;
    }
}
