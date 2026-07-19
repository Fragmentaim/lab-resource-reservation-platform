package com.fragment.labbooking.knowledge.service;

import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.List;

public interface AiServiceClient {

    ProcessResult processDocument(Long documentId, MultipartFile file, String fileType);

    ProcessResult processDocument(Long documentId, File file, String fileName, String fileType);

    ProcessResult processDocument(Long documentId, File file, String fileName, String fileType, String docVersion);

    ProcessResult processDocument(Long documentId, byte[] bytes, String fileName, String fileType);

    ProcessResult processDocument(Long documentId, byte[] bytes, String fileName, String fileType, String docVersion);

    ProcessResult processDocumentByUrl(Long documentId, String fileUrl, String fileName, String fileType,
                                       String docVersion);

    KnowledgeSearchResult retrieveKnowledge(String question, List<Long> documentIds);

    List<KnowledgeChunk> openKnowledgeChunks(List<String> chunkUids, List<Long> documentIds);

    SummaryResult summarizeSession(String existingSummary, List<ChatMessage> newTurns, Integer maxSummaryTokens);

    int deleteDocumentVectors(Long documentId);

    boolean checkHealth();

    record ProcessResult(
            int chunkCount,
            String status,
            String docVersion,
            List<String> chunkIds,
            List<String> vectorIds,
            List<ChunkResult> chunks
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

    record SummaryResult(String summary, Integer summaryTokens) {}

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
