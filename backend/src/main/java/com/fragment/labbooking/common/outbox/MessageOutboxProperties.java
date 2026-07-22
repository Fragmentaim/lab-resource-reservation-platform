package com.fragment.labbooking.common.outbox;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.message-outbox")
public class MessageOutboxProperties {

    private boolean enabled = false;
    private String nameServer = "127.0.0.1:9876";
    private String producerGroup = "lab-booking-message-outbox-producer-group";
    private int maxReconsumeTimes = -1;
    private String delayTopic = "reservation-delay";
    private String delayConsumerGroup = "lab-booking-delay-consumer-group";
    private int lockSeconds = 60;
    private Outbox outbox = new Outbox();

    @Data
    public static class Outbox {
        private long relayDelayMillis = 1000L;
        private int batchSize = 20;
        private int maxRetryCount = 10;
        private long initialRetryDelayMillis = 1000L;
        private long maxRetryDelayMillis = 300000L;
    }
}
