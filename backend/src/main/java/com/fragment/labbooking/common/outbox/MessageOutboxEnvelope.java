package com.fragment.labbooking.common.outbox;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MessageOutboxEnvelope {

    private String eventId;
    private String eventType;
    private String aggregateType;
    private String aggregateId;
    private String businessKey;
    private LocalDateTime availableAt;
    private String payload;
}
