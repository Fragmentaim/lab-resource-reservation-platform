package com.fragment.labbooking.common.delay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.common.outbox.MessageOutboxEnvelope;
import com.fragment.labbooking.common.outbox.MessageOutboxProperties;
import com.fragment.labbooking.common.reminder.ReservationReminderDeliveryService;
import com.fragment.labbooking.common.reservation.ReservationAutoCancelService;
import com.fragment.labbooking.service.ReservationRequestService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;

@Component
@Slf4j
public class DelayMessageMqConsumer {

    private static final String TIMEOUT_FAIL_REASON = "热门预约请求处理超时，请重新提交";

    private final ObjectMapper objectMapper;
    private final ReservationReminderDeliveryService reminderDeliveryService;
    private final ReservationRequestService reservationRequestService;
    private final ReservationAutoCancelService reservationAutoCancelService;
    private final MessageOutboxProperties properties;

    private volatile DefaultMQPushConsumer consumer;

    public DelayMessageMqConsumer(ObjectMapper objectMapper,
                                  ReservationReminderDeliveryService reminderDeliveryService,
                                  ReservationRequestService reservationRequestService,
                                  ReservationAutoCancelService reservationAutoCancelService,
                                  MessageOutboxProperties properties) {
        this.objectMapper = objectMapper;
        this.reminderDeliveryService = reminderDeliveryService;
        this.reservationRequestService = reservationRequestService;
        this.reservationAutoCancelService = reservationAutoCancelService;
        this.properties = properties;
    }

    @PostConstruct
    public void start() {
        if (!properties.isEnabled()) {
            return;
        }
        if (!StringUtils.hasText(properties.getNameServer())) {
            log.warn("Delay message MQ is enabled but NameServer address is empty, consumer will not start.");
            return;
        }

        try {
            DefaultMQPushConsumer mqConsumer = new DefaultMQPushConsumer(properties.getDelayConsumerGroup());
            mqConsumer.setNamesrvAddr(properties.getNameServer());
            if (properties.getMaxReconsumeTimes() >= 0) {
                mqConsumer.setMaxReconsumeTimes(properties.getMaxReconsumeTimes());
            }
            mqConsumer.subscribe(properties.getDelayTopic(), "*");
            mqConsumer.registerMessageListener((List<MessageExt> messages, ConsumeConcurrentlyContext context) ->
                    consumeMessages(messages));
            mqConsumer.start();
            consumer = mqConsumer;
            log.info("Delay message MQ consumer started. nameServer={}, topic={}, consumerGroup={}",
                    properties.getNameServer(), properties.getDelayTopic(), properties.getDelayConsumerGroup());
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
                MessageOutboxEnvelope envelope = objectMapper.readValue(message.getBody(), MessageOutboxEnvelope.class);
                dispatch(envelope);
            } catch (Exception exception) {
                log.error("Failed to consume delay message, requesting re-consume later.", exception);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    private void dispatch(MessageOutboxEnvelope envelope) throws Exception {
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
