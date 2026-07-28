package com.fragment.labbooking.common.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.common.mq.AbstractRocketMqConsumer;
import com.fragment.labbooking.common.mq.OutboxMessageDecoder;
import com.fragment.labbooking.service.ReservationRequestService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@Slf4j
public class ReservationMqConsumer extends AbstractRocketMqConsumer {

    private final ObjectMapper objectMapper;
    private final ReservationRequestService reservationRequestService;
    public ReservationMqConsumer(ObjectMapper objectMapper,
                                 ReservationRequestService reservationRequestService,
                                 @Value("${app.reservation.async.enabled:true}") boolean enabled,
                                 @Value("${app.reservation.async.name-server:}") String nameServer,
                                 @Value("${app.reservation.async.topic:reservation-create}") String topic,
                                 @Value("${app.reservation.async.consumer-group:lab-booking-reservation-consumer-group}") String consumerGroup,
                                 @Value("${app.reservation.async.max-reconsume-times:-1}") int maxReconsumeTimes) {
        super("Reservation", enabled, nameServer, topic, ReservationMqPublisher.TAG,
                consumerGroup, maxReconsumeTimes);
        this.objectMapper = objectMapper;
        this.reservationRequestService = reservationRequestService;
    }

    @Override
    protected ConsumeConcurrentlyStatus consumeMessages(List<MessageExt> messages) {
        for (MessageExt message : messages) {
            try {
                ReservationCreateEvent event = parseEvent(message);
                reservationRequestService.processPendingHotRequest(event.getRequestNo());
            } catch (Exception exception) {
                log.error("Failed to consume reservation create event, requesting re-consume later.", exception);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    private ReservationCreateEvent parseEvent(MessageExt message) throws Exception {
        return OutboxMessageDecoder.payload(objectMapper, message, ReservationCreateEvent.class);
    }
}
