package com.fragment.labbooking.knowledge.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fragment.labbooking.knowledge.agent.AgentState;
import com.fragment.labbooking.knowledge.agent.AgentToolExecution;
import com.fragment.labbooking.knowledge.agent.ContextPlan;
import com.fragment.labbooking.knowledge.agent.SessionContextPlan;
import com.fragment.labbooking.knowledge.agent.PolicyContext;
import com.fragment.labbooking.knowledge.entity.QaRecord;
import com.fragment.labbooking.knowledge.vo.AgentRunVO;
import com.fragment.labbooking.knowledge.vo.AgentStepVO;
import com.fragment.labbooking.knowledge.vo.ContextTraceVO;
import com.fragment.labbooking.knowledge.vo.QaAnswerVO;

import java.util.List;

public interface AgentRunService {

    void start(QaRecord record);

    void beginRuntime(String traceId, PolicyContext policy);

    void recordRuntimeState(String traceId, AgentState state);

    void recordContextPlan(String traceId, ContextPlan plan);

    void recordSessionContextPlan(String traceId, SessionContextPlan plan);

    void recordToolExecution(String traceId, AgentToolExecution execution);

    void finishTool(QaRecord record, QaAnswerVO answer);

    void finishRag(QaRecord record, QaAnswerVO answer);

    void fail(QaRecord record, Exception exception);

    Page<AgentRunVO> page(int pageNum, int pageSize, String route, String status);

    List<AgentStepVO> listSteps(String traceId);

    ContextTraceVO getContextTrace(String traceId);
}
