package com.fragment.labbooking.knowledge.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fragment.labbooking.knowledge.agent.context.SessionContextPlanner;
import com.fragment.labbooking.knowledge.agent.runtime.AgentState;
import com.fragment.labbooking.knowledge.entity.QaRecord;
import com.fragment.labbooking.knowledge.vo.AgentRunVO;
import com.fragment.labbooking.knowledge.vo.AgentStepVO;
import com.fragment.labbooking.knowledge.vo.ContextTraceVO;
import com.fragment.labbooking.knowledge.vo.QaAnswerVO;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface AgentRunService {

    void start(QaRecord record);

    void beginRuntime(String traceId, AgentState.Policy policy);

    void recordRuntimeState(String traceId, AgentState state);

    Optional<AgentState> restoreRuntimeCheckpoint(String traceId, AgentState.Policy policy);

    void recordContextPlan(String traceId, ContextPlan plan);

    void recordSessionContextPlan(String traceId, SessionContextPlanner.Plan plan);

    void recordProviderUsage(String traceId, Map<String, Object> providerUsage);

    void recordToolExecution(String traceId, ToolExecution execution);

    void finishTool(QaRecord record, QaAnswerVO answer);

    void finishRag(QaRecord record, QaAnswerVO answer);

    void fail(QaRecord record, Exception exception);

    Page<AgentRunVO> page(int pageNum, int pageSize, String route, String status);

    List<AgentStepVO> listSteps(String traceId);

    ContextTraceVO getContextTrace(String traceId);

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record ContextPlan(int round, String model, List<String> requestedTools,
                       @JsonIgnore Map<String, Object> providerUsage,
                       int modelDecisionLatencyMs) {
        public ContextPlan {
            round = Math.max(0, round);
            model = model == null ? "" : model;
            requestedTools = requestedTools == null ? List.of() : List.copyOf(requestedTools);
            providerUsage = providerUsage == null ? Map.of() : Map.copyOf(providerUsage);
            modelDecisionLatencyMs = Math.max(0, modelDecisionLatencyMs);
        }

        public ContextPlan(int round, String model, List<String> requestedTools) {
            this(round, model, requestedTools, Map.of(), 0);
        }

        public ContextPlan(int round, String model, List<String> requestedTools,
                           Map<String, Object> providerUsage) {
            this(round, model, requestedTools, providerUsage, 0);
        }

        @JsonProperty("requested_tool_count")
        public int requestedToolCount() { return requestedTools.size(); }

        @JsonProperty("provider_usage")
        public Map<String, Object> observableProviderUsage() {
            return providerUsage.isEmpty() ? Map.of("reported", false) : providerUsage;
        }
    }

    record ToolExecution(String toolName, String status, int latencyMs, String toolTraceId,
                         String protocol, Map<String, Object> detail) {
        public ToolExecution {
            toolName = toolName == null ? "" : toolName;
            status = status == null ? "" : status;
            latencyMs = Math.max(0, latencyMs);
            toolTraceId = toolTraceId == null ? "" : toolTraceId;
            protocol = protocol == null ? "" : protocol;
            detail = detail == null ? Map.of() : Map.copyOf(detail);
        }
    }
}
