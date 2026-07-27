package com.fragment.labbooking.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.knowledge.agent.context.ModelContextProfileProperties;
import com.fragment.labbooking.knowledge.agent.context.SessionContextPlanner;
import com.fragment.labbooking.knowledge.agent.model.AgentConversationContext;
import com.fragment.labbooking.knowledge.agent.model.AgentModelMapper;
import com.fragment.labbooking.knowledge.agent.model.SessionContextPlan;
import com.fragment.labbooking.knowledge.agent.model.SessionTurn;
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
import com.fragment.labbooking.knowledge.service.AgentRunService;
import com.fragment.labbooking.knowledge.service.AgentChatService;
import com.fragment.labbooking.knowledge.service.QaRecordService;
import com.fragment.labbooking.knowledge.service.SessionEventService;
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
import java.util.stream.Collectors;

@Service
@Slf4j
public class QaRecordServiceImpl extends ServiceImpl<QaRecordMapper, QaRecord>
        implements QaRecordService {

    @Autowired
    private AgentRunService agentRunService;

    @Autowired
    private KbDocumentMapper kbDocumentMapper;

    @Autowired
    private AiServiceClient aiServiceClient;

    @Autowired
    private AgentChatService agentChatService;

    @Autowired
    private QaSourceMapper qaSourceMapper;

    @Autowired
    private QaSessionMapper qaSessionMapper;

    @Autowired
    private QaContextTraceMapper qaContextTraceMapper;

    @Autowired private QaFeedbackMapper qaFeedbackMapper;
    @Autowired private SessionContextPlanner sessionContextPlanner;
    @Autowired private SessionEventService sessionEventService;
    @Autowired private ModelContextProfileProperties modelContextProfiles;

    // Recent-turn-only baseline used by context evaluations.
    @Value("${app.knowledge.model-context.compaction-strategy:SUMMARY}")
    private String compactionStrategy;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AgentModelMapper agentModelMapper;

    @Override
    public QaAnswerVO ask(QaAskDTO dto, LoginUser actor) {
        RoutedAnswer result = routeAndBuildAnswer(dto, actor);
        return result.answer();
    }

    @Override
    public QaAnswerVO resume(String traceId, LoginUser actor) {
        if (!StringUtils.hasText(traceId) || actor == null || actor.getId() == null) {
            throw new BusinessException("恢复运行需要有效的 traceId 和登录用户");
        }
        QaRecord record = getOne(new LambdaQueryWrapper<QaRecord>()
                .eq(QaRecord::getTraceId, traceId)
                .eq(QaRecord::getUserId, actor.getId())
                .last("LIMIT 1"));
        if (record == null || !"PENDING".equals(record.getStatus())) {
            throw new BusinessException("运行不存在、已结束或无权恢复");
        }
        if (agentRunService.restoreRuntimeCheckpoint(traceId, com.fragment.labbooking.knowledge.agent.model.PolicyContext.from(actor)).isEmpty()) {
            throw new BusinessException("运行恢复点不存在或已过期，请重新提问");
        }
        QaSession session = qaSessionMapper.selectById(record.getSessionId());
        if (session == null || Boolean.TRUE.equals(session.getDeleted()) || !actor.getId().equals(session.getUserId())) {
            throw new BusinessException("会话不存在或无权访问");
        }
        PreparedSessionContext prepared = prepareSessionContext(session, record.getQuestion());
        SessionContextPlan sessionPlan = prepared.plan();
        agentRunService.recordProviderUsage(record.getTraceId(), prepared.summaryProviderUsage());
        agentRunService.recordSessionContextPlan(record.getTraceId(), sessionPlan);

        long routeStartedAt = System.nanoTime();
        ToolRouteResult routed = agentChatService.answer(
                record.getQuestion(), actor, record.getSessionId(), record.getTraceId(),
                new AgentConversationContext(prepared.session().getSummary(), sessionPlan.historyMessages()));
        QaAnswerVO answer = new QaAnswerVO();
        answer.setAnswer(routed.answer());
        answer.setLatencyMs(elapsedMs(routeStartedAt));
        answer.setModelName(toolRouteModel(routed));
        answer.setQuestionType("TOOL");
        answer.setSources(routed.sources());
        answer.setClientActions(routed.clientActions());
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("route", "permission_scoped_tool_resume");
        stats.put("tool_calls", routed.toolCalls());
        stats.put("runtime_managed", routed.runtimeManaged());
        stats.put("selected_source_count", routed.sourceCount());
        attachProviderUsage(stats, routed.providerUsage(), prepared.summaryProviderUsage());
        stats.put("session_context_plan", agentModelMapper.detail(sessionPlan));
        answer.setContextStats(stats);
        finishToolAnswer(record, answer, record.getSessionId(), actor.getId(), record.getQuestion(), nextTurnNo(prepared.session()));
        return answer;
    }

    @Override
    public void askStream(QaAskDTO dto, LoginUser actor, ResponseBodyEmitter emitter) {
        RoutedAnswer result = routeAndBuildAnswer(dto, actor);
        QaAnswerVO answer = result.answer();
        Map<String, Object> toolStats = answer.getContextStats();
        String sessionId = answer.getSessionId();

        try {
            sendEvent(emitter, event("record",
                    "recordId", answer.getRecordId(),
                    "sessionId", sessionId,
                    "traceId", answer.getTraceId()));
            sendEvent(emitter, event("meta",
                    "recordId", answer.getRecordId(), "sessionId", sessionId, "traceId", answer.getTraceId(),
                    "contextStats", toolStats, "sources", answer.getSources(), "clientActions", answer.getClientActions(),
                    "modelName", answer.getModelName()));
            sendEvent(emitter, event("delta", "content", answer.getAnswer()));
            sendEvent(emitter, event("done",
                    "recordId", answer.getRecordId(), "sessionId", sessionId, "traceId", answer.getTraceId(),
                    "contextStats", toolStats, "sources", answer.getSources(), "clientActions", answer.getClientActions(), "latencyMs", 0,
                    "modelName", answer.getModelName()));
            emitter.complete();
        } catch (Exception e) {
            log.error("Streaming QA failed for record {}: {}", answer.getRecordId(), e.getMessage());
            try {
                sendEvent(emitter, event("error",
                        "message", e.getMessage(),
                        "recordId", answer.getRecordId(),
                        "sessionId", sessionId,
                        "traceId", answer.getTraceId()));
            } catch (IOException sendError) {
                log.warn("Failed to send streaming error event for record {}: {}", answer.getRecordId(), sendError.getMessage());
            } finally {
                emitter.complete();
            }
        }
    }

    // Shared by the normal and streaming endpoints.
    private RoutedAnswer routeAndBuildAnswer(QaAskDTO dto, LoginUser actor) {
        Long userId = actor.getId();
        QaSession session = resolveSession(dto.getSessionId(), userId, dto.getQuestion());
        PreparedSessionContext preparedContext = prepareSessionContext(session, dto.getQuestion());
        session = preparedContext.session();
        String sessionId = session.getSessionId();
        SessionContextPlan sessionPlan = preparedContext.plan();
        List<AiServiceClient.ChatMessage> chatHistory = sessionPlan.historyMessages();
        int turnNo = nextTurnNo(session);

        QaRecord record = new QaRecord();
        record.setUserId(userId);
        record.setSessionId(sessionId);
        record.setQuestion(dto.getQuestion());
        record.setStatus("PENDING");
        record.setQuestionType("KB");
        record.setTraceId(UUID.randomUUID().toString());
        record.setCreatedAt(LocalDateTime.now());
        save(record);
        agentRunService.start(record);
        agentRunService.recordProviderUsage(record.getTraceId(), preparedContext.summaryProviderUsage());
        sessionEventService.appendUserInput(record, turnNo);
        agentRunService.recordSessionContextPlan(record.getTraceId(), sessionPlan);

        long routeStartedAt = System.nanoTime();
        try {
            ToolRouteResult routed = agentChatService.answer(
                    dto.getQuestion(), actor, sessionId, record.getTraceId(),
                    new AgentConversationContext(session.getSummary(), chatHistory));
            QaAnswerVO answer = new QaAnswerVO();
            answer.setAnswer(routed.answer());
            answer.setLatencyMs(elapsedMs(routeStartedAt));
            answer.setModelName(toolRouteModel(routed));
            answer.setQuestionType("TOOL");
            answer.setSources(routed.sources());
            answer.setClientActions(routed.clientActions());
            Map<String, Object> toolStats = new LinkedHashMap<>();
            toolStats.put("route", "spring_ai_agent");
            toolStats.put("tool_calls", routed.toolCalls());
            toolStats.put("runtime_managed", routed.runtimeManaged());
            toolStats.put("selected_source_count", routed.sourceCount());
            attachProviderUsage(toolStats, routed.providerUsage(), preparedContext.summaryProviderUsage());
            toolStats.put("session_context_plan", agentModelMapper.detail(sessionPlan));
            answer.setContextStats(toolStats);
            finishToolAnswer(record, answer, sessionId, userId, dto.getQuestion(), turnNo);
            return new RoutedAnswer(answer);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            record.setStatus("FAILED");
            record.setAnswer("抱歉，问答服务暂时不可用: " + e.getMessage());
            updateById(record);
            agentRunService.fail(record, e);
            throw new RuntimeException(e);
        }
    }

    private record RoutedAnswer(QaAnswerVO answer) {}

    private int elapsedMs(long startedAt) {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private void finishToolAnswer(QaRecord record, QaAnswerVO answer, String sessionId, Long userId, String question,
                                  int turnNo) {
        record.setAnswer(answer.getAnswer());
        record.setLatencyMs(answer.getLatencyMs());
        record.setStatus("ANSWERED");
        record.setModelName(answer.getModelName());
        record.setQuestionType(answer.getQuestionType());
        updateById(record);
        saveSources(record.getId(), answer.getSources());
        saveContextTrace(record, question, answer);
        agentRunService.finishTool(record, answer);
        sessionEventService.appendAssistantOutput(record, answer, turnNo);
        updateSessionAfterAnswer(sessionId, userId, question, record.getTraceId());
        answer.setRecordId(record.getId());
        answer.setSessionId(sessionId);
        answer.setTraceId(record.getTraceId());
    }

    private String toolRouteModel(ToolRouteResult result) {
        Object model = result.providerUsage().get("model");
        return model == null || !StringUtils.hasText(String.valueOf(model))
                ? "spring-ai-agent" : String.valueOf(model);
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

    private List<QaRecord> loadAnsweredSessionRecords(String sessionId, Long userId) {
        return baseMapper.selectList(new LambdaQueryWrapper<QaRecord>()
                .eq(QaRecord::getSessionId, sessionId)
                .eq(QaRecord::getUserId, userId)
                .eq(QaRecord::getStatus, "ANSWERED")
                .orderByAsc(QaRecord::getCreatedAt)
                .orderByAsc(QaRecord::getId));
    }

    private List<SessionTurn> loadUncompressedSessionTurns(QaSession session) {
        List<QaRecord> records = loadAnsweredSessionRecords(session.getSessionId(), session.getUserId());
        int coveredTurnCount = Math.min(records.size(), Math.max(0,
                session.getSummaryTurnCount() == null ? 0 : session.getSummaryTurnCount()));
        return records.stream()
                .skip(coveredTurnCount)
                .map(record -> new SessionTurn(record.getId(), record.getTraceId(), record.getQuestion(), record.getAnswer()))
                .toList();
    }

    private SessionContextPlan planSessionContext(QaSession session, String question) {
        return sessionContextPlanner.plan(
                session.getSummary(), question, loadUncompressedSessionTurns(session),
                contextCapacity()
        );
    }

    private SessionContextPlanner.ContextCapacity contextCapacity() {
        ModelContextProfileProperties.Profile profile = modelContextProfiles.active();
        return new SessionContextPlanner.ContextCapacity(
                profile.getContextWindowTokens(), profile.getMaxOutputTokens(),
                profile.getSafetyMarginTokens(), profile.getCompactionTargetRatio());
    }

    private int nextTurnNo(QaSession session) {
        return (session.getTurnCount() == null ? 0 : session.getTurnCount()) + 1;
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

        String title = session.getTitle();
        if (!StringUtils.hasText(session.getTitle())) {
            title = defaultSessionTitle(question);
        }
        LocalDateTime now = LocalDateTime.now();
        QaSession update = new QaSession();
        update.setTurnCount((int) answeredTurns);
        update.setTitle(title);
        update.setLastMessageAt(now);
        update.setLastTraceId(traceId);
        update.setUpdatedAt(now);
        // Summary updates use summaryTurnCount as their version.
        qaSessionMapper.update(update, new LambdaUpdateWrapper<QaSession>()
                .eq(QaSession::getSessionId, sessionId)
                .eq(QaSession::getUserId, userId));
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
            trace.setContextJson(com.fragment.labbooking.common.util.TruncateUtil.truncate(objectMapper.writeValueAsString(stats), 12000));
            trace.setCreatedAt(LocalDateTime.now());
            qaContextTraceMapper.insert(trace);
        } catch (Exception e) {
            log.warn("Failed to save context trace for record {}: {}", record.getId(), e.getMessage());
        }
    }

    private PreparedSessionContext prepareSessionContext(QaSession initialSession, String question) {
        QaSession session = initialSession;
        List<Map<String, Object>> summaryUsages = new ArrayList<>();
        for (int attempt = 0; attempt < 3; attempt++) {
            SessionContextPlan plan = planSessionContext(session, question);
            if (!plan.compactionRecommended()) {
                return new PreparedSessionContext(session, plan, aggregateSummaryUsage(summaryUsages));
            }
            if ("SLIDING_WINDOW".equalsIgnoreCase(compactionStrategy)) {
                // Evaluation baseline: keep recent turns without creating memory.
                return new PreparedSessionContext(session, plan, aggregateSummaryUsage(summaryUsages));
            }
            AiServiceClient.SummaryResult summaryResult = compactSessionSummary(session, plan);
            summaryUsages.add(summaryResult.providerUsage());
            session = qaSessionMapper.selectById(session.getSessionId());
            if (session == null || Boolean.TRUE.equals(session.getDeleted())
                    || !initialSession.getUserId().equals(session.getUserId())) {
                throw new BusinessException("会话不存在或无权访问");
            }
        }
        throw new BusinessException("会话上下文正在更新，请稍后重试");
    }

    private AiServiceClient.SummaryResult compactSessionSummary(QaSession session, SessionContextPlan plan) {
        if (plan.deferredTurns().isEmpty()) {
            throw new BusinessException("会话上下文无法安全压缩");
        }
        int coveredTurnCount = Math.max(0, session.getSummaryTurnCount() == null ? 0 : session.getSummaryTurnCount());
        List<AiServiceClient.ChatMessage> deferredMessages = plan.deferredTurns().stream()
                .flatMap(turn -> turn.messages().stream())
                .toList();
        AiServiceClient.SummaryResult result = aiServiceClient.summarizeSession(session.getSummary(), deferredMessages);
        QaSession update = new QaSession();
        update.setSummary(result.summary());
        update.setSummaryTurnCount(coveredTurnCount + plan.deferredTurns().size());
        update.setUpdatedAt(LocalDateTime.now());
        int updated = qaSessionMapper.update(update, new LambdaUpdateWrapper<QaSession>()
                .eq(QaSession::getSessionId, session.getSessionId())
                .eq(QaSession::getUserId, session.getUserId())
                .eq(QaSession::getSummaryTurnCount, coveredTurnCount));
        if (updated == 0) {
            log.info("Session summary changed concurrently; recalculating context for {}", session.getSessionId());
        }
        return result;
    }

    private void attachProviderUsage(Map<String, Object> stats, Map<String, Object> agentUsage,
                                     Map<String, Object> summaryUsage) {
        stats.put("agent_provider_usage", agentUsage == null ? Map.of("reported", false) : agentUsage);
        stats.put("summary_provider_usage", summaryUsage);
        stats.put("provider_usage", mergeProviderUsage(agentUsage, summaryUsage));
    }

    private Map<String, Object> aggregateSummaryUsage(List<Map<String, Object>> usages) {
        Map<String, Object> total = new LinkedHashMap<>();
        long inputTokens = 0;
        long outputTokens = 0;
        long totalTokens = 0;
        long cachedInputTokens = 0;
        long summaryCallCount = 0;
        boolean reported = false;
        for (Map<String, Object> usage : usages == null ? List.<Map<String, Object>>of() : usages) {
            if (usage == null) continue;
            reported |= Boolean.TRUE.equals(usage.get("reported"));
            inputTokens += longValue(usage.get("input_tokens"));
            outputTokens += longValue(usage.get("output_tokens"));
            totalTokens += longValue(usage.get("total_tokens"));
            cachedInputTokens += longValue(usage.get("cached_input_tokens"));
            summaryCallCount += Math.max(0, longValue(usage.get("model_call_count")));
        }
        total.put("reported", reported);
        total.put("input_tokens", inputTokens);
        total.put("output_tokens", outputTokens);
        total.put("total_tokens", totalTokens > 0 ? totalTokens : inputTokens + outputTokens);
        total.put("cached_input_tokens", cachedInputTokens);
        total.put("summary_call_count", summaryCallCount);
        return total;
    }

    private Map<String, Object> mergeProviderUsage(Map<String, Object> agentUsage, Map<String, Object> summaryUsage) {
        Map<String, Object> agent = agentUsage == null ? Map.of() : agentUsage;
        Map<String, Object> summary = summaryUsage == null ? Map.of() : summaryUsage;
        long inputTokens = longValue(agent.get("input_tokens")) + longValue(summary.get("input_tokens"));
        long outputTokens = longValue(agent.get("output_tokens")) + longValue(summary.get("output_tokens"));
        long totalTokens = longValue(agent.get("total_tokens")) + longValue(summary.get("total_tokens"));
        Map<String, Object> total = new LinkedHashMap<>();
        total.put("reported", Boolean.TRUE.equals(agent.get("reported")) || Boolean.TRUE.equals(summary.get("reported")));
        total.put("input_tokens", inputTokens);
        total.put("output_tokens", outputTokens);
        total.put("total_tokens", totalTokens > 0 ? totalTokens : inputTokens + outputTokens);
        total.put("cached_input_tokens", longValue(agent.get("cached_input_tokens"))
                + longValue(summary.get("cached_input_tokens")));
        total.put("agent_model_round_count", longValue(agent.get("model_round_count")));
        total.put("summary_call_count", longValue(summary.get("summary_call_count")));
        total.put("model_round_count", longValue(agent.get("model_round_count"))
                + longValue(summary.get("summary_call_count")));
        return total;
    }

    private long longValue(Object value) {
        if (value instanceof Number number) return Math.max(0L, number.longValue());
        try {
            return Math.max(0L, Long.parseLong(String.valueOf(value)));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private record PreparedSessionContext(QaSession session, SessionContextPlan plan,
                                          Map<String, Object> summaryProviderUsage) {
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
