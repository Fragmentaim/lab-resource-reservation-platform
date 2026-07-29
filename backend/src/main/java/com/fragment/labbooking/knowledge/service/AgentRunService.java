package com.fragment.labbooking.knowledge.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fragment.labbooking.knowledge.agent.context.SessionContextPlanner;
import com.fragment.labbooking.knowledge.entity.QaRecord;
import com.fragment.labbooking.knowledge.vo.AgentRunVO;
import com.fragment.labbooking.knowledge.vo.AgentStepVO;
import com.fragment.labbooking.knowledge.vo.QaAnswerVO;

import java.util.List;
import java.util.Map;

public interface AgentRunService {

    void start(QaRecord record);

    void recordSessionContextPlan(String traceId, SessionContextPlanner.Plan plan);

    void recordToolExecution(String traceId, ToolExecution execution);

    void finishTool(QaRecord record, QaAnswerVO answer);

    void fail(QaRecord record, Exception exception);

    Page<AgentRunVO> page(int pageNum, int pageSize, String route, String status);

    List<AgentStepVO> listSteps(String traceId);

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
