package com.fragment.labbooking.common.reservation;

import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.common.redis.HotReservationRedisService;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReservationCommandPublisherTest {

    @Test
    void publisherShouldReturnLocalTransactionResult() {
        StreamBridge bridge = mock(StreamBridge.class);
        ReservationCommandPublisher publisher = new ReservationCommandPublisher(bridge);
        ReservationCreateCommand command = command();
        HotReservationRedisService.HotRequestState expected = state(command);
        when(bridge.send(eq(ReservationCommandPublisher.OUTPUT_BINDING),
                org.mockito.ArgumentMatchers.<Message<?>>any())).thenAnswer(invocation -> {
            Message<?> message = invocation.getArgument(1);
            Object argument = message.getHeaders().get("TRANSACTIONAL_ARGS");
            assertThat(argument).isInstanceOf(ReservationCommandPublisher.LocalTransactionContext.class);
            ((ReservationCommandPublisher.LocalTransactionContext) argument).commit(expected);
            return true;
        });

        assertThat(publisher.publish(command)).isSameAs(expected);
    }

    @Test
    void publisherShouldRejectWhenBinderDoesNotAcceptMessage() {
        StreamBridge bridge = mock(StreamBridge.class);
        ReservationCommandPublisher publisher = new ReservationCommandPublisher(bridge);
        when(bridge.send(eq(ReservationCommandPublisher.OUTPUT_BINDING),
                org.mockito.ArgumentMatchers.<Message<?>>any())).thenReturn(false);

        assertThatThrownBy(() -> publisher.publish(command()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(503));
    }

    private ReservationCreateCommand command() {
        return new ReservationCreateCommand(
                "33c8aa68-9fe0-4d30-afd5-6e62de86bd6c",
                7L, 1L, 10L, System.currentTimeMillis() + 300_000);
    }

    private HotReservationRedisService.HotRequestState state(ReservationCreateCommand command) {
        return new HotReservationRedisService.HotRequestState(
                command.requestId(), command.userId(), command.resourceId(), command.slotId(),
                "PRE_RESERVED", command.expiresAtEpochMillis(), null, null, null, null);
    }
}
