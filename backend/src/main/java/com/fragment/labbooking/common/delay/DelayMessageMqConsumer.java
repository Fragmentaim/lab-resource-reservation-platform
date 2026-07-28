package com.fragment.labbooking.common.delay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.common.outbox.MessageOutboxEnvelope;
import com.fragment.labbooking.common.outbox.MessageOutboxProperties;
import com.fragment.labbooking.common.mq.AbstractRocketMqConsumer;
import com.fragment.labbooking.common.mq.OutboxMessageDecoder;
import com.fragment.labbooking.common.reminder.ReservationReminderDeliveryService;
import com.fragment.labbooking.common.reservation.ReservationAutoCancelService;
import com.fragment.labbooking.service.ReservationRequestService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@Slf4j
public class DelayMessageMqConsumer extends AbstractRocketMqConsumer {

    private static final String TIMEOUT_FAIL_REASON = "热门预约请求处理超时，请重新提交";

    private final ObjectMapper objectMapper;
    private final ReservationReminderDeliveryService reminderDeliveryService;
    private final ReservationRequestService reservationRequestService;
    private final ReservationAutoCancelService reservationAutoCancelService;

    public DelayMessageMqConsumer(ObjectMapper objectMapper,
                                  ReservationReminderDeliveryService reminderDeliveryService,
                                  ReservationRequestService reservationRequestService,
                                  ReservationAutoCancelService reservationAutoCancelService,
                                  MessageOutboxProperties properties) {
        super("Delay", properties.isEnabled(), properties.getNameServer(), properties.getDelayTopic(), "*",
                properties.getDelayConsumerGroup(), properties.getMaxReconsumeTimes());
        this.objectMapper = objectMapper;
        this.reminderDeliveryService = reminderDeliveryService;
        this.reservationRequestService = reservationRequestService;
        this.reservationAutoCancelService = reservationAutoCancelService;
    }

    @Override
    protected ConsumeConcurrentlyStatus consumeMessages(List<MessageExt> messages) {
        for (MessageExt message : messages) {
            try {
                MessageOutboxEnvelope envelope = OutboxMessageDecoder.envelope(objectMapper, message);
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
            return;
        }
        throw new IllegalArgumentException("Unsupported delay event type: " + envelope.getEventType());
    }
}
