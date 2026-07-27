package com.fragment.labbooking.knowledge.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fragment.labbooking.knowledge.agent.model.AgentToolExecution;
import com.fragment.labbooking.knowledge.agent.model.ContextPlan;
import com.fragment.labbooking.knowledge.agent.model.PolicyContext;
import com.fragment.labbooking.knowledge.agent.model.SessionContextPlan;
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

    void beginRuntime(String traceId, PolicyContext policy);

    void recordRuntimeState(String traceId, AgentState state);

    Optional<AgentState> restoreRuntimeCheckpoint(String traceId, PolicyContext policy);

    void recordContextPlan(String traceId, ContextPlan plan);

    void recordSessionContextPlan(String traceId, SessionContextPlan plan);

    void recordProviderUsage(String traceId, Map<String, Object> providerUsage);

    void recordToolExecution(String traceId, AgentToolExecution execution);

    void finishTool(QaRecord record, QaAnswerVO answer);

    void finishRag(QaRecord record, QaAnswerVO answer);

    void fail(QaRecord record, Exception exception);

    Page<AgentRunVO> page(int pageNum, int pageSize, String route, String status);

    List<AgentStepVO> listSteps(String traceId);

    ContextTraceVO getContextTrace(String traceId);
}
