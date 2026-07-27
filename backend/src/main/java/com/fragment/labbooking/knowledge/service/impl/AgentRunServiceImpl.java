package com.fragment.labbooking.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.knowledge.agent.model.AgentToolExecution;
import com.fragment.labbooking.knowledge.agent.model.ContextPlan;
import com.fragment.labbooking.knowledge.agent.model.PolicyContext;
import com.fragment.labbooking.knowledge.agent.model.SessionContextPlan;
import com.fragment.labbooking.knowledge.agent.runtime.AgentState;
import com.fragment.labbooking.knowledge.entity.AgentRun;
import com.fragment.labbooking.knowledge.entity.AgentRuntimeCheckpoint;
import com.fragment.labbooking.knowledge.entity.AgentStep;
import com.fragment.labbooking.knowledge.entity.QaContextTrace;
import com.fragment.labbooking.knowledge.entity.QaRecord;
import com.fragment.labbooking.common.util.TruncateUtil;
import com.fragment.labbooking.knowledge.mapper.AgentRunMapper;
import com.fragment.labbooking.knowledge.mapper.AgentRuntimeCheckpointMapper;
import com.fragment.labbooking.knowledge.mapper.AgentStepMapper;
import com.fragment.labbooking.knowledge.mapper.QaContextTraceMapper;
import com.fragment.labbooking.knowledge.service.AgentRunService;
import com.fragment.labbooking.knowledge.vo.ContextTraceVO;
import com.fragment.labbooking.knowledge.vo.AgentRunVO;
import com.fragment.labbooking.knowledge.vo.AgentStepVO;
import com.fragment.labbooking.knowledge.vo.QaAnswerVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Stores sanitized run metadata; raw content remains in domain tables. */
@Service
@Slf4j
public class AgentRunServiceImpl implements AgentRunService {

    private static final String RUNNING = "RUNNING";
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String FAILED = "FAILED";

    private final AgentRunMapper agentRunMapper;
    private final AgentRuntimeCheckpointMapper checkpointMapper;
    private final AgentStepMapper agentStepMapper;
    private final QaContextTraceMapper qaContextTraceMapper;
    private final ObjectMapper objectMapper;

    @Value("${app.knowledge.agent-checkpoint.ttl-seconds:900}")
    private long checkpointTtlSeconds;

