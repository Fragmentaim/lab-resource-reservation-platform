package com.fragment.labbooking.knowledge.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

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

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record ProcessResult(
            int chunkCount,
            String status,
            String docVersion,
            List<String> chunkIds,
            List<String> vectorIds,
            List<ChunkResult> chunks,
            ParseQuality parseQuality
    ) {
        public ProcessResult {
            chunkIds = chunkIds == null ? List.of() : List.copyOf(chunkIds);
            vectorIds = vectorIds == null ? List.of() : List.copyOf(vectorIds);
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
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
            List<String> warnings
    ) {
        public ParseQuality {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
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
    ) {
        public ChunkResult {
            titlePath = titlePath == null ? List.of() : List.copyOf(titlePath);
        }
    }

    record ChatMessage(String role, String content) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record SummaryResult(String summary, Integer summaryTokens, Map<String, Object> providerUsage) {
        public SummaryResult {
            summary = summary == null ? "" : summary;
            summaryTokens = summaryTokens == null ? 0 : summaryTokens;
            providerUsage = providerUsage == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(providerUsage));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record KnowledgeSearchResult(String query, List<KnowledgeCandidate> candidates) {
        public KnowledgeSearchResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record KnowledgeCandidate(
            String chunkUid, Long documentId, String docVersion, Integer chunkIndex, Integer pageNo,
            String sectionTitle, List<String> titlePath, String contentHash, Integer tokenCount,
            Double score, Double retrievalScore, Double rerankScore, String rerankProvider,
            String retrievalSource, String locator
    ) {
        public KnowledgeCandidate {
            titlePath = titlePath == null ? List.of() : List.copyOf(titlePath);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record KnowledgeChunk(
            String chunkUid, Long documentId, String docVersion, Integer chunkIndex, Integer pageNo,
            String sectionTitle, List<String> titlePath, String contentHash, Integer tokenCount, String content
    ) {
        public KnowledgeChunk {
            titlePath = titlePath == null ? List.of() : List.copyOf(titlePath);
        }
    }

}
