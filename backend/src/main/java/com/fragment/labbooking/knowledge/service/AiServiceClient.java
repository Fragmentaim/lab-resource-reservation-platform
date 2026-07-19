package com.fragment.labbooking.knowledge.service;

import com.fragment.labbooking.knowledge.vo.QaAnswerVO;
import com.fragment.labbooking.knowledge.vo.QaSourceVO;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
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

    QaAnswerVO askQuestion(String question, String sessionId, List<Long> documentIds);

    QaAnswerVO askQuestion(String question, String sessionId, List<Long> documentIds,
                           String sessionSummary, List<ChatMessage> chatHistory,
                           ContextOptions contextOptions, String traceId);

    KnowledgeSearchResult retrieveKnowledge(String question, List<Long> documentIds);

    List<KnowledgeChunk> openKnowledgeChunks(List<String> chunkUids, List<Long> documentIds);

    void askQuestionStream(String question, String sessionId, List<Long> documentIds,
                           StreamEventConsumer eventConsumer);

    void askQuestionStream(String question, String sessionId, List<Long> documentIds,
                           String sessionSummary, List<ChatMessage> chatHistory,
                           ContextOptions contextOptions, String traceId,
                           StreamEventConsumer eventConsumer);

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

    record ContextOptions(
            int maxPromptTokens,
            int answerReserveTokens,
            int summaryBudgetTokens,
            int historyBudgetTokens,
            int evidenceBudgetTokens
    ) {}

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

    record StreamEvent(String type, String content, List<QaSourceVO> sources,
                       Integer latencyMs, String model, String message,
                       String rewrittenQuestion, Boolean rewriteApplied,
                       Map<String, Object> contextStats) {}

    @FunctionalInterface
    interface StreamEventConsumer {
        void accept(StreamEvent event) throws IOException;
    }
}
