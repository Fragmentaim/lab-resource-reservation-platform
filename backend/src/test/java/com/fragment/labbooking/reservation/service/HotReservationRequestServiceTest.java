package com.fragment.labbooking.reservation.service;

import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.reservation.redis.HotReservationRedisService;
import com.fragment.labbooking.reservation.messaging.ReservationCommandPublisher;
import com.fragment.labbooking.reservation.persistence.ReservationMapper;
import com.fragment.labbooking.reservation.persistence.ReservationRequestMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HotReservationRequestServiceTest {

    private static final String REQUEST_ID = "33c8aa68-9fe0-4d30-afd5-6e62de86bd6c";

    @Mock ReservationCommandPublisher publisher;
    @Mock HotReservationRedisService hotRedis;
    @Mock ReservationRequestMapper requestMapper;
    @Mock ReservationMapper reservationMapper;

    private HotReservationRequestService service;

    @BeforeEach
    void setUp() {
        service = new HotReservationRequestService(
                publisher, hotRedis, requestMapper, reservationMapper);
    }

    @Test
    void acceptedTransactionShouldReturnPending() {
        when(publisher.publish(argThat(command -> REQUEST_ID.equals(command.requestId()))))
                .thenReturn(state("PRE_RESERVED", null));

        var result = service.accept(REQUEST_ID, 7L, 1L, 10L);

        assertThat(result.getStatus()).isEqualTo("PENDING");
        verify(publisher).publish(argThat(command ->
                command.userId().equals(7L)
                        && command.resourceId().equals(1L)
                        && command.slotId().equals(10L)));
    }

    @Test
    void idempotentTerminalReplayShouldReturnOriginalResult() {
        when(publisher.publish(argThat(command -> REQUEST_ID.equals(command.requestId()))))
                .thenReturn(state("CONFIRMED", 91L));

        var result = service.accept(REQUEST_ID, 7L, 1L, 10L);

        assertThat(result.getStatus()).isEqualTo("CONFIRMED");
        assertThat(result.getReservationId()).isEqualTo(91L);
    }

    @Test
    void unknownTransactionShouldTellClientToRetrySameKey() {
        when(publisher.publish(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new BusinessException(503,
                        "预约事务状态正在由消息队列确认，请使用相同 Idempotency-Key 重试"));

        assertThatThrownBy(() -> service.accept(REQUEST_ID, 7L, 1L, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(503))
                .hasMessageContaining("相同 Idempotency-Key");
    }

    private HotReservationRedisService.HotRequestState state(String status, Long reservationId) {
        return new HotReservationRedisService.HotRequestState(
                REQUEST_ID, 7L, 1L, 10L, status,
                reservationId, null, null, null);
    }
}
