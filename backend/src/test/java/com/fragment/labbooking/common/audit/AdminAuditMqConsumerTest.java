package com.fragment.labbooking.common.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.common.outbox.MessageOutboxEnvelope;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AdminAuditMqConsumerTest {

    @Test
    void consumeMessagesShouldTreatDuplicateAuditInsertAsSuccess() throws Exception {
        AdminAuditLogWriter logWriter = mock(AdminAuditLogWriter.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AdminAuditMqConsumer consumer = new AdminAuditMqConsumer(
                objectMapper,
                logWriter,
                false,
                "",
                "admin-audit-log",
                "audit-group",
                -1,
                false,
                "",
                ""
        );
        AdminAuditLogEvent event = new AdminAuditLogEvent();
        event.setEventId("AUDIT-300");
        event.setCreatedAt(LocalDateTime.now());
        MessageOutboxEnvelope envelope = new MessageOutboxEnvelope();
        envelope.setEventId("ADMIN_AUDIT_LOG:ADMIN_AUDIT:AUDIT-300");
        envelope.setEventType("ADMIN_AUDIT_LOG");
        envelope.setPayload(objectMapper.writeValueAsString(event));
        MessageExt message = new MessageExt();
        message.setBody(objectMapper.writeValueAsBytes(envelope));

        doThrow(new DuplicateKeyException("duplicate")).when(logWriter).write(any());

        ConsumeConcurrentlyStatus status = consumer.consumeMessages(List.of(message));

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        verify(logWriter).write(any());
    }

    @Test
    void consumeMessagesShouldWriteAuditLogFromOutboxEnvelope() throws Exception {
        AdminAuditLogWriter logWriter = mock(AdminAuditLogWriter.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AdminAuditMqConsumer consumer = new AdminAuditMqConsumer(
                objectMapper,
                logWriter,
                false,
                "",
                "admin-audit-log",
                "audit-group",
                -1,
                false,
                "",
                ""
        );
        AdminAuditLogEvent event = new AdminAuditLogEvent();
        event.setEventId("AUDIT-301");
        event.setCreatedAt(LocalDateTime.now());
        MessageOutboxEnvelope envelope = new MessageOutboxEnvelope();
        envelope.setEventId("ADMIN_AUDIT_LOG:ADMIN_AUDIT:AUDIT-301");
        envelope.setEventType("ADMIN_AUDIT_LOG");
        envelope.setPayload(objectMapper.writeValueAsString(event));
        MessageExt message = new MessageExt();
        message.setBody(objectMapper.writeValueAsBytes(envelope));

        ConsumeConcurrentlyStatus status = consumer.consumeMessages(List.of(message));

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        verify(logWriter).write(any());
    }

    @Test
    void consumeMessagesShouldRequestRetryForBrokenPayload() {
        AdminAuditLogWriter logWriter = mock(AdminAuditLogWriter.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AdminAuditMqConsumer consumer = new AdminAuditMqConsumer(
                objectMapper,
                logWriter,
                false,
                "",
                "admin-audit-log",
                "audit-group",
                -1,
                false,
                "",
                ""
        );
        MessageExt message = new MessageExt();
        message.setBody("not-json".getBytes(StandardCharsets.UTF_8));

        ConsumeConcurrentlyStatus status = consumer.consumeMessages(List.of(message));

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
    }
}
