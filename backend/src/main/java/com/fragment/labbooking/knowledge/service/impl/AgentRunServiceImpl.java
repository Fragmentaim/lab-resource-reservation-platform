package com.fragment.labbooking.knowledge.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.knowledge.entity.AgentRun;
import com.fragment.labbooking.knowledge.entity.AgentStep;
import com.fragment.labbooking.knowledge.entity.QaRecord;
import com.fragment.labbooking.knowledge.mapper.AgentRunMapper;
import com.fragment.labbooking.knowledge.mapper.AgentStepMapper;
import com.fragment.labbooking.knowledge.service.AgentRunService;
import com.fragment.labbooking.knowledge.vo.AgentRunVO;
import com.fragment.labbooking.knowledge.vo.AgentStepVO;
import com.fragment.labbooking.knowledge.vo.QaAnswerVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists a privacy-safe execution view for one QA record. Raw questions,
 * answers and tool outputs stay in their domain tables instead of being copied
 * into the observability tables.
 */
@Service
@Slf4j
public class AgentRunServiceImpl implements AgentRunService {

    private static final String RUNNING = "RUNNING";
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String FAILED = "FAILED";

    private final AgentRunMapper agentRunMapper;
    private final AgentStepMapper agentStepMapper;
    private final ObjectMapper objectMapper;

    public AgentRunServiceImpl(AgentRunMapper agentRunMapper, AgentStepMapper agentStepMapper,
                               ObjectMapper objectMapper) {
        this.agentRunMapper = agentRunMapper;
        this.agentStepMapper = agentStepMapper;
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
            run.setCreatedAt(LocalDateTime.now());
            agentRunMapper.insert(run);
            insertStep(run.getId(), 1, "REQUEST", "qa_request", SUCCEEDED, 0, null,
                    Map.of("question_type", record.getQuestionType()));
        });
    }

    @Override
    public void finishTool(QaRecord record, QaAnswerVO answer) {
        safely(record, () -> finish(record, answer, "TOOL", toolSteps(answer), 0));
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
        run.setFinishedAt(LocalDateTime.now());
        agentRunMapper.updateById(run);

        int stepNo = nextStepNo(run.getId());
        for (StepData step : steps) {
            insertStep(run.getId(), stepNo++, step.type(), step.name(), step.status(), step.latencyMs(),
                    step.toolTraceId(), step.detail());
        }
    }

    private List<StepData> toolSteps(QaAnswerVO answer) {
        Map<String, Object> stats = safeStats(answer.getContextStats());
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
        step.setStepType(truncate(type, 32));
        step.setName(truncate(name, 64));
        step.setStatus(truncate(status, 16));
        step.setLatencyMs(Math.max(0, latencyMs));
        step.setToolTraceId(truncate(toolTraceId, 64));
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
            return truncate(objectMapper.writeValueAsString(detail == null ? Collections.emptyMap() : detail), 2000);
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

    private int safeLatency(Integer latencyMs) {
        return latencyMs == null ? 0 : Math.max(0, latencyMs);
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
        return value == null ? "" : truncate(String.valueOf(value), 256);
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

    private record StepData(String type, String name, String status, int latencyMs,
                            String toolTraceId, Map<String, Object> detail) {
    }
}
