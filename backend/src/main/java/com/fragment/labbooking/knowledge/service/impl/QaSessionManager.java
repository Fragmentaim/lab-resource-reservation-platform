package com.fragment.labbooking.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.knowledge.agent.context.ModelContextProfileProperties;
import com.fragment.labbooking.knowledge.agent.context.SessionContextPlanner;
import com.fragment.labbooking.knowledge.entity.QaRecord;
import com.fragment.labbooking.knowledge.entity.QaSession;
import com.fragment.labbooking.knowledge.mapper.QaRecordMapper;
import com.fragment.labbooking.knowledge.mapper.QaSessionMapper;
import com.fragment.labbooking.knowledge.service.AiServiceClient;
import com.fragment.labbooking.knowledge.vo.QaSessionVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Owns session lifecycle and LLM-summary compaction. */
@Component
@Slf4j
public class QaSessionManager {

    private final QaSessionMapper sessionMapper;
    private final QaRecordMapper recordMapper;
    private final SessionContextPlanner contextPlanner;
    private final ModelContextProfileProperties contextProfiles;
    private final AiServiceClient aiServiceClient;
    private final String compactionStrategy;

    public QaSessionManager(QaSessionMapper sessionMapper, QaRecordMapper recordMapper,
                            SessionContextPlanner contextPlanner,
                            ModelContextProfileProperties contextProfiles,
                            AiServiceClient aiServiceClient,
                            @Value("${app.knowledge.model-context.compaction-strategy:SUMMARY}")
                            String compactionStrategy) {
        this.sessionMapper = sessionMapper;
        this.recordMapper = recordMapper;
        this.contextPlanner = contextPlanner;
        this.contextProfiles = contextProfiles;
        this.aiServiceClient = aiServiceClient;
        this.compactionStrategy = compactionStrategy;
    }

    public PreparedContext prepare(String sessionId, Long userId, String question) {
        QaSession initial = resolve(sessionId, userId, question);
        QaSession session = initial;
        List<Map<String, Object>> summaryUsages = new ArrayList<>();
        for (int attempt = 0; attempt < 3; attempt++) {
            SessionContextPlanner.Plan plan = plan(session, question);
            if (!plan.compactionRecommended() || "SLIDING_WINDOW".equalsIgnoreCase(compactionStrategy)) {
                return new PreparedContext(session, plan, aggregateUsage(summaryUsages));
            }
            summaryUsages.add(compact(session, plan).providerUsage());
            session = sessionMapper.selectById(session.getSessionId());
            assertOwned(session, initial.getUserId());
        }
        throw new BusinessException("会话上下文正在更新，请稍后重试");
    }

    public int nextTurnNo(QaSession session) {
        return (session.getTurnCount() == null ? 0 : session.getTurnCount()) + 1;
    }

    public void updateAfterAnswer(String sessionId, Long userId, String question, String traceId) {
        QaSession session = sessionMapper.selectById(sessionId);
        if (session == null) return;
        long answeredTurns = recordMapper.selectCount(new LambdaQueryWrapper<QaRecord>()
                .eq(QaRecord::getSessionId, sessionId)
                .eq(QaRecord::getUserId, userId)
                .eq(QaRecord::getStatus, "ANSWERED"));
        QaSession update = new QaSession();
        update.setTurnCount((int) answeredTurns);
        update.setTitle(StringUtils.hasText(session.getTitle()) ? session.getTitle() : title(question));
        update.setLastMessageAt(LocalDateTime.now());
        update.setLastTraceId(traceId);
        update.setUpdatedAt(LocalDateTime.now());
        sessionMapper.update(update, new LambdaUpdateWrapper<QaSession>()
                .eq(QaSession::getSessionId, sessionId).eq(QaSession::getUserId, userId));
    }

    public List<QaSessionVO> list(Long userId) {
        return sessionMapper.selectList(new LambdaQueryWrapper<QaSession>()
                        .eq(QaSession::getUserId, userId)
                        .eq(QaSession::getDeleted, false)
                        .orderByDesc(QaSession::getLastMessageAt)
                        .last("LIMIT 30"))
                .stream().map(session -> {
                    QaSessionVO vo = new QaSessionVO();
                    vo.setSessionId(session.getSessionId());
                    vo.setTitle(StringUtils.hasText(session.getTitle()) ? session.getTitle() : "新对话");
                    vo.setTurnCount(session.getTurnCount());
                    vo.setLastMessageAt(session.getLastMessageAt());
                    return vo;
                }).toList();
    }

    public void delete(String sessionId, Long userId) {
        QaSession session = sessionMapper.selectById(sessionId);
        assertOwned(session, userId);
        session.setDeleted(true);
        session.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(session);
    }

