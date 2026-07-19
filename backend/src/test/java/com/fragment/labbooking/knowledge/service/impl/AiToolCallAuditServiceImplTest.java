package com.fragment.labbooking.knowledge.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fragment.labbooking.common.auth.LoginUser;
import com.fragment.labbooking.knowledge.entity.AiToolCallLog;
import com.fragment.labbooking.knowledge.mapper.AiToolCallLogMapper;
import com.fragment.labbooking.knowledge.vo.AiToolCallLogVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiToolCallAuditServiceImplTest {

    @Mock
    private AiToolCallLogMapper aiToolCallLogMapper;

    private AiToolCallAuditServiceImpl auditService;

    @BeforeEach
    void setUp() {
        auditService = new AiToolCallAuditServiceImpl();
        ReflectionTestUtils.setField(auditService, "aiToolCallLogMapper", aiToolCallLogMapper);
    }

    @Test
    void shouldPersistMinimalSuccessAuditFacts() {
        LoginUser actor = new LoginUser(7L, "tester", "测试用户", "USER", "13900000000");

        auditService.recordSuccess("trace-1", "reservation_context", actor, 7L,
                "SELF_READ", 12L, "subjectUserId=7");

        ArgumentCaptor<AiToolCallLog> captor = ArgumentCaptor.forClass(AiToolCallLog.class);
        verify(aiToolCallLogMapper).insert(captor.capture());
        AiToolCallLog logEntry = captor.getValue();
        assertThat(logEntry.getTraceId()).isEqualTo("trace-1");
        assertThat(logEntry.getActorUserId()).isEqualTo(7L);
        assertThat(logEntry.getActorRole()).isEqualTo("USER");
        assertThat(logEntry.getAccessScope()).isEqualTo("SELF_READ");
        assertThat(logEntry.getResult()).isEqualTo("SUCCESS");
        assertThat(logEntry.getParameterSummary()).isEqualTo("subjectUserId=7");
        assertThat(logEntry.getParameterSummary()).doesNotContain("13900000000");
    }

    @Test
    void shouldNotBreakToolFlowWhenAuditStorageFails() {
        when(aiToolCallLogMapper.insert(any(AiToolCallLog.class))).thenThrow(new RuntimeException("database unavailable"));

        assertThatCode(() -> auditService.recordFailure("trace-2", "reservation_context", null,
                7L, 8L, "subjectUserId=7", "forbidden"))
                .doesNotThrowAnyException();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldMapAuditPageToViewObjects() {
        AiToolCallLog logEntry = new AiToolCallLog();
        logEntry.setId(9L);
        logEntry.setTraceId("trace-9");
        logEntry.setToolName("reservation_context");
        logEntry.setResult("SUCCESS");
        Page<AiToolCallLog> source = new Page<>(1, 10, 1);
        source.setRecords(List.of(logEntry));
        when(aiToolCallLogMapper.selectPage(any(), any())).thenReturn(source);

        Page<AiToolCallLogVO> page = auditService.page(0, 500);

        assertThat(page.getCurrent()).isEqualTo(1L);
        ArgumentCaptor<Page<AiToolCallLog>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(aiToolCallLogMapper).selectPage(pageCaptor.capture(), any());
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1L);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(100L);
        assertThat(page.getRecords()).singleElement().satisfies(item -> {
            assertThat(item.getId()).isEqualTo(9L);
            assertThat(item.getTraceId()).isEqualTo("trace-9");
        });
    }
}
