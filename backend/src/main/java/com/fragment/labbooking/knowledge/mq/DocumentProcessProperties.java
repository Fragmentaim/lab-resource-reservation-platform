package com.fragment.labbooking.knowledge.mq;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.knowledge.document-processing")
public class DocumentProcessProperties {

    private boolean enabled = false;
    private String topic = "knowledge-document-process";
    private String tag = "KNOWLEDGE_DOCUMENT_PROCESS";
    private String eventType = "KNOWLEDGE_DOCUMENT_PROCESS";
    private String consumerGroup = "lab-booking-knowledge-document-consumer-group";
    private int maxReconsumeTimes = -1;
    private int maxRetryAttempts = 3;
}
