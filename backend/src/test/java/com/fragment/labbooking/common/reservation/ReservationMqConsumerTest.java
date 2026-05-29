package com.fragment.labbooking.common.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.service.ReservationRequestService;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ReservationMqConsumerTest {

    @Test
    void consumeMessagesShouldProcessRequestAndReturnSuccessForValidPayload() throws Exception {
        ReservationRequestService requestService = mock(ReservationRequestService.class);
        ReservationMqConsumer consumer = new ReservationMqConsumer(
                new ObjectMapper(),
                requestService,
                false,
                "",
                "reservation-create",
                "group",
                -1
        );

        ReservationCreateEvent event = new ReservationCreateEvent();
        event.setRequestNo("REQ-6001");
        MessageExt message = new MessageExt();
        message.setBody(new ObjectMapper().writeValueAsBytes(event));

        ConsumeConcurrentlyStatus status = consumer.consumeMessages(List.of(message));

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.CONSUME_SUCCESS);
        verify(requestService).processPendingHotRequest("REQ-6001");
    }

    @Test
    void consumeMessagesShouldRequestRetryWhenPayloadIsInvalid() {
        ReservationRequestService requestService = mock(ReservationRequestService.class);
        ReservationMqConsumer consumer = new ReservationMqConsumer(
                new ObjectMapper(),
                requestService,
                false,
                "",
                "reservation-create",
                "group",
                -1
        );

        MessageExt message = new MessageExt();
        message.setBody("not-json".getBytes(StandardCharsets.UTF_8));

        ConsumeConcurrentlyStatus status = consumer.consumeMessages(List.of(message));

        assertThat(status).isEqualTo(ConsumeConcurrentlyStatus.RECONSUME_LATER);
    }
}
