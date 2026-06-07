package com.fragment.labbooking.common.delay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.common.reminder.ReservationReminderDeliveryService;
import com.fragment.labbooking.common.reservation.ReservationAutoCancelService;
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
import java.time.LocalDateTime;
import java.util.List;

@Component
@Slf4j
public class DelayMessageMqConsumer {

    private static final String TIMEOUT_FAIL_REASON = "热门预约请求处理超时，请重新提交";

    private final ObjectMapper objectMapper;
    private final DelayMessageMqPublisher delayMessageMqPublisher;
    private final RocketMqDelayLevelResolver delayLevelResolver;
    private final ReservationReminderDeliveryService reminderDeliveryService;
    private final ReservationRequestService reservationRequestService;
    private final ReservationAutoCancelService reservationAutoCancelService;
    private final boolean enabled;
    private final String nameServer;
    private final String topic;
    private final String consumerGroup;
    private final int maxReconsumeTimes;

    private volatile DefaultMQPushConsumer consumer;

    public DelayMessageMqConsumer(ObjectMapper objectMapper,
                                  DelayMessageMqPublisher delayMessageMqPublisher,
                                  RocketMqDelayLevelResolver delayLevelResolver,
                                  ReservationReminderDeliveryService reminderDeliveryService,
                                  ReservationRequestService reservationRequestService,
                                  ReservationAutoCancelService reservationAutoCancelService,
                                  @Value("${app.delay-message.enabled:false}") boolean enabled,
                                  @Value("${app.delay-message.name-server:}") String nameServer,
                                  @Value("${app.delay-message.topic:reservation-delay}") String topic,
                                  @Value("${app.delay-message.consumer-group:lab-booking-delay-consumer-group}") String consumerGroup,
                                  @Value("${app.delay-message.max-reconsume-times:-1}") int maxReconsumeTimes) {
        this.objectMapper = objectMapper;
        this.delayMessageMqPublisher = delayMessageMqPublisher;
        this.delayLevelResolver = delayLevelResolver;
        this.reminderDeliveryService = reminderDeliveryService;
        this.reservationRequestService = reservationRequestService;
        this.reservationAutoCancelService = reservationAutoCancelService;
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
            log.warn("Delay message MQ is enabled but NameServer address is empty, consumer will not start.");
            return;
        }

        try {
            DefaultMQPushConsumer mqConsumer = new DefaultMQPushConsumer(consumerGroup);
            mqConsumer.setNamesrvAddr(nameServer);
            if (maxReconsumeTimes >= 0) {
                mqConsumer.setMaxReconsumeTimes(maxReconsumeTimes);
            }
            mqConsumer.subscribe(topic, "*");
            mqConsumer.registerMessageListener((List<MessageExt> messages, ConsumeConcurrentlyContext context) ->
                    consumeMessages(messages));
            mqConsumer.start();
            consumer = mqConsumer;
            log.info("Delay message MQ consumer started. nameServer={}, topic={}, consumerGroup={}",
                    nameServer, topic, consumerGroup);
        } catch (Exception exception) {
            log.error("Failed to start delay message MQ consumer.", exception);
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
                DelayMessageEnvelope envelope = objectMapper.readValue(message.getBody(), DelayMessageEnvelope.class);
                if (shouldRequeue(envelope)) {
                    if (!requeue(envelope)) {
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                    continue;
                }
                dispatch(envelope);
            } catch (Exception exception) {
                log.error("Failed to consume delay message, requesting re-consume later.", exception);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    private boolean shouldRequeue(DelayMessageEnvelope envelope) {
        return envelope.getDeliverAt() != null && LocalDateTime.now().isBefore(envelope.getDeliverAt());
    }

    private boolean requeue(DelayMessageEnvelope envelope) {
        int delayLevel = delayLevelResolver.resolveDelayLevel(envelope.getDeliverAt());
        return delayMessageMqPublisher.publish(
                envelope,
                topic,
                DelayMessageTags.tagFor(envelope.getEventType()),
                envelope.getBusinessKey(),
                delayLevel
        );
    }

    private void dispatch(DelayMessageEnvelope envelope) throws Exception {
        if (DelayMessageEventTypes.RESERVATION_REMINDER.equals(envelope.getEventType())) {
            ReservationReminderDelayPayload payload = objectMapper.readValue(
                    envelope.getPayload(),
                    ReservationReminderDelayPayload.class
            );
            reminderDeliveryService.deliver(payload.getReminderTaskId());
            return;
        }

        if (DelayMessageEventTypes.RESERVATION_REQUEST_TIMEOUT.equals(envelope.getEventType())) {
            ReservationRequestTimeoutDelayPayload payload = objectMapper.readValue(
                    envelope.getPayload(),
                    ReservationRequestTimeoutDelayPayload.class
            );
            reservationRequestService.markTimedOutByRequestNo(payload.getRequestNo(), TIMEOUT_FAIL_REASON);
            return;
        }

        if (DelayMessageEventTypes.RESERVATION_AUTO_CANCEL.equals(envelope.getEventType())) {
            ReservationAutoCancelDelayPayload payload = objectMapper.readValue(
                    envelope.getPayload(),
                    ReservationAutoCancelDelayPayload.class
            );
            reservationAutoCancelService.autoCancel(payload.getReservationId());
        }
    }
}