    public AgentRunServiceImpl(AgentRunMapper agentRunMapper, AgentRuntimeCheckpointMapper checkpointMapper,
                               AgentStepMapper agentStepMapper,
                               QaContextTraceMapper qaContextTraceMapper,
                               ObjectMapper objectMapper) {
        this.agentRunMapper = agentRunMapper;
        this.checkpointMapper = checkpointMapper;
        this.agentStepMapper = agentStepMapper;
        this.qaContextTraceMapper = qaContextTraceMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void start(QaRecord record) {
        safely(record, () -> {
            AgentRun run = new AgentRun();
            run.setTraceId(record.getTraceId());
            run.setQaRecordId(record.getId());
            run.setSessionId(record.getSessionId());
            run.setUserId(record.getUserId());
            run.setRoute("PENDING");
            run.setStatus(RUNNING);
            run.setSourceCount(0);
            run.setUsageReported(false);
            run.setInputTokens(0L);
            run.setOutputTokens(0L);
            run.setCachedInputTokens(0L);
            run.setTotalTokens(0L);
            run.setModelCallCount(0);
            run.setCreatedAt(LocalDateTime.now());
            agentRunMapper.insert(run);
            insertStep(run.getId(), 1, "REQUEST", "qa_request", SUCCEEDED, 0, null,
                    Map.of("question_type", record.getQuestionType()));
        });
    }

    @Override
    public void beginRuntime(String traceId, PolicyContext policy) {
        safely(traceId, () -> {
            AgentRun run = findRun(traceId);
            if (run == null) {
                return;
            }
            run.setRoute("AGENT_RUNTIME");
            agentRunMapper.updateById(run);
            insertStep(run.getId(), nextStepNo(run.getId()), "STATE", "agent_runtime_started", SUCCEEDED, 0, null,
                    policy.safeAttributes());
        });
    }

    @Override
    public void recordRuntimeState(String traceId, AgentState state) {
        safely(traceId, () -> {
            AgentRun run = findRun(traceId);
            if (run != null) {
                insertStep(run.getId(), nextStepNo(run.getId()), "STATE", "agent_state", SUCCEEDED, 0, null,
                        state.safeSnapshot());
                upsertCheckpoint(run, state);
            }
        });
    }

    @Override
    public Optional<AgentState> restoreRuntimeCheckpoint(String traceId, PolicyContext policy) {
        if (!StringUtils.hasText(traceId) || policy == null || policy.userId() == null) {
            return Optional.empty();
        }
        try {
            AgentRuntimeCheckpoint stored = checkpointMapper.selectById(traceId);
            if (stored == null) {
                return Optional.empty();
            }
            if (!policy.userId().equals(stored.getUserId())
                    || stored.getExpiresAt() == null || !stored.getExpiresAt().isAfter(LocalDateTime.now())) {
                checkpointMapper.deleteById(traceId);
                return Optional.empty();
            }
            AgentState.Checkpoint checkpoint = deserializeCheckpoint(stored.getCheckpointJson());
            return Optional.of(AgentState.restore(checkpoint, policy));
        } catch (Exception exception) {
            log.warn("Failed to restore agent checkpoint. traceId={}, reason={}", traceId, exception.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void recordContextPlan(String traceId, ContextPlan plan) {
        safely(traceId, () -> {
            AgentRun run = findRun(traceId);
            if (run != null) {
                addProviderUsage(run, plan.providerUsage(), 1);
                agentRunMapper.updateById(run);
                insertStep(run.getId(), nextStepNo(run.getId()), "PLAN", "model_tool_plan", SUCCEEDED, 0, null,
                        plan.safeDetail());
            }
        });
    }

    @Override
    public void recordSessionContextPlan(String traceId, SessionContextPlan plan) {
        safely(traceId, () -> {
            AgentRun run = findRun(traceId);
            if (run != null) {
                insertStep(run.getId(), nextStepNo(run.getId()), "PLAN", "session_context_plan", SUCCEEDED, 0, null,
                        plan.safeDetail());
            }
        });
    }

    @Override
    public void recordProviderUsage(String traceId, Map<String, Object> providerUsage) {
        if (!StringUtils.hasText(traceId) || providerUsage == null || providerUsage.isEmpty()) {
            return;
        }
        safely(traceId, () -> {
            AgentRun run = findRun(traceId);
            if (run != null) {
                addProviderUsage(run, providerUsage, 0);
                agentRunMapper.updateById(run);
                insertStep(run.getId(), nextStepNo(run.getId()), "USAGE", "provider_usage", SUCCEEDED, 0, null,
                        Map.of("provider_usage", providerUsage));
            }
        });
    }

    @Override
    public void recordToolExecution(String traceId, AgentToolExecution execution) {
        safely(traceId, () -> {
            AgentRun run = findRun(traceId);
            if (run != null) {
                insertStep(run.getId(), nextStepNo(run.getId()), "TOOL_CALL", execution.toolName(), execution.status(),
                        execution.latencyMs(), execution.toolTraceId(), execution.safeDetail());
            }
        });
    }

    @Override
    public void finishTool(QaRecord record, QaAnswerVO answer) {
        safely(record, () -> finish(record, answer, runtimeManaged(answer) ? "AGENT_RUNTIME" : "TOOL", toolSteps(answer),
                intValue(safeStats(answer.getContextStats()).get("selected_source_count"))));
    }

    @Override
    public void finishRag(QaRecord record, QaAnswerVO answer) {
        safely(record, () -> {
            int sourceCount = answer.getSources() == null ? 0 : answer.getSources().size();
            Map<String, Object> stats = safeStats(answer.getContextStats());
            List<StepData> steps = List.of(
                    new StepData("RETRIEVAL", "knowledge_retrieval", SUCCEEDED, 0, null, retrievalDetail(stats, sourceCount)),
                    new StepData("ANSWER", "model_answer", SUCCEEDED, safeLatency(answer.getLatencyMs()), null,
                            Map.of("model", safeText(answer.getModelName()), "source_count", sourceCount))
            );
            finish(record, answer, "KB_RAG", steps, sourceCount);
        });
    }

    @Override
    public void fail(QaRecord record, Exception exception) {
        safely(record, () -> {
            AgentRun run = findRun(record.getTraceId());
            if (run == null) {
                return;
            }
            run.setStatus(FAILED);
            run.setFinishedAt(LocalDateTime.now());
            agentRunMapper.updateById(run);
            clearCheckpoint(record.getTraceId());
            insertStep(run.getId(), nextStepNo(run.getId()), "FAILURE", "execution_failed", FAILED, 0, null,
                    Map.of("error_type", exception.getClass().getSimpleName(), "message", safeText(exception.getMessage())));
        });
    }

    @Override
    public Page<AgentRunVO> page(int pageNum, int pageSize, String route, String status) {
        int current = Math.max(1, pageNum);
        int size = Math.min(Math.max(1, pageSize), 100);
        Page<AgentRun> data = agentRunMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<AgentRun>()
                        .eq(StringUtils.hasText(route), AgentRun::getRoute, route)
                        .eq(StringUtils.hasText(status), AgentRun::getStatus, status)
                        .orderByDesc(AgentRun::getCreatedAt)
                        .orderByDesc(AgentRun::getId));
        Page<AgentRunVO> result = new Page<>(data.getCurrent(), data.getSize(), data.getTotal());
        result.setRecords(data.getRecords().stream().map(this::toRunVO).toList());
        return result;
    }

    @Override
    public List<AgentStepVO> listSteps(String traceId) {
        AgentRun run = findRun(traceId);
        if (run == null) {
            return Collections.emptyList();
        }
        return agentStepMapper.selectList(new LambdaQueryWrapper<AgentStep>()
                        .eq(AgentStep::getAgentRunId, run.getId())
                        .orderByAsc(AgentStep::getStepNo)
                        .orderByAsc(AgentStep::getId))
                .stream()
                .map(this::toStepVO)
                .toList();
    }

    @Override
    public ContextTraceVO getContextTrace(String traceId) {
        if (!StringUtils.hasText(traceId)) {
            return null;
        }
        QaContextTrace trace = qaContextTraceMapper.selectOne(new LambdaQueryWrapper<QaContextTrace>()
                .eq(QaContextTrace::getTraceId, traceId)
                .last("LIMIT 1"));
        if (trace == null) {
            return null;
        }
        ContextTraceVO vo = new ContextTraceVO();
        vo.setTraceId(trace.getTraceId());
        vo.setRewriteApplied(Boolean.TRUE.equals(trace.getRewriteApplied()));
        vo.setSummaryTokens(safeMetric(trace.getSummaryTokens()));
        vo.setHistoryTokens(safeMetric(trace.getHistoryTokens()));
        vo.setEvidenceTokens(safeMetric(trace.getEvidenceTokens()));
        vo.setTotalPromptTokens(safeMetric(trace.getTotalPromptTokens()));
        vo.setSelectedSourceCount(safeMetric(trace.getSelectedSourceCount()));
        vo.setDroppedSourceCount(safeMetric(trace.getDroppedSourceCount()));
        vo.setCreatedAt(trace.getCreatedAt());
        return vo;
    }

    private void finish(QaRecord record, QaAnswerVO answer, String route, List<StepData> steps, int sourceCount) {
        AgentRun run = findRun(record.getTraceId());
        if (run == null) {
            return;
        }
        run.setRoute(route);
        run.setModelName(answer.getModelName());
        run.setStatus(SUCCEEDED);
        run.setTotalLatencyMs(safeLatency(answer.getLatencyMs()));
        run.setSourceCount(sourceCount);
        applyProviderUsageSnapshot(run, mapValue(safeStats(answer.getContextStats()).get("provider_usage")));
        run.setFinishedAt(LocalDateTime.now());
        agentRunMapper.updateById(run);
        clearCheckpoint(record.getTraceId());

        int stepNo = nextStepNo(run.getId());
        for (StepData step : steps) {
            insertStep(run.getId(), stepNo++, step.type(), step.name(), step.status(), step.latencyMs(),
                    step.toolTraceId(), step.detail());
        }
    }

    private List<StepData> toolSteps(QaAnswerVO answer) {
        Map<String, Object> stats = safeStats(answer.getContextStats());
        if (runtimeManaged(answer)) {
            return List.of(new StepData("ANSWER", "agent_answer", SUCCEEDED, safeLatency(answer.getLatencyMs()), null,
                    Map.of("model", safeText(answer.getModelName()), "runtime", "native_function_calling")));
        }
        Object rawCalls = stats.get("tool_calls");
        List<?> calls = rawCalls instanceof List<?> value ? value : Collections.emptyList();
        List<StepData> steps = new java.util.ArrayList<>();
        for (Object rawCall : calls) {
            if (!(rawCall instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Map<String, Object> call = new LinkedHashMap<>();
            rawMap.forEach((key, value) -> call.put(String.valueOf(key), value));
            steps.add(new StepData(
                    "TOOL_CALL",
                    safeText(call.get("tool_name")),
                    safeText(call.get("result")),
                    intValue(call.get("latency_ms")),
                    safeText(call.get("tool_trace_id")),
                    Map.of("protocol", safeText(call.get("protocol")), "result", safeText(call.get("result")))
            ));
        }
        steps.add(new StepData("ANSWER", "tool_answer", SUCCEEDED, safeLatency(answer.getLatencyMs()), null,
                Map.of("model", safeText(answer.getModelName()), "tool_call_count", steps.size())));
        return steps;
    }

    private boolean runtimeManaged(QaAnswerVO answer) {
        return Boolean.TRUE.equals(safeStats(answer.getContextStats()).get("runtime_managed"));
    }

    private Map<String, Object> retrievalDetail(Map<String, Object> stats, int sourceCount) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("source_count", sourceCount);
        copyMetric(stats, detail, "selected_source_count");
        copyMetric(stats, detail, "dropped_source_count");
        copyMetric(stats, detail, "evidence_tokens");
        copyMetric(stats, detail, "history_tokens");
        copyMetric(stats, detail, "summary_tokens");
        copyMetric(stats, detail, "total_prompt_tokens");
        return detail;
    }

    private void copyMetric(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private AgentRun findRun(String traceId) {
        if (!StringUtils.hasText(traceId)) {
            return null;
        }
        return agentRunMapper.selectOne(new LambdaQueryWrapper<AgentRun>()
                .eq(AgentRun::getTraceId, traceId)
                .last("LIMIT 1"));
    }

    private void upsertCheckpoint(AgentRun run, AgentState state) {
        LocalDateTime now = LocalDateTime.now();
        AgentRuntimeCheckpoint checkpoint = new AgentRuntimeCheckpoint();
        checkpoint.setTraceId(run.getTraceId());
        checkpoint.setAgentRunId(run.getId());
        checkpoint.setUserId(run.getUserId());
        checkpoint.setSessionId(run.getSessionId());
        checkpoint.setCheckpointJson(serializeCheckpoint(state.checkpoint()));
        checkpoint.setExpiresAt(now.plusSeconds(Math.max(60L, checkpointTtlSeconds)));
        checkpoint.setUpdatedAt(now);
        if (checkpointMapper.selectById(run.getTraceId()) == null) {
            checkpointMapper.insert(checkpoint);
        } else {
            checkpointMapper.updateById(checkpoint);
        }
    }

    private String serializeCheckpoint(AgentState.Checkpoint checkpoint) {
        try {
            return objectMapper.writeValueAsString(checkpoint);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot serialize agent checkpoint", exception);
        }
    }

    private AgentState.Checkpoint deserializeCheckpoint(String json) throws Exception {
        return objectMapper.readValue(json, AgentState.Checkpoint.class);
    }

    private void clearCheckpoint(String traceId) {
        if (StringUtils.hasText(traceId)) {
            checkpointMapper.deleteById(traceId);
        }
    }

    private int nextStepNo(Long runId) {
        Long count = agentStepMapper.selectCount(new LambdaQueryWrapper<AgentStep>()
                .eq(AgentStep::getAgentRunId, runId));
        return (count == null ? 0 : count.intValue()) + 1;
    }

    private void insertStep(Long runId, int stepNo, String type, String name, String status, int latencyMs,
                            String toolTraceId, Map<String, Object> detail) {
        AgentStep step = new AgentStep();
        step.setAgentRunId(runId);
        step.setStepNo(stepNo);
        step.setStepType(TruncateUtil.truncate(type, 32));
        step.setName(TruncateUtil.truncate(name, 64));
        step.setStatus(TruncateUtil.truncate(status, 16));
        step.setLatencyMs(Math.max(0, latencyMs));
        step.setToolTraceId(TruncateUtil.truncate(toolTraceId, 64));
        step.setDetailJson(detailJson(detail));
        step.setCreatedAt(LocalDateTime.now());
        agentStepMapper.insert(step);
    }

    private AgentRunVO toRunVO(AgentRun run) {
        AgentRunVO vo = new AgentRunVO();
        BeanUtils.copyProperties(run, vo);
        Long count = agentStepMapper.selectCount(new LambdaQueryWrapper<AgentStep>()
                .eq(AgentStep::getAgentRunId, run.getId()));
        vo.setStepCount(count == null ? 0 : count.intValue());
        return vo;
    }

    private AgentStepVO toStepVO(AgentStep step) {
        AgentStepVO vo = new AgentStepVO();
        BeanUtils.copyProperties(step, vo);
        vo.setDetail(parseDetail(step.getDetailJson()));
        return vo;
    }

    private Map<String, Object> safeStats(Map<String, Object> stats) {
        return stats == null ? Collections.emptyMap() : stats;
    }

    private void addProviderUsage(AgentRun run, Map<String, Object> usage, int defaultCallCount) {
        Map<String, Object> safeUsage = usage == null ? Map.of() : usage;
        run.setUsageReported(Boolean.TRUE.equals(run.getUsageReported())
                || Boolean.TRUE.equals(safeUsage.get("reported")));
        run.setInputTokens(safeLong(run.getInputTokens()) + safeLongValue(safeUsage.get("input_tokens")));
        run.setOutputTokens(safeLong(run.getOutputTokens()) + safeLongValue(safeUsage.get("output_tokens")));
        run.setCachedInputTokens(safeLong(run.getCachedInputTokens())
                + safeLongValue(safeUsage.get("cached_input_tokens")));
        long reportedTotal = safeLongValue(safeUsage.get("total_tokens"));
        if (reportedTotal <= 0) {
            reportedTotal = safeLongValue(safeUsage.get("input_tokens"))
                    + safeLongValue(safeUsage.get("output_tokens"));
        }
        run.setTotalTokens(safeLong(run.getTotalTokens()) + reportedTotal);
        int callCount = usageCallCount(safeUsage, defaultCallCount);
        run.setModelCallCount(safeMetric(run.getModelCallCount()) + callCount);
    }

    private void applyProviderUsageSnapshot(AgentRun run, Map<String, Object> usage) {
        if (usage.isEmpty()) {
            return;
        }
        long inputTokens = safeLongValue(usage.get("input_tokens"));
        long outputTokens = safeLongValue(usage.get("output_tokens"));
        long totalTokens = safeLongValue(usage.get("total_tokens"));
        run.setUsageReported(Boolean.TRUE.equals(usage.get("reported")));
        run.setInputTokens(inputTokens);
        run.setOutputTokens(outputTokens);
        run.setCachedInputTokens(safeLongValue(usage.get("cached_input_tokens")));
        run.setTotalTokens(totalTokens > 0 ? totalTokens : inputTokens + outputTokens);
        run.setModelCallCount(usageCallCount(usage, safeMetric(run.getModelCallCount())));
    }

    private int usageCallCount(Map<String, Object> usage, int fallback) {
        long count = safeLongValue(usage.get("model_call_count"));
        if (count <= 0) {
            count = safeLongValue(usage.get("model_round_count"));
        }
        if (count <= 0) {
            count = safeLongValue(usage.get("summary_call_count"));
        }
        return count <= 0 ? Math.max(0, fallback) : (int) Math.min(Integer.MAX_VALUE, count);
    }

    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private long safeLong(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }

    private long safeLongValue(Object value) {
        if (value instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        try {
            return Math.max(0L, Long.parseLong(String.valueOf(value)));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private Map<String, Object> parseDetail(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception exception) {
            return Map.of("parse_error", "detail unavailable");
        }
    }

    private String detailJson(Map<String, Object> detail) {
        try {
            return TruncateUtil.truncate(objectMapper.writeValueAsString(detail == null ? Collections.emptyMap() : detail), 2000);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private void safely(QaRecord record, Runnable task) {
        try {
            task.run();
        } catch (Exception exception) {
            log.warn("Failed to persist agent run trace. qaRecordId={}, traceId={}, reason={}",
                    record == null ? null : record.getId(), record == null ? null : record.getTraceId(), exception.getMessage());
        }
    }

    private void safely(String traceId, Runnable task) {
        try {
            task.run();
        } catch (Exception exception) {
            log.warn("Failed to persist agent runtime event. traceId={}, reason={}", traceId, exception.getMessage());
        }
    }

    private int safeLatency(Integer latencyMs) {
        return latencyMs == null ? 0 : Math.max(0, latencyMs);
    }

    private int safeMetric(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(value)));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String safeText(Object value) {
        return value == null ? "" : TruncateUtil.truncate(String.valueOf(value), 256);
    }

    private record StepData(String type, String name, String status, int latencyMs,
                            String toolTraceId, Map<String, Object> detail) {
    }
}
