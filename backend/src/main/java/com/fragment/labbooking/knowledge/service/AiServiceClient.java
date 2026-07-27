package com.fragment.labbooking.knowledge.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public interface AiServiceClient {

    ProcessResult processDocument(Long documentId, MultipartFile file, String fileType);

    ProcessResult processDocument(Long documentId, File file, String fileName, String fileType);

    ProcessResult processDocument(Long documentId, File file, String fileName, String fileType, String docVersion);

    ProcessResult processDocument(Long documentId, byte[] bytes, String fileName, String fileType);

    ProcessResult processDocument(Long documentId, byte[] bytes, String fileName, String fileType, String docVersion);

    ProcessResult processDocumentByUrl(Long documentId, String fileUrl, String fileName, String fileType,
                                       String docVersion);

    KnowledgeSearchResult retrieveKnowledge(String question, Map<Long, String> documentVersions);

    List<KnowledgeChunk> openKnowledgeChunks(List<String> chunkUids, Map<Long, String> documentVersions);

    SummaryResult summarizeSession(String existingSummary, List<ChatMessage> newTurns);

    int deleteDocumentVectors(Long documentId);

    int deleteDocumentVersion(Long documentId, String docVersion);

    boolean checkHealth();

    record ProcessResult(
            int chunkCount,
            String status,
            String docVersion,
            List<String> chunkIds,
            List<String> vectorIds,
            List<ChunkResult> chunks,
            ParseQuality parseQuality
    ) {}

    record ParseQuality(
            String provider,
            String providerVersion,
            String parseMode,
            Integer unitCount,
            Integer nonEmptyUnitCount,
            Integer characterCount,
            Integer headingCount,
            Integer tableCount,
            Integer imageCount,
            Integer scannedUnitCount,
            Integer ocrUnitCount,
            Integer ocrCharacterCount,
            Double ocrAverageConfidence,
            Double parseAverageConfidence,
            Double layoutAverageConfidence,
            Double tableAverageConfidence,
            Integer emptyUnitCount,
            Double qualityScore,
            Double qualityLowScore,
            List<String> warnings,
            String reportJson
    ) {}

    record ChunkResult(
            String chunkId,
            Integer chunkIndex,
            String content,
            Integer tokenCount,
            Integer pageNo,
            String sectionTitle,
            List<String> titlePath,
            String contentHash,
            Integer charStart,
            Integer charEnd,
            String vectorId
    ) {}

    record ChatMessage(String role, String content) {}

    record SummaryResult(String summary, Integer summaryTokens, Map<String, Object> providerUsage) {
        public SummaryResult {
            providerUsage = providerUsage == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(providerUsage));
        }
    }

    record KnowledgeSearchResult(String query, List<KnowledgeCandidate> candidates) {}

    record KnowledgeCandidate(
            String chunkUid, Long documentId, String docVersion, Integer chunkIndex, Integer pageNo,
            String sectionTitle, List<String> titlePath, String contentHash, Integer tokenCount,
            Double score, Double retrievalScore, Double rerankScore, String rerankProvider,
            String retrievalSource, String locator
    ) {}

    record KnowledgeChunk(
            String chunkUid, Long documentId, String docVersion, Integer chunkIndex, Integer pageNo,
            String sectionTitle, List<String> titlePath, String contentHash, Integer tokenCount, String content
    ) {}

}
