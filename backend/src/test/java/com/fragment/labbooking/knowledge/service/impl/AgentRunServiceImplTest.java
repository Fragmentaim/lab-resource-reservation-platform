package com.fragment.labbooking.knowledge.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.knowledge.entity.AgentRun;
import com.fragment.labbooking.knowledge.entity.AgentStep;
import com.fragment.labbooking.knowledge.entity.QaContextTrace;
import com.fragment.labbooking.knowledge.entity.QaRecord;
import com.fragment.labbooking.knowledge.mapper.AgentRunMapper;
import com.fragment.labbooking.knowledge.mapper.AgentStepMapper;
import com.fragment.labbooking.knowledge.mapper.QaContextTraceMapper;
import com.fragment.labbooking.knowledge.service.AgentRunService;
import com.fragment.labbooking.knowledge.vo.ContextTraceVO;
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
    @Mock private QaContextTraceMapper contextTraceMapper;

    private AgentRunServiceImpl service;
    private AgentRun persistedRun;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AgentRun.class);
        TableInfoHelper.initTableInfo(assistant, AgentStep.class);
        TableInfoHelper.initTableInfo(assistant, QaContextTrace.class);
        service = new AgentRunServiceImpl(runMapper, stepMapper, contextTraceMapper,
                new ObjectMapper());
        persistedRun = new AgentRun();
        persistedRun.setId(42L);
        persistedRun.setTraceId("qa-trace-1");
        persistedRun.setUserId(7L);
        persistedRun.setSessionId("session-1");
    }

    private void stubPersistedRun() {
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
        stubPersistedRun();
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

        service.recordToolExecution("qa-trace-1", new AgentRunService.ToolExecution(
                "reservation_context", "SUCCESS", 12, "tool-trace-1",
                "spring_ai_annotated_tool", Map.of()));

        service.finishTool(record, answer);

        assertThat(persistedRun.getRoute()).isEqualTo("AGENT_RUNTIME");
        assertThat(persistedRun.getStatus()).isEqualTo("SUCCEEDED");
        ArgumentCaptor<AgentStep> stepCaptor = ArgumentCaptor.forClass(AgentStep.class);
        verify(stepMapper, org.mockito.Mockito.times(3)).insert(stepCaptor.capture());
        assertThat(stepCaptor.getAllValues()).anySatisfy(step -> {
            assertThat(step.getStepType()).isEqualTo("TOOL_CALL");
            assertThat(step.getToolTraceId()).isEqualTo("tool-trace-1");
            assertThat(step.getDetailJson()).contains("spring_ai_annotated_tool");
        });
    }

    @Test
    void shouldExposeOnlySafeContextAccounting() {
        QaContextTrace trace = new QaContextTrace();
        trace.setTraceId("qa-trace-1");
        trace.setOriginalQuestion("must not be exposed");
        trace.setRewrittenQuestion("must not be exposed either");
        trace.setSummaryTokens(240);
        trace.setHistoryTokens(510);
        trace.setEvidenceTokens(1280);
        trace.setTotalPromptTokens(2300);
        trace.setSelectedSourceCount(3);
        trace.setDroppedSourceCount(4);
        trace.setRewriteApplied(true);
        when(contextTraceMapper.selectOne(any())).thenReturn(trace);

        ContextTraceVO result = service.getContextTrace("qa-trace-1");

        assertThat(result.getTraceId()).isEqualTo("qa-trace-1");
        assertThat(result.getEvidenceTokens()).isEqualTo(1280);
        assertThat(result.getSelectedSourceCount()).isEqualTo(3);
        assertThat(result).hasNoNullFieldsOrPropertiesExcept("createdAt");
    }

    @Test
    void shouldPersistPlanAndToolWithoutRawToolOutput() {
        stubPersistedRun();
        service.start(record());
        service.recordContextPlan("qa-trace-1", new AgentRunService.ContextPlan(
                1, "glm-5.1", List.of("knowledge_search")));
        service.recordToolExecution("qa-trace-1", new AgentRunService.ToolExecution(
                "knowledge_search", "SUCCESS", 18, "tool-trace-2", "native_function_calling",
                Map.of("evidence_count", 1)));

        ArgumentCaptor<AgentStep> stepCaptor = ArgumentCaptor.forClass(AgentStep.class);
        verify(stepMapper, org.mockito.Mockito.times(3)).insert(stepCaptor.capture());
        assertThat(stepCaptor.getAllValues()).anySatisfy(step -> {
            assertThat(step.getStepType()).isEqualTo("PLAN");
            assertThat(step.getDetailJson()).contains("knowledge_search");
        });
        assertThat(stepCaptor.getAllValues()).anySatisfy(step -> {
            assertThat(step.getStepType()).isEqualTo("TOOL_CALL");
            assertThat(step.getDetailJson()).contains("evidence_count");
            assertThat(step.getDetailJson()).doesNotContain("grounded_answer");
        });
    }

    @Test
    void shouldPersistFinalProviderUsageSnapshot() {
        when(runMapper.selectOne(any())).thenReturn(persistedRun);
        when(stepMapper.selectCount(any())).thenReturn(1L);

        service.recordContextPlan("qa-trace-1", new AgentRunService.ContextPlan(
                1, "glm-5.1", List.of("knowledge_search"), 15));

        QaAnswerVO answer = new QaAnswerVO();
        answer.setModelName("glm-5.1");
        answer.setLatencyMs(80);
        answer.setContextStats(Map.of(
                "selected_source_count", 0,
                "provider_usage", Map.of(
                        "reported", true,
                        "input_tokens", 35,
                        "output_tokens", 7,
                        "cached_input_tokens", 6,
                        "total_tokens", 42,
                        "model_round_count", 3)));

        service.finishTool(record(), answer);

        assertThat(persistedRun.getUsageReported()).isTrue();
        assertThat(persistedRun.getInputTokens()).isEqualTo(35L);
        assertThat(persistedRun.getOutputTokens()).isEqualTo(7L);
        assertThat(persistedRun.getCachedInputTokens()).isEqualTo(6L);
        assertThat(persistedRun.getTotalTokens()).isEqualTo(42L);
        assertThat(persistedRun.getModelCallCount()).isEqualTo(3);
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
