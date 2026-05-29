package com.fragment.labbooking.common.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Component
@Slf4j
public class ReservationMqPublisher {

    public static final String TAG = "reservation-create";

    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String nameServer;
    private final String topic;
    private final String producerGroup;

    private volatile DefaultMQProducer producer;
    private volatile boolean started;

    public ReservationMqPublisher(ObjectMapper objectMapper,
                                  @Value("${app.reservation.async.enabled:true}") boolean enabled,
                                  @Value("${app.reservation.async.name-server:}") String nameServer,
                                  @Value("${app.reservation.async.topic:reservation-create}") String topic,
                                  @Value("${app.reservation.async.producer-group:lab-booking-reservation-producer-group}") String producerGroup) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.nameServer = nameServer;
        this.topic = topic;
        this.producerGroup = producerGroup;
    }

    @PostConstruct
    public void warmUp() {
        if (!enabled) {
            return;
        }
        ensureStarted();
    }

    public boolean publish(ReservationCreateEvent event, String messageKey) {
        if (!enabled || event == null || !ensureStarted()) {
            return false;
        }

        try {
            Message message = new Message(
                    topic,
                    TAG,
                    messageKey,
                    objectMapper.writeValueAsBytes(event)
            );
            SendResult sendResult = producer.send(message);
            log.info("Published reservation create event via MQ. topic={}, requestNo={}, msgId={}",
                    topic, event.getRequestNo(), sendResult.getMsgId());
            return true;
        } catch (Exception exception) {
            log.warn("Failed to publish reservation create event via MQ, relay will retry later.", exception);
            return false;
        }
    }

    @PreDestroy
    public void shutdown() {
        if (producer != null) {
            producer.shutdown();
        }
    }

    private synchronized boolean ensureStarted() {
        if (started) {
            return true;
        }
        if (!StringUtils.hasText(nameServer)) {
            log.warn("Reservation async MQ is enabled but NameServer address is empty.");
            return false;
        }

        try {
            DefaultMQProducer mqProducer = new DefaultMQProducer(producerGroup);
            mqProducer.setNamesrvAddr(nameServer);
            mqProducer.start();
            producer = mqProducer;
            started = true;
            log.info("Reservation async MQ producer started. nameServer={}, topic={}", nameServer, topic);
            return true;
        } catch (Exception exception) {
            log.warn("Failed to start reservation async MQ producer, relay will retry later.", exception);
            return false;
        }
    }
}
