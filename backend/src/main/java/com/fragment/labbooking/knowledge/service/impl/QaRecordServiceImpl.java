package com.fragment.labbooking.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.knowledge.dto.QaAskDTO;
import com.fragment.labbooking.knowledge.dto.QaFeedbackDTO;
import com.fragment.labbooking.knowledge.entity.KbDocument;
import com.fragment.labbooking.knowledge.entity.QaFeedback;
import com.fragment.labbooking.knowledge.entity.QaContextTrace;
import com.fragment.labbooking.knowledge.entity.QaRecord;
import com.fragment.labbooking.knowledge.entity.QaSession;
import com.fragment.labbooking.knowledge.entity.QaSource;
import com.fragment.labbooking.knowledge.mapper.QaContextTraceMapper;
import com.fragment.labbooking.knowledge.mapper.KbDocumentMapper;
import com.fragment.labbooking.knowledge.mapper.QaFeedbackMapper;
import com.fragment.labbooking.knowledge.mapper.QaRecordMapper;
import com.fragment.labbooking.knowledge.mapper.QaSessionMapper;
import com.fragment.labbooking.knowledge.mapper.QaSourceMapper;
import com.fragment.labbooking.knowledge.service.AiServiceClient;
import com.fragment.labbooking.knowledge.service.AssistantToolRouter;
import com.fragment.labbooking.knowledge.service.NativeToolCallingService;
import com.fragment.labbooking.knowledge.service.KbDocumentService;
import com.fragment.labbooking.knowledge.service.QaRecordService;
import com.fragment.labbooking.knowledge.service.ToolRouteResult;
import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.knowledge.vo.QaAnswerVO;
import com.fragment.labbooking.knowledge.vo.QaRecordVO;
import com.fragment.labbooking.knowledge.vo.QaSessionVO;
import com.fragment.labbooking.knowledge.vo.QaSourceVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
@Slf4j
public class QaRecordServiceImpl extends ServiceImpl<QaRecordMapper, QaRecord>
        implements QaRecordService {

    @Autowired
    private KbDocumentService kbDocumentService;

    @Autowired
    private KbDocumentMapper kbDocumentMapper;

    @Autowired
    private AiServiceClient aiServiceClient;

    @Autowired
    private AssistantToolRouter assistantToolRouter;

    @Autowired
    private NativeToolCallingService nativeToolCallingService;

    @Autowired
    private QaSourceMapper qaSourceMapper;

    @Autowired
    private QaSessionMapper qaSessionMapper;

    @Autowired
    private QaContextTraceMapper qaContextTraceMapper;

    @Autowired
    private QaFeedbackMapper qaFeedbackMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.knowledge.chat.context-max-turns:8}")
    private int contextMaxTurns;

    @Value("${app.knowledge.chat.max-prompt-tokens:12000}")
    private int maxPromptTokens;

    @Value("${app.knowledge.chat.answer-reserve-tokens:2000}")
    private int answerReserveTokens;

    @Value("${app.knowledge.chat.summary-budget-tokens:1000}")
    private int summaryBudgetTokens;

    @Value("${app.knowledge.chat.history-budget-tokens:2000}")
    private int historyBudgetTokens;

    @Value("${app.knowledge.chat.evidence-budget-tokens:7000}")
    private int evidenceBudgetTokens;

    @Value("${app.knowledge.chat.summary-trigger-turns:6}")
    private int summaryTriggerTurns;

    @Override
    public QaAnswerVO ask(QaAskDTO dto, LoginUser actor) {
        Long userId = actor.getId();
        QaSession session = resolveSession(dto.getSessionId(), userId, dto.getQuestion());
        String sessionId = session.getSessionId();
        List<AiServiceClient.ChatMessage> chatHistory = loadRecentChatHistory(sessionId, userId);
        AiServiceClient.ContextOptions contextOptions = contextOptions();

        // Save question record
        QaRecord record = new QaRecord();
        record.setUserId(userId);
        record.setSessionId(sessionId);
        record.setQuestion(dto.getQuestion());
        record.setStatus("PENDING");
        record.setQuestionType("KB");
        record.setTraceId(UUID.randomUUID().toString());
        record.setCreatedAt(LocalDateTime.now());
        save(record);

        try {
            java.util.Optional<ToolRouteResult> routed = nativeToolCallingService.tryAnswer(dto.getQuestion(), actor);
            if (routed.isEmpty()) {
                routed = assistantToolRouter.route(dto.getQuestion(), actor);
            }
            if (routed.isPresent()) {
                QaAnswerVO answer = new QaAnswerVO();
                answer.setAnswer(routed.get().answer());
                answer.setLatencyMs(0);
                answer.setModelName(toolRouteModel(routed.get()));
                answer.setQuestionType("TOOL");
                answer.setSources(Collections.emptyList());
                Map<String, Object> toolStats = new LinkedHashMap<>();
                toolStats.put("route", "permission_scoped_tool");
                toolStats.put("tool_calls", routed.get().toolCalls());
                answer.setContextStats(toolStats);
                finishToolAnswer(record, answer, sessionId, userId, dto.getQuestion());
                return answer;
            }
            List<Long> readyDocumentIds = kbDocumentService.listAccessibleReadyDocumentIds(actor);

            // Call AI service
            QaAnswerVO answer = aiServiceClient.askQuestion(
                    dto.getQuestion(),
                    sessionId,
                    readyDocumentIds,
                    session.getSummary(),
                    chatHistory,
                    contextOptions,
                    record.getTraceId()
            );

            // Update record with answer
            record.setAnswer(answer.getAnswer());
            record.setLatencyMs(answer.getLatencyMs());
            record.setStatus("ANSWERED");
            record.setModelName(answer.getModelName());
            record.setQuestionType("KB");
            updateById(record);

            saveSources(record.getId(), answer.getSources());
            saveContextTrace(record, dto.getQuestion(), answer);
            updateSessionAfterAnswer(sessionId, userId, dto.getQuestion(), record.getTraceId());
            triggerSummaryRefresh(sessionId, userId);

            answer.setRecordId(record.getId());
            answer.setSessionId(sessionId);
            answer.setTraceId(record.getTraceId());
            answer.setQuestionType("KB");
            return answer;
        } catch (Exception e) {
            record.setStatus("FAILED");
            record.setAnswer("抱歉，问答服务暂时不可用: " + e.getMessage());
            updateById(record);
            throw e;
        }
    }

    private void finishToolAnswer(QaRecord record, QaAnswerVO answer, String sessionId, Long userId, String question) {
        record.setAnswer(answer.getAnswer());
        record.setLatencyMs(answer.getLatencyMs());
        record.setStatus("ANSWERED");
        record.setModelName(answer.getModelName());
        record.setQuestionType(answer.getQuestionType());
        updateById(record);
        saveContextTrace(record, question, answer);
        updateSessionAfterAnswer(sessionId, userId, question, record.getTraceId());
        triggerSummaryRefresh(sessionId, userId);
        answer.setRecordId(record.getId());
        answer.setSessionId(sessionId);
        answer.setTraceId(record.getTraceId());
    }

    private String toolRouteModel(ToolRouteResult result) {
        boolean nativeFunctionCalling = result.toolCalls().stream()
                .anyMatch(call -> "native_function_calling".equals(call.get("protocol")));
        return nativeFunctionCalling ? "native-function-calling" : "java-tool-router";
    }

    @Override
    public void askStream(QaAskDTO dto, LoginUser actor, ResponseBodyEmitter emitter) {
        Long userId = actor.getId();
        QaSession session = resolveSession(dto.getSessionId(), userId, dto.getQuestion());
        String sessionId = session.getSessionId();
        final String streamSessionId = sessionId;
        List<AiServiceClient.ChatMessage> chatHistory = loadRecentChatHistory(streamSessionId, userId);
        AiServiceClient.ContextOptions contextOptions = contextOptions();

        QaRecord record = new QaRecord();
        record.setUserId(userId);
        record.setSessionId(streamSessionId);
        record.setQuestion(dto.getQuestion());
        record.setStatus("PENDING");
        record.setQuestionType("KB");
        record.setTraceId(UUID.randomUUID().toString());
        record.setCreatedAt(LocalDateTime.now());
        save(record);

        StringBuilder answerBuilder = new StringBuilder();
        List<QaSourceVO> streamedSources = new ArrayList<>();
        Integer[] latencyMs = new Integer[1];
        String[] modelName = new String[1];
        AtomicReference<String> rewrittenQuestion = new AtomicReference<>();
        AtomicReference<Boolean> rewriteApplied = new AtomicReference<>(false);
        AtomicReference<Map<String, Object>> contextStats = new AtomicReference<>(Collections.emptyMap());

        try {
            sendEvent(emitter, event("record",
                    "recordId", record.getId(),
                    "sessionId", streamSessionId,
                    "traceId", record.getTraceId()));

            java.util.Optional<ToolRouteResult> routed = nativeToolCallingService.tryAnswer(dto.getQuestion(), actor);
            if (routed.isEmpty()) {
                routed = assistantToolRouter.route(dto.getQuestion(), actor);
            }
            if (routed.isPresent()) {
                QaAnswerVO toolAnswer = new QaAnswerVO();
                toolAnswer.setAnswer(routed.get().answer());
                toolAnswer.setLatencyMs(0);
                toolAnswer.setModelName(toolRouteModel(routed.get()));
                toolAnswer.setQuestionType("TOOL");
                toolAnswer.setSources(Collections.emptyList());
                Map<String, Object> toolStats = new LinkedHashMap<>();
                toolStats.put("route", "permission_scoped_tool");
                toolStats.put("tool_calls", routed.get().toolCalls());
                toolAnswer.setContextStats(toolStats);
                finishToolAnswer(record, toolAnswer, streamSessionId, userId, dto.getQuestion());
                sendEvent(emitter, event("meta",
                        "recordId", record.getId(), "sessionId", streamSessionId, "traceId", record.getTraceId(),
                        "contextStats", toolStats, "sources", Collections.emptyList(), "modelName", toolAnswer.getModelName()));
                sendEvent(emitter, event("delta", "content", toolAnswer.getAnswer()));
                sendEvent(emitter, event("done",
                        "recordId", record.getId(), "sessionId", streamSessionId, "traceId", record.getTraceId(),
                        "contextStats", toolStats, "sources", Collections.emptyList(), "latencyMs", 0,
                        "modelName", toolAnswer.getModelName()));
                emitter.complete();
                return;
            }

            List<Long> readyDocumentIds = kbDocumentService.listAccessibleReadyDocumentIds(actor);

            aiServiceClient.askQuestionStream(
                    dto.getQuestion(),
                    streamSessionId,
                    readyDocumentIds,
                    session.getSummary(),
                    chatHistory,
                    contextOptions,
                    record.getTraceId(),
                    aiEvent -> {
                switch (aiEvent.type()) {
                    case "meta" -> {
                        if (aiEvent.sources() != null) {
                            streamedSources.clear();
                            streamedSources.addAll(aiEvent.sources());
                        }
                        if (aiEvent.model() != null) {
                            modelName[0] = aiEvent.model();
                        }
                        if (aiEvent.rewrittenQuestion() != null) {
                            rewrittenQuestion.set(aiEvent.rewrittenQuestion());
                        }
                        if (aiEvent.rewriteApplied() != null) {
                            rewriteApplied.set(aiEvent.rewriteApplied());
                        }
                        if (aiEvent.contextStats() != null) {
                            contextStats.set(aiEvent.contextStats());
                        }
                        sendEvent(emitter, event("meta",
                                "recordId", record.getId(),
                                "sessionId", streamSessionId,
                                "traceId", record.getTraceId(),
                                "rewrittenQuestion", rewrittenQuestion.get(),
                                "rewriteApplied", rewriteApplied.get(),
                                "contextStats", contextStats.get(),
                                "sources", streamedSources,
                                "modelName", modelName[0]));
                    }
                    case "delta" -> {
                        if (aiEvent.content() != null) {
                            answerBuilder.append(aiEvent.content());
                            sendEvent(emitter, event("delta", "content", aiEvent.content()));
                        }
                    }
                    case "done" -> {
                        latencyMs[0] = aiEvent.latencyMs();
                        if (aiEvent.model() != null) {
                            modelName[0] = aiEvent.model();
                        }
                    }
                    case "error" -> throw new BusinessException("AI 流式生成失败: " + aiEvent.message());
                    default -> log.debug("Ignored AI stream event type={}", aiEvent.type());
                }
            });

            record.setAnswer(answerBuilder.toString());
            record.setLatencyMs(latencyMs[0]);
            record.setStatus("ANSWERED");
            record.setModelName(modelName[0]);
            record.setQuestionType("KB");
            updateById(record);

            saveSources(record.getId(), streamedSources);
            saveContextTrace(record, dto.getQuestion(), rewrittenQuestion.get(), rewriteApplied.get(), contextStats.get());
            updateSessionAfterAnswer(streamSessionId, userId, dto.getQuestion(), record.getTraceId());
            triggerSummaryRefresh(streamSessionId, userId);

            sendEvent(emitter, event("done",
                    "recordId", record.getId(),
                    "sessionId", streamSessionId,
                    "traceId", record.getTraceId(),
                    "rewrittenQuestion", rewrittenQuestion.get(),
                    "rewriteApplied", rewriteApplied.get(),
                    "contextStats", contextStats.get(),
                    "sources", streamedSources,
                    "latencyMs", latencyMs[0],
                    "modelName", modelName[0]));
            emitter.complete();
        } catch (Exception e) {
            record.setStatus("FAILED");
            record.setAnswer("抱歉，问答服务暂时不可用: " + e.getMessage());
            updateById(record);
            log.error("Streaming QA failed for record {}: {}", record.getId(), e.getMessage());
            try {
                sendEvent(emitter, event("error",
                        "message", e.getMessage(),
                        "recordId", record.getId(),
                        "sessionId", streamSessionId,
                        "traceId", record.getTraceId()));
            } catch (IOException sendError) {
                log.warn("Failed to send streaming error event for record {}: {}", record.getId(), sendError.getMessage());
            } finally {
                emitter.complete();
            }
        }
    }

    @Override
    public Page<QaRecordVO> pageRecords(int pageNum, int pageSize, String sessionId, Long userId) {
        Page<QaRecord> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<QaRecord> wrapper = new LambdaQueryWrapper<QaRecord>()
                .eq(QaRecord::getUserId, userId)
                .eq(StringUtils.hasText(sessionId), QaRecord::getSessionId, sessionId)
                .orderByDesc(QaRecord::getCreatedAt);

        Page<QaRecord> recordPage = this.page(page, wrapper);
        List<QaRecordVO> records = recordPage.getRecords().stream()
                .map(this::toVO)
                .collect(Collectors.toList());
        attachSources(records);

        Page<QaRecordVO> voPage = new Page<>(recordPage.getCurrent(), recordPage.getSize(), recordPage.getTotal());
        voPage.setRecords(records);
        return voPage;
    }

    @Override
    public List<QaSessionVO> listSessions(Long userId) {
        return qaSessionMapper.selectList(new LambdaQueryWrapper<QaSession>()
                        .eq(QaSession::getUserId, userId)
                        .eq(QaSession::getDeleted, false)
                        .orderByDesc(QaSession::getLastMessageAt)
                        .last("LIMIT 30"))
                .stream()
                .map(session -> {
                    QaSessionVO vo = new QaSessionVO();
                    vo.setSessionId(session.getSessionId());
                    vo.setTitle(firstText(session.getTitle(), "新对话"));
                    vo.setTurnCount(session.getTurnCount());
                    vo.setLastMessageAt(session.getLastMessageAt());
                    return vo;
                })
                .collect(Collectors.toList());
    }

    @Override
    public void deleteSession(String sessionId, Long userId) {
        QaSession session = qaSessionMapper.selectById(sessionId);
        if (session == null || Boolean.TRUE.equals(session.getDeleted()) || !userId.equals(session.getUserId())) {
            throw new BusinessException("会话不存在或无权访问");
        }
        session.setDeleted(true);
        session.setUpdatedAt(LocalDateTime.now());
        qaSessionMapper.updateById(session);
    }

    @Override
    public void submitFeedback(QaFeedbackDTO dto, Long userId) {
        QaRecord record = getById(dto.getQaRecordId());
        if (record == null || !userId.equals(record.getUserId())) {
            throw new BusinessException("问答记录不存在");
        }

        QaFeedback feedback = new QaFeedback();
        feedback.setQaRecordId(dto.getQaRecordId());
        feedback.setUserId(userId);
        feedback.setHelpful(dto.getHelpful());
        feedback.setComment(dto.getComment());
        feedback.setCreatedAt(LocalDateTime.now());
        qaFeedbackMapper.insert(feedback);
    }

    private QaRecordVO toVO(QaRecord record) {
        QaRecordVO vo = new QaRecordVO();
        BeanUtils.copyProperties(record, vo);
        return vo;
    }

    private QaSession resolveSession(String sessionId, Long userId, String question) {
        if (!StringUtils.hasText(sessionId)) {
            QaSession session = new QaSession();
            LocalDateTime now = LocalDateTime.now();
            session.setSessionId(UUID.randomUUID().toString());
            session.setUserId(userId);
            session.setTitle(defaultSessionTitle(question));
            session.setSummary("");
            session.setSummaryTurnCount(0);
            session.setTurnCount(0);
            session.setLastMessageAt(now);
            session.setDeleted(false);
            session.setCreatedAt(now);
            session.setUpdatedAt(now);
            qaSessionMapper.insert(session);
            return session;
        }

        QaSession session = qaSessionMapper.selectById(sessionId);
        if (session == null || Boolean.TRUE.equals(session.getDeleted()) || !userId.equals(session.getUserId())) {
            throw new BusinessException("会话不存在或无权访问");
        }
        return session;
    }

    private List<AiServiceClient.ChatMessage> loadRecentChatHistory(String sessionId, Long userId) {
        List<QaRecord> records = baseMapper.selectList(new LambdaQueryWrapper<QaRecord>()
                .eq(QaRecord::getSessionId, sessionId)
                .eq(QaRecord::getUserId, userId)
                .eq(QaRecord::getStatus, "ANSWERED")
                .orderByDesc(QaRecord::getCreatedAt)
                .last("LIMIT " + Math.max(1, contextMaxTurns)));
        Collections.reverse(records);

        List<AiServiceClient.ChatMessage> messages = new ArrayList<>();
        for (QaRecord record : records) {
            messages.add(new AiServiceClient.ChatMessage("user", record.getQuestion()));
            if (StringUtils.hasText(record.getAnswer())) {
                messages.add(new AiServiceClient.ChatMessage("assistant", record.getAnswer()));
            }
        }
        return messages;
    }

    private AiServiceClient.ContextOptions contextOptions() {
        return new AiServiceClient.ContextOptions(
                maxPromptTokens,
                answerReserveTokens,
                summaryBudgetTokens,
                historyBudgetTokens,
                evidenceBudgetTokens
        );
    }

    private void updateSessionAfterAnswer(String sessionId, Long userId, String question, String traceId) {
        QaSession session = qaSessionMapper.selectById(sessionId);
        if (session == null) {
            return;
        }
        long answeredTurns = baseMapper.selectCount(new LambdaQueryWrapper<QaRecord>()
                .eq(QaRecord::getSessionId, sessionId)
                .eq(QaRecord::getUserId, userId)
                .eq(QaRecord::getStatus, "ANSWERED"));

        session.setTurnCount((int) answeredTurns);
        if (!StringUtils.hasText(session.getTitle())) {
            session.setTitle(defaultSessionTitle(question));
        }
        session.setLastMessageAt(LocalDateTime.now());
        session.setLastTraceId(traceId);
        session.setUpdatedAt(LocalDateTime.now());
        qaSessionMapper.updateById(session);
    }

    private void saveContextTrace(QaRecord record, String originalQuestion, QaAnswerVO answer) {
        saveContextTrace(
                record,
                originalQuestion,
                answer.getRewrittenQuestion(),
                Boolean.TRUE.equals(answer.getRewriteApplied()),
                answer.getContextStats()
        );
    }

    private void saveContextTrace(QaRecord record, String originalQuestion, String rewrittenQuestion,
                                  Boolean rewriteApplied, Map<String, Object> contextStats) {
        try {
            Map<String, Object> stats = contextStats == null ? Collections.emptyMap() : contextStats;
            QaContextTrace trace = new QaContextTrace();
            trace.setTraceId(record.getTraceId());
            trace.setQaRecordId(record.getId());
            trace.setSessionId(record.getSessionId());
            trace.setUserId(record.getUserId());
            trace.setOriginalQuestion(originalQuestion);
            trace.setRewrittenQuestion(firstText(rewrittenQuestion, originalQuestion));
            trace.setRewriteApplied(Boolean.TRUE.equals(rewriteApplied));
            trace.setSummaryTokens(intValue(stats, "summary_tokens"));
            trace.setHistoryTokens(intValue(stats, "history_tokens"));
            trace.setEvidenceTokens(intValue(stats, "evidence_tokens"));
            trace.setTotalPromptTokens(intValue(stats, "total_prompt_tokens"));
            trace.setSelectedSourceCount(intValue(stats, "selected_source_count"));
            trace.setDroppedSourceCount(intValue(stats, "dropped_source_count"));
            trace.setContextJson(truncateJson(objectMapper.writeValueAsString(stats), 12000));
            trace.setCreatedAt(LocalDateTime.now());
            qaContextTraceMapper.insert(trace);
        } catch (Exception e) {
            log.warn("Failed to save context trace for record {}: {}", record.getId(), e.getMessage());
        }
    }

    private void triggerSummaryRefresh(String sessionId, Long userId) {
        QaSession session = qaSessionMapper.selectById(sessionId);
        if (session == null) {
            return;
        }
        int turnCount = session.getTurnCount() == null ? 0 : session.getTurnCount();
        int summaryTurnCount = session.getSummaryTurnCount() == null ? 0 : session.getSummaryTurnCount();
        if (turnCount - summaryTurnCount < summaryTriggerTurns
                && estimateRecentHistoryTokens(sessionId, userId) <= historyBudgetTokens) {
            return;
        }

        CompletableFuture.runAsync(() -> refreshSummary(sessionId, userId));
    }

    private void refreshSummary(String sessionId, Long userId) {
        try {
            QaSession session = qaSessionMapper.selectById(sessionId);
            if (session == null) {
                return;
            }
            List<QaRecord> records = baseMapper.selectList(new LambdaQueryWrapper<QaRecord>()
                    .eq(QaRecord::getSessionId, sessionId)
                    .eq(QaRecord::getUserId, userId)
                    .eq(QaRecord::getStatus, "ANSWERED")
                    .orderByAsc(QaRecord::getCreatedAt));
            int skipTurns = Math.max(0, session.getSummaryTurnCount() == null ? 0 : session.getSummaryTurnCount());
            List<AiServiceClient.ChatMessage> newTurns = records.stream()
                    .skip(skipTurns)
                    .flatMap(record -> {
                        List<AiServiceClient.ChatMessage> messages = new ArrayList<>();
                        messages.add(new AiServiceClient.ChatMessage("user", record.getQuestion()));
                        if (StringUtils.hasText(record.getAnswer())) {
                            messages.add(new AiServiceClient.ChatMessage("assistant", record.getAnswer()));
                        }
                        return messages.stream();
                    })
                    .collect(Collectors.toList());
            if (newTurns.isEmpty()) {
                return;
            }

            AiServiceClient.SummaryResult result = aiServiceClient.summarizeSession(
                    session.getSummary(),
                    newTurns,
                    summaryBudgetTokens
            );
            session.setSummary(result.summary());
            session.setSummaryTurnCount(records.size());
            session.setUpdatedAt(LocalDateTime.now());
            qaSessionMapper.updateById(session);
        } catch (Exception e) {
            log.warn("Session summary refresh failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    private int estimateRecentHistoryTokens(String sessionId, Long userId) {
        return loadRecentChatHistory(sessionId, userId).stream()
                .mapToInt(message -> estimateTokens(message.content()))
                .sum();
    }

    private void attachSources(List<QaRecordVO> records) {
        if (records == null || records.isEmpty()) {
            return;
        }

        List<Long> recordIds = records.stream()
                .map(QaRecordVO::getId)
                .collect(Collectors.toList());
        List<QaSource> sources = qaSourceMapper.selectList(new LambdaQueryWrapper<QaSource>()
                .in(QaSource::getQaRecordId, recordIds)
                .orderByAsc(QaSource::getId));
        if (sources.isEmpty()) {
            records.forEach(record -> record.setSources(Collections.emptyList()));
            return;
        }

        Map<Long, String> documentTitles = loadDocumentTitles(sources);
        Map<Long, List<QaSourceVO>> sourcesByRecord = sources.stream()
                .collect(Collectors.groupingBy(
                        QaSource::getQaRecordId,
                        LinkedHashMap::new,
                        Collectors.mapping(source -> toSourceVO(source, documentTitles), Collectors.toList())
                ));

        records.forEach(record -> record.setSources(
                sourcesByRecord.getOrDefault(record.getId(), Collections.emptyList())));
    }

    private Map<Long, String> loadDocumentTitles(List<QaSource> sources) {
        List<Long> documentIds = sources.stream()
                .map(QaSource::getDocumentId)
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());
        if (documentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return kbDocumentMapper.selectList(new LambdaQueryWrapper<KbDocument>()
                        .select(KbDocument::getId, KbDocument::getTitle, KbDocument::getFileName)
                        .in(KbDocument::getId, documentIds)).stream()
                .collect(Collectors.toMap(
                        KbDocument::getId,
                        document -> {
                            String title = firstText(document.getTitle(), document.getFileName());
                            return title == null ? "" : title;
                        },
                        (left, right) -> left
                ));
    }

    private QaSourceVO toSourceVO(QaSource source, Map<Long, String> documentTitles) {
        QaSourceVO vo = new QaSourceVO();
        vo.setDocumentId(source.getDocumentId());
        vo.setChunkId(source.getChunkUid());
        vo.setChunkUid(source.getChunkUid());
        vo.setChunkIndex(source.getChunkIndex());
        vo.setPageNo(source.getPageNo());
        vo.setSectionTitle(source.getSectionTitle());
        vo.setTitlePath(parseTitlePath(source.getTitlePath()));
        vo.setDocVersion(source.getDocVersion());
        vo.setContentHash(source.getContentHash());
        vo.setScore(source.getScore());
        vo.setRetrievalScore(source.getRetrievalScore());
        vo.setRerankScore(source.getRerankScore());
        vo.setRerankProvider(source.getRerankProvider());
        vo.setRetrievalSource(source.getRetrievalSource());
        vo.setExcerpt(source.getExcerpt());
        vo.setDocumentTitle(documentTitles.get(source.getDocumentId()));
        return vo;
    }

    private List<String> parseTitlePath(String titlePath) {
        if (!StringUtils.hasText(titlePath)) {
            return Collections.emptyList();
        }
        return Arrays.stream(titlePath.split("\\s*>\\s*"))
                .filter(StringUtils::hasText)
                .collect(Collectors.toList());
    }

    private void saveSources(Long recordId, List<QaSourceVO> sources) {
        if (sources == null) {
            return;
        }

        for (QaSourceVO src : sources) {
            QaSource source = new QaSource();
            source.setQaRecordId(recordId);
            source.setDocumentId(src.getDocumentId());
            source.setChunkUid(firstText(src.getChunkUid(), src.getChunkId()));
            source.setChunkIndex(src.getChunkIndex());
            source.setPageNo(src.getPageNo());
            source.setSectionTitle(src.getSectionTitle());
            source.setTitlePath(formatTitlePath(src.getTitlePath()));
            source.setDocVersion(src.getDocVersion());
            source.setContentHash(src.getContentHash());
            source.setScore(src.getScore());
            source.setRetrievalScore(src.getRetrievalScore());
            source.setRerankScore(src.getRerankScore());
            source.setRerankProvider(src.getRerankProvider());
            source.setRetrievalSource(src.getRetrievalSource());
            source.setExcerpt(src.getExcerpt());
            source.setCreatedAt(LocalDateTime.now());
            qaSourceMapper.insert(source);
        }
    }

    private String formatTitlePath(List<String> titlePath) {
        if (titlePath == null || titlePath.isEmpty()) {
            return null;
        }
        return titlePath.stream()
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(" > "));
    }

    private String firstText(String first, String second) {
        if (StringUtils.hasText(first)) {
            return first;
        }
        return StringUtils.hasText(second) ? second : null;
    }

    private String defaultSessionTitle(String question) {
        String text = StringUtils.hasText(question) ? question.trim() : "新会话";
        return text.length() <= 30 ? text : text.substring(0, 30);
    }

    private int intValue(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private String truncateJson(String json, int maxLength) {
        if (json == null || json.length() <= maxLength) {
            return json;
        }
        return json.substring(0, maxLength);
    }

    private int estimateTokens(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        int chineseChars = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u4e00' && c <= '\u9fff') {
                chineseChars++;
            }
        }
        int otherChars = Math.max(0, text.length() - chineseChars);
        return Math.max(1, (int) (chineseChars * 1.2 + otherChars / 4.0));
    }

    private Map<String, Object> event(String type, Object... kvPairs) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            Object value = kvPairs[i + 1];
            if (value != null) {
                event.put(String.valueOf(kvPairs[i]), value);
            }
        }
        return event;
    }

    private void sendEvent(ResponseBodyEmitter emitter, Map<String, Object> event) throws IOException {
        String line = objectMapper.writeValueAsString(event) + "\n";
        emitter.send(line, MediaType.TEXT_PLAIN);
    }
}
