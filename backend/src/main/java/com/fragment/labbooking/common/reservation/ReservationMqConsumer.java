package com.fragment.labbooking.common.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.service.ReservationRequestService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;

@Component
@Slf4j
public class ReservationMqConsumer {

    private final ObjectMapper objectMapper;
    private final ReservationRequestService reservationRequestService;
    private final boolean enabled;
    private final String nameServer;
    private final String topic;
    private final String consumerGroup;
    private final int maxReconsumeTimes;

    private volatile DefaultMQPushConsumer consumer;

    public ReservationMqConsumer(ObjectMapper objectMapper,
                                 ReservationRequestService reservationRequestService,
                                 @Value("${app.reservation.async.enabled:true}") boolean enabled,
                                 @Value("${app.reservation.async.name-server:}") String nameServer,
                                 @Value("${app.reservation.async.topic:reservation-create}") String topic,
                                 @Value("${app.reservation.async.consumer-group:lab-booking-reservation-consumer-group}") String consumerGroup,
                                 @Value("${app.reservation.async.max-reconsume-times:-1}") int maxReconsumeTimes) {
        this.objectMapper = objectMapper;
        this.reservationRequestService = reservationRequestService;
        this.enabled = enabled;
        this.nameServer = nameServer;
        this.topic = topic;
        this.consumerGroup = consumerGroup;
        this.maxReconsumeTimes = maxReconsumeTimes;
    }

    @PostConstruct
    public void start() {
        if (!enabled) {
            return;
        }
        if (!StringUtils.hasText(nameServer)) {
            log.warn("Reservation async MQ is enabled but NameServer address is empty, consumer will not start.");
            return;
        }

        try {
            DefaultMQPushConsumer mqConsumer = new DefaultMQPushConsumer(consumerGroup);
            mqConsumer.setNamesrvAddr(nameServer);
            if (maxReconsumeTimes >= 0) {
                mqConsumer.setMaxReconsumeTimes(maxReconsumeTimes);
            }
            mqConsumer.subscribe(topic, ReservationMqPublisher.TAG);
            mqConsumer.registerMessageListener((List<MessageExt> messages, ConsumeConcurrentlyContext context) ->
                    consumeMessages(messages));
            mqConsumer.start();
            consumer = mqConsumer;
            log.info("Reservation async MQ consumer started. nameServer={}, topic={}, consumerGroup={}, maxReconsumeTimes={}",
                    nameServer, topic, consumerGroup, maxReconsumeTimes);
        } catch (Exception exception) {
            log.error("Failed to start reservation async MQ consumer.", exception);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }

    ConsumeConcurrentlyStatus consumeMessages(List<MessageExt> messages) {
        for (MessageExt message : messages) {
            try {
                ReservationCreateEvent event = objectMapper.readValue(message.getBody(), ReservationCreateEvent.class);
                reservationRequestService.processPendingHotRequest(event.getRequestNo());
            } catch (Exception exception) {
                log.error("Failed to consume reservation create event, requesting re-consume later.", exception);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
}
