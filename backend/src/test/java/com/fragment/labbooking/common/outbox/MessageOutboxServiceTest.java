package com.fragment.labbooking.common.outbox;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.entity.MessageOutbox;
import com.fragment.labbooking.mapper.MessageOutboxMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MessageOutboxServiceTest {

    @BeforeEach
    void setUp() {
        if (TableInfoHelper.getTableInfo(MessageOutbox.class) != null) {
            return;
        }
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        assistant.setCurrentNamespace(MessageOutbox.class.getName());
        TableInfoHelper.initTableInfo(assistant, MessageOutbox.class);
    }

    @Test
    void markRetryFailureShouldScheduleNextAttemptWithExponentialBackoff() {
        MessageOutboxMapper mapper = mock(MessageOutboxMapper.class);
        MessageOutboxProperties properties = properties(10, 1000L, 300000L);
        MessageOutboxService service = new MessageOutboxService(mapper, new ObjectMapper(), properties);
        MessageOutbox outbox = sendingOutbox(0);
        LocalDateTime before = LocalDateTime.now();

        service.markRetryFailure(outbox, "broker unavailable");

        LambdaUpdateWrapper<MessageOutbox> update = capturedUpdate(mapper);
        Map<String, Object> values = update.getParamNameValuePairs();
        assertThat(values).containsValue(MessageOutboxService.STATUS_PENDING).containsValue(1);
        assertThat(values.values().stream()
                .filter(LocalDateTime.class::isInstance)
                .map(LocalDateTime.class::cast)
                .anyMatch(value -> value.isAfter(before)))
                .isTrue();
    }

    @Test
    void markRetryFailureShouldMoveMessageToFailedAfterRetryLimit() {
        MessageOutboxMapper mapper = mock(MessageOutboxMapper.class);
        MessageOutboxProperties properties = properties(3, 1000L, 300000L);
        MessageOutboxService service = new MessageOutboxService(mapper, new ObjectMapper(), properties);

        service.markRetryFailure(sendingOutbox(2), "invalid topic");

        Map<String, Object> values = capturedUpdate(mapper).getParamNameValuePairs();
        assertThat(values)
                .containsValue(MessageOutboxService.STATUS_FAILED)
                .containsValue(3)
                .doesNotContainValue(MessageOutboxService.STATUS_PENDING);
    }

    @Test
    void calculateRetryDelayMillisShouldDoubleAndStopAtConfiguredMaximum() {
        MessageOutboxService service = new MessageOutboxService(
                mock(MessageOutboxMapper.class),
                new ObjectMapper(),
                properties(10, 1000L, 5000L)
        );

        assertThat(service.calculateRetryDelayMillis(1)).isEqualTo(1000L);
        assertThat(service.calculateRetryDelayMillis(2)).isEqualTo(2000L);
        assertThat(service.calculateRetryDelayMillis(3)).isEqualTo(4000L);
        assertThat(service.calculateRetryDelayMillis(4)).isEqualTo(5000L);
        assertThat(service.calculateRetryDelayMillis(100)).isEqualTo(5000L);
    }

    private MessageOutboxProperties properties(int maxRetryCount,
                                               long initialRetryDelayMillis,
                                               long maxRetryDelayMillis) {
        MessageOutboxProperties properties = new MessageOutboxProperties();
        properties.getOutbox().setMaxRetryCount(maxRetryCount);
        properties.getOutbox().setInitialRetryDelayMillis(initialRetryDelayMillis);
        properties.getOutbox().setMaxRetryDelayMillis(maxRetryDelayMillis);
        return properties;
    }

    private MessageOutbox sendingOutbox(int retryCount) {
        MessageOutbox outbox = new MessageOutbox();
        outbox.setId(1L);
        outbox.setEventId("RESERVATION_CREATE:RESERVATION_REQUEST:REQ-1");
        outbox.setStatus(MessageOutboxService.STATUS_SENDING);
        outbox.setRetryCount(retryCount);
        return outbox;
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<MessageOutbox> capturedUpdate(MessageOutboxMapper mapper) {
        ArgumentCaptor<LambdaUpdateWrapper<MessageOutbox>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        return captor.getValue();
    }
}
