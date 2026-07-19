package com.fragment.labbooking.knowledge.agent;

import com.fragment.labbooking.knowledge.vo.QaSourceVO;

import java.util.LinkedHashMap;
import java.util.Map;

/** A bounded, source-attributed piece of knowledge exposed to the model. */
public record EvidenceCard(String documentTitle, String sectionTitle, String excerpt,
                           Double score, String retrievalSource) {

    private static final int MAX_EXCERPT_CHARS = 700;

    public static EvidenceCard from(QaSourceVO source) {
        return new EvidenceCard(
                text(source.getDocumentTitle(), 160),
                text(source.getSectionTitle(), 160),
                text(source.getExcerpt(), MAX_EXCERPT_CHARS),
                source.getScore(),
                text(source.getRetrievalSource(), 32)
        );
    }

    public Map<String, Object> toToolPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("document_title", documentTitle);
        payload.put("section_title", sectionTitle);
        payload.put("excerpt", excerpt);
        payload.put("score", score);
        payload.put("retrieval_source", retrievalSource);
        return payload;
    }

    private static String text(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
