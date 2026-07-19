package com.fragment.labbooking.knowledge.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fragment.labbooking.knowledge.entity.QaRecord;
import com.fragment.labbooking.knowledge.vo.AgentRunVO;
import com.fragment.labbooking.knowledge.vo.AgentStepVO;
import com.fragment.labbooking.knowledge.vo.ContextTraceVO;
import com.fragment.labbooking.knowledge.vo.QaAnswerVO;

import java.util.List;

public interface AgentRunService {

    void start(QaRecord record);

    void finishTool(QaRecord record, QaAnswerVO answer);

    void finishRag(QaRecord record, QaAnswerVO answer);

    void fail(QaRecord record, Exception exception);

    Page<AgentRunVO> page(int pageNum, int pageSize, String route, String status);

    List<AgentStepVO> listSteps(String traceId);

    ContextTraceVO getContextTrace(String traceId);
}
