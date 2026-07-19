package com.fragment.labbooking.knowledge.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.knowledge.entity.AgentRun;
import com.fragment.labbooking.knowledge.entity.AgentStep;
import com.fragment.labbooking.knowledge.entity.QaRecord;
import com.fragment.labbooking.knowledge.mapper.AgentRunMapper;
import com.fragment.labbooking.knowledge.mapper.AgentStepMapper;
import com.fragment.labbooking.knowledge.vo.QaAnswerVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentRunServiceImplTest {

    @Mock private AgentRunMapper runMapper;
    @Mock private AgentStepMapper stepMapper;

    private AgentRunServiceImpl service;
    private AgentRun persistedRun;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AgentRun.class);
        TableInfoHelper.initTableInfo(assistant, AgentStep.class);
        service = new AgentRunServiceImpl(runMapper, stepMapper, new ObjectMapper());
        persistedRun = new AgentRun();
        persistedRun.setId(42L);
        persistedRun.setTraceId("qa-trace-1");
        when(runMapper.selectOne(any())).thenReturn(persistedRun);
        when(stepMapper.selectCount(any())).thenReturn(1L);
        doAnswer(invocation -> {
            AgentRun run = invocation.getArgument(0);
            run.setId(42L);
            return 1;
        }).when(runMapper).insert(org.mockito.ArgumentMatchers.<AgentRun>any());
    }

    @Test
    void shouldLinkNativeToolAuditTraceIntoAgentSteps() {
        QaRecord record = record();
        service.start(record);

        QaAnswerVO answer = new QaAnswerVO();
        answer.setModelName("native-function-calling");
        answer.setContextStats(Map.of("tool_calls", List.of(Map.of(
                "tool_name", "reservation_context",
                "tool_trace_id", "tool-trace-1",
                "latency_ms", 12,
                "result", "SUCCESS",
                "protocol", "native_function_calling"
        ))));

        service.finishTool(record, answer);

        assertThat(persistedRun.getRoute()).isEqualTo("TOOL");
        assertThat(persistedRun.getStatus()).isEqualTo("SUCCEEDED");
        ArgumentCaptor<AgentStep> stepCaptor = ArgumentCaptor.forClass(AgentStep.class);
        verify(stepMapper, org.mockito.Mockito.times(3)).insert(stepCaptor.capture());
        assertThat(stepCaptor.getAllValues()).anySatisfy(step -> {
            assertThat(step.getStepType()).isEqualTo("TOOL_CALL");
            assertThat(step.getToolTraceId()).isEqualTo("tool-trace-1");
            assertThat(step.getDetailJson()).contains("native_function_calling");
        });
    }

    private QaRecord record() {
        QaRecord record = new QaRecord();
        record.setId(9L);
        record.setTraceId("qa-trace-1");
        record.setSessionId("session-1");
        record.setUserId(7L);
        record.setQuestionType("KB");
        return record;
    }
}
