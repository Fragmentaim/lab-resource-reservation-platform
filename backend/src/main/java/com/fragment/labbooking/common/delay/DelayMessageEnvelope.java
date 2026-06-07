package com.fragment.labbooking.common.delay;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DelayMessageEnvelope {

    private String eventId;
    private String eventType;
    private String businessKey;
    private LocalDateTime deliverAt;
    private String payload;
}