    private QaSession resolve(String sessionId, Long userId, String question) {
        if (StringUtils.hasText(sessionId)) {
            QaSession session = sessionMapper.selectById(sessionId);
            assertOwned(session, userId);
            return session;
        }
        LocalDateTime now = LocalDateTime.now();
        QaSession session = new QaSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setTitle(title(question));
        session.setSummary("");
        session.setSummaryTurnCount(0);
        session.setTurnCount(0);
        session.setLastMessageAt(now);
        session.setDeleted(false);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessionMapper.insert(session);
        return session;
    }

    private SessionContextPlanner.Plan plan(QaSession session, String question) {
        List<QaRecord> records = recordMapper.selectList(new LambdaQueryWrapper<QaRecord>()
                .eq(QaRecord::getSessionId, session.getSessionId())
                .eq(QaRecord::getUserId, session.getUserId())
                .eq(QaRecord::getStatus, "ANSWERED")
                .orderByAsc(QaRecord::getCreatedAt).orderByAsc(QaRecord::getId));
        int covered = Math.min(records.size(), Math.max(0,
                session.getSummaryTurnCount() == null ? 0 : session.getSummaryTurnCount()));
        List<SessionContextPlanner.Turn> turns = records.stream().skip(covered)
                .map(record -> new SessionContextPlanner.Turn(
                        record.getId(), record.getTraceId(), record.getQuestion(), record.getAnswer()))
                .toList();
        ModelContextProfileProperties.Profile profile = contextProfiles.active();
        return contextPlanner.plan(session.getSummary(), question, turns,
                new SessionContextPlanner.ContextCapacity(profile.getContextWindowTokens(),
                        profile.getMaxOutputTokens(), profile.getSafetyMarginTokens(),
                        profile.getCompactionTargetRatio()));
    }

    private AiServiceClient.SummaryResult compact(QaSession session, SessionContextPlanner.Plan plan) {
        if (plan.deferredTurns().isEmpty()) throw new BusinessException("会话上下文无法安全压缩");
        int covered = Math.max(0, session.getSummaryTurnCount() == null ? 0 : session.getSummaryTurnCount());
        List<AiServiceClient.ChatMessage> messages = plan.deferredTurns().stream()
                .flatMap(turn -> turn.messages().stream()).toList();
        AiServiceClient.SummaryResult result = aiServiceClient.summarizeSession(session.getSummary(), messages);
        QaSession update = new QaSession();
        update.setSummary(result.summary());
        update.setSummaryTurnCount(covered + plan.deferredTurns().size());
        update.setUpdatedAt(LocalDateTime.now());
        int updated = sessionMapper.update(update, new LambdaUpdateWrapper<QaSession>()
                .eq(QaSession::getSessionId, session.getSessionId())
                .eq(QaSession::getUserId, session.getUserId())
                .eq(QaSession::getSummaryTurnCount, covered));
        if (updated == 0) log.info("Session summary changed concurrently; recalculating {}", session.getSessionId());
        return result;
    }

    private Map<String, Object> aggregateUsage(List<Map<String, Object>> usages) {
        long input = 0, output = 0, total = 0, cached = 0, calls = 0;
        boolean reported = false;
        for (Map<String, Object> usage : usages) {
            if (usage == null) continue;
            reported |= Boolean.TRUE.equals(usage.get("reported"));
            input += number(usage.get("input_tokens"));
            output += number(usage.get("output_tokens"));
            total += number(usage.get("total_tokens"));
            cached += number(usage.get("cached_input_tokens"));
            calls += number(usage.get("model_call_count"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reported", reported);
        result.put("input_tokens", input);
        result.put("output_tokens", output);
        result.put("total_tokens", total > 0 ? total : input + output);
        result.put("cached_input_tokens", cached);
        result.put("summary_call_count", calls);
        return result;
    }

    private long number(Object value) {
        if (value instanceof Number number) return Math.max(0, number.longValue());
        try { return Math.max(0, Long.parseLong(String.valueOf(value))); }
        catch (Exception ignored) { return 0; }
    }

    private void assertOwned(QaSession session, Long userId) {
        if (session == null || Boolean.TRUE.equals(session.getDeleted()) || !userId.equals(session.getUserId())) {
            throw new BusinessException("会话不存在或无权访问");
        }
    }

    private String title(String question) {
        String text = StringUtils.hasText(question) ? question.trim() : "新会话";
        return text.length() <= 30 ? text : text.substring(0, 30);
    }

    public record PreparedContext(QaSession session, SessionContextPlanner.Plan plan,
                                  Map<String, Object> summaryProviderUsage) { }
}
