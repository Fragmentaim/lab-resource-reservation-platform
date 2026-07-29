package com.fragment.labbooking.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.knowledge.agent.context.SessionContext;
import com.fragment.labbooking.knowledge.dto.QaAskDTO;
import com.fragment.labbooking.knowledge.dto.QaFeedbackDTO;
import com.fragment.labbooking.knowledge.entity.KbDocument;
import com.fragment.labbooking.knowledge.entity.QaFeedback;
import com.fragment.labbooking.knowledge.entity.QaRecord;
import com.fragment.labbooking.knowledge.entity.QaSource;
import com.fragment.labbooking.knowledge.mapper.KbDocumentMapper;
import com.fragment.labbooking.knowledge.mapper.QaFeedbackMapper;
import com.fragment.labbooking.knowledge.mapper.QaRecordMapper;
import com.fragment.labbooking.knowledge.mapper.QaSourceMapper;
import com.fragment.labbooking.knowledge.service.AiServiceClient;
import com.fragment.labbooking.knowledge.service.AgentRunService;
import com.fragment.labbooking.knowledge.service.QaRecordService;
import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.knowledge.vo.QaAnswerVO;
import com.fragment.labbooking.knowledge.vo.QaRecordVO;
import com.fragment.labbooking.knowledge.vo.QaSessionVO;
import com.fragment.labbooking.knowledge.vo.QaSourceVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
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
    private SpringAiAgentService agentChatService;

    @Autowired
    private QaSourceMapper qaSourceMapper;

    @Autowired private QaFeedbackMapper qaFeedbackMapper;
    @Autowired private QaSessionManager sessionManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public QaAnswerVO ask(QaAskDTO dto, LoginUser actor) {
        return routeAndBuildAnswer(dto, actor);
    }

    private QaAnswerVO routeAndBuildAnswer(QaAskDTO dto, LoginUser actor) {
        Long userId = actor.getId();
        // 先准备会话上下文并在需要时完成压缩，再创建本轮记录，保证记录使用的是最终上下文计划。
        SessionContext sessionContext = sessionManager.prepare(
                dto.getSessionId(), userId, dto.getQuestion());
        String sessionId = sessionContext.sessionId();

        QaRecord record = new QaRecord();
        record.setUserId(userId);
        record.setSessionId(sessionId);
        record.setQuestion(dto.getQuestion());
        record.setStatus("PENDING");
        record.setQuestionType("AGENT");
        record.setTraceId(UUID.randomUUID().toString());
        record.setCreatedAt(LocalDateTime.now());
        save(record);
        // 先持久化 PENDING 记录，后续无论成功或失败都能关联到同一个 traceId。
        agentRunService.start(record);
        if (sessionContext.plan().compactionRecommended()) {
            agentRunService.recordSessionContextPlan(record.getTraceId(), sessionContext.plan());
        }

        long routeStartedAt = System.nanoTime();
        try {
            // Spring AI 负责模型与工具的调用循环；QaRecordService 只负责把结果落为会话历史。
            SpringAiAgentService.AgentReply routed = agentChatService.answer(
                    dto.getQuestion(), actor, record.getTraceId(),
                    sessionContext);
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
            toolStats.put("selected_source_count", routed.sources().size());
            attachProviderUsage(toolStats, routed.providerUsage(), sessionContext.summaryProviderUsage());
            toolStats.put("session_context_plan", objectMapper.convertValue(sessionContext.plan(), Map.class));
            answer.setContextStats(toolStats);
            finishToolAnswer(record, answer, sessionId, userId, dto.getQuestion());
            return answer;
        } catch (Exception e) {
            // 即使对外抛错，也要把 PENDING 改为 FAILED，避免会话中留下永远未完成的轮次。
            record.setStatus("FAILED");
            record.setAnswer("抱歉，问答服务暂时不可用");
            updateById(record);
            agentRunService.fail(record, e);
            if (e instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException("问答服务暂时不可用");
        }
    }

    private int elapsedMs(long startedAt) {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private void finishToolAnswer(QaRecord record, QaAnswerVO answer, String sessionId, Long userId, String question) {
        record.setAnswer(answer.getAnswer());
        record.setLatencyMs(answer.getLatencyMs());
        record.setStatus("ANSWERED");
        record.setModelName(answer.getModelName());
        record.setQuestionType(answer.getQuestionType());
        updateById(record);
        // 引用归历史问答保存，执行轨迹归 AgentRun 保存，避免再维护一份上下文副本。
        saveSources(record.getId(), answer.getSources());
        agentRunService.finishTool(record, answer);
        sessionManager.updateAfterAnswer(sessionId, userId, question, record.getTraceId());
        answer.setRecordId(record.getId());
        answer.setSessionId(sessionId);
        answer.setTraceId(record.getTraceId());
    }

    private String toolRouteModel(SpringAiAgentService.AgentReply result) {
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
        // 批量补齐来源，避免每条问答各查一次 QaSource 造成 N+1 查询。
        attachSources(records);

        Page<QaRecordVO> voPage = new Page<>(recordPage.getCurrent(), recordPage.getSize(), recordPage.getTotal());
        voPage.setRecords(records);
        return voPage;
    }

    @Override
    public List<QaSessionVO> listSessions(Long userId) {
        return sessionManager.list(userId);
    }

    @Override
    public void deleteSession(String sessionId, Long userId) {
        sessionManager.delete(sessionId, userId);
    }

    @Override
    public void submitFeedback(QaFeedbackDTO dto, Long userId) {
        QaRecord record = getById(dto.getQaRecordId());
        if (record == null || !userId.equals(record.getUserId())) {
            throw new BusinessException("问答记录不存在");
        }

        // 反馈只能归属到自己的问答记录，不能借 recordId 给其他用户写评价。
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

    private void attachProviderUsage(Map<String, Object> stats, Map<String, Object> agentUsage,
                                     Map<String, Object> summaryUsage) {
        // Agent 回答和会话摘要可能分别调用模型，既保留明细，也提供本轮总 usage。
        stats.put("agent_provider_usage", agentUsage == null ? Map.of("reported", false) : agentUsage);
        stats.put("summary_provider_usage", summaryUsage);
        stats.put("provider_usage", mergeProviderUsage(agentUsage, summaryUsage));
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

    private void attachSources(List<QaRecordVO> records) {
        if (records == null || records.isEmpty()) {
            return;
        }

        // 一次查出当前页全部来源，再按问答记录分组组装。
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
        // QaSource 只存 documentId，标题允许更新，因此展示时读取当前文档元数据。
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

        // 复制当次引用的版本、页码和分数，文档后续重处理也不改写历史回答的证据。
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

}
