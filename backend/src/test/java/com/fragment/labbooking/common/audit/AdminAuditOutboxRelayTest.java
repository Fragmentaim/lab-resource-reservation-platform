package com.fragment.labbooking.common.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.entity.AdminAuditOutbox;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAuditOutboxRelayTest {

    @Test
    void relayPendingMessagesShouldMarkSentAfterSuccessfulPublish() throws Exception {
        AdminAuditOutboxService outboxService = mock(AdminAuditOutboxService.class);
        AdminAuditMqPublisher publisher = mock(AdminAuditMqPublisher.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AdminAuditOutboxRelay relay = new AdminAuditOutboxRelay(outboxService, publisher, objectMapper, true, true, 20);

        AdminAuditLogEvent event = new AdminAuditLogEvent();
        event.setEventId("AUDIT-200");
        event.setCreatedAt(LocalDateTime.now());

        AdminAuditOutbox outbox = new AdminAuditOutbox();
        outbox.setEventId("AUDIT-200");
        outbox.setTopic("admin-audit-log");
        outbox.setTag("admin-audit");
        outbox.setMessageKey("AUDIT-200");
        outbox.setPayload(objectMapper.writeValueAsString(event));

        when(outboxService.findPendingBatch(20)).thenReturn(List.of(outbox));
        when(publisher.publish(event, "admin-audit-log", "admin-audit", "AUDIT-200")).thenReturn(true);

        relay.relayPendingMessages();

        verify(outboxService).markSent(outbox);
        verify(outboxService, never()).markRetryFailure(outbox, "publish returned false");
    }

    @Test
    void relayPendingMessagesShouldMarkRetryFailureWhenPayloadCannotBeParsed() {
        AdminAuditOutboxService outboxService = mock(AdminAuditOutboxService.class);
        AdminAuditMqPublisher publisher = mock(AdminAuditMqPublisher.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AdminAuditOutboxRelay relay = new AdminAuditOutboxRelay(outboxService, publisher, objectMapper, true, true, 20);

        AdminAuditOutbox outbox = new AdminAuditOutbox();
        outbox.setEventId("AUDIT-201");
        outbox.setPayload("bad-json");

        when(outboxService.findPendingBatch(20)).thenReturn(List.of(outbox));

        relay.relayPendingMessages();

        verify(outboxService).markRetryFailure(org.mockito.Mockito.eq(outbox), org.mockito.ArgumentMatchers.contains("Unrecognized token"));
        verify(publisher, never()).publish(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
