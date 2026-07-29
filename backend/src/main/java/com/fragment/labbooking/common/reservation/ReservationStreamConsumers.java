package com.fragment.labbooking.common.reservation;

import com.fragment.labbooking.common.outbox.MessageOutboxEnvelope;
import com.fragment.labbooking.service.reservation.ReservationConfirmationService;
import com.fragment.labbooking.service.reservation.ReservationResultService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

@Configuration
public class ReservationStreamConsumers {

    @Bean
    Consumer<ReservationCreateCommand> reservationCommandConsumer(
            ReservationConfirmationService confirmationService) {
        return confirmationService::confirm;
    }

    @Bean
    Consumer<Message<MessageOutboxEnvelope>> reservationResultConsumer(
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            ReservationResultService resultService) {
        return message -> {
            MessageOutboxEnvelope envelope = message.getPayload();
            try {
                ReservationResultEvent event =
                        objectMapper.readValue(envelope.getPayload(), ReservationResultEvent.class);
                resultService.handle(envelope.getEventId(), event);
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw new IllegalArgumentException("Invalid reservation result payload", exception);
            }
        };
    }
}
