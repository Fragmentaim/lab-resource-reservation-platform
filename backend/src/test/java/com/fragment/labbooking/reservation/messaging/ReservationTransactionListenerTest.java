package com.fragment.labbooking.reservation.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.reservation.redis.HotReservationRedisService;
import com.fragment.labbooking.reservation.redis.RedisDecisionUnknownException;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationTransactionListenerTest {

    private static final String REQUEST_ID = "33c8aa68-9fe0-4d30-afd5-6e62de86bd6c";

    @Mock HotReservationRedisService hotRedis;

    private ObjectMapper objectMapper;
    private ReservationTransactionListener listener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        listener = new ReservationTransactionListener(hotRedis, objectMapper);
    }

    @Test
    void acceptedRedisTransactionShouldCommitHalfMessage() {
        ReservationCreateCommand command = command();
        var state = state(command);
        var context = new ReservationCommandPublisher.LocalTransactionContext(command);
        when(hotRedis.accept(eq(REQUEST_ID), eq(7L), eq(1L), eq(10L)))
                .thenReturn(state);

        LocalTransactionState decision = listener.executeLocalTransaction(message(command), context);

        assertThat(decision).isEqualTo(LocalTransactionState.COMMIT_MESSAGE);
        assertThat(context.result()).isSameAs(state);
    }

    @Test
    void deterministicBusinessRejectionShouldRollbackHalfMessage() {
        ReservationCreateCommand command = command();
        var context = new ReservationCommandPublisher.LocalTransactionContext(command);
        when(hotRedis.accept(eq(REQUEST_ID), eq(7L), eq(1L), eq(10L)))
                .thenThrow(new BusinessException(409, "热门时段余量不足"));

        LocalTransactionState decision = listener.executeLocalTransaction(message(command), context);

        assertThat(decision).isEqualTo(LocalTransactionState.ROLLBACK_MESSAGE);
        assertThatThrownBy(context::result)
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(409));
    }

    @Test
    void unavailableRedisShouldLeaveTransactionForBrokerCheck() {
        ReservationCreateCommand command = command();
        var context = new ReservationCommandPublisher.LocalTransactionContext(command);
        when(hotRedis.accept(eq(REQUEST_ID), eq(7L), eq(1L), eq(10L)))
                .thenThrow(new RedisDecisionUnknownException(new IllegalStateException("Redis unavailable")));

        assertThat(listener.executeLocalTransaction(message(command), context))
                .isEqualTo(LocalTransactionState.UNKNOW);
        assertThatThrownBy(context::result)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("相同 Idempotency-Key");
    }

    @Test
    void deterministicUnavailableStateShouldRollbackInsteadOfWaitingForBrokerCheck() {
        ReservationCreateCommand command = command();
        var context = new ReservationCommandPublisher.LocalTransactionContext(command);
        when(hotRedis.accept(eq(REQUEST_ID), eq(7L), eq(1L), eq(10L)))
                .thenThrow(new BusinessException(503, "热门预约通道暂时不可用，请稍后重试"));

        assertThat(listener.executeLocalTransaction(message(command), context))
                .isEqualTo(LocalTransactionState.ROLLBACK_MESSAGE);
        assertThatThrownBy(context::result)
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode()).isEqualTo(503));
    }

    @Test
    void brokerCheckShouldCommitOnlyMatchingRedisRequest() {
        ReservationCreateCommand command = command();
        MessageExt message = checkMessage(command);
        when(hotRedis.getRequest(REQUEST_ID)).thenReturn(state(command));

        assertThat(listener.checkLocalTransaction(message))
                .isEqualTo(LocalTransactionState.COMMIT_MESSAGE);

        when(hotRedis.getRequest(REQUEST_ID)).thenReturn(null);
        assertThat(listener.checkLocalTransaction(message))
                .isEqualTo(LocalTransactionState.ROLLBACK_MESSAGE);
    }

    @Test
    void brokerCheckShouldStayUnknownWhileRedisCannotBeRead() {
        when(hotRedis.getRequest(REQUEST_ID)).thenThrow(new IllegalStateException("network timeout"));

        assertThat(listener.checkLocalTransaction(checkMessage(command())))
                .isEqualTo(LocalTransactionState.UNKNOW);
    }

    @Test
    void brokerCheckShouldRollbackWhenRequestIdParametersDoNotMatch() {
        ReservationCreateCommand command = command();
        when(hotRedis.getRequest(REQUEST_ID)).thenReturn(new HotReservationRedisService.HotRequestState(
                REQUEST_ID, 7L, 99L, 10L, "PRE_RESERVED",
                null, null, null, null));

        assertThat(listener.checkLocalTransaction(checkMessage(command)))
                .isEqualTo(LocalTransactionState.ROLLBACK_MESSAGE);
    }

    private ReservationCreateCommand command() {
        return new ReservationCreateCommand(
                REQUEST_ID, 7L, 1L, 10L);
    }

    private HotReservationRedisService.HotRequestState state(ReservationCreateCommand command) {
        return new HotReservationRedisService.HotRequestState(
                command.requestId(), command.userId(), command.resourceId(), command.slotId(),
                "PRE_RESERVED", null, null, null, null);
    }

    private Message message(ReservationCreateCommand command) {
        try {
            return new Message("reservation-command", objectMapper.writeValueAsBytes(command));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private MessageExt checkMessage(ReservationCreateCommand command) {
        MessageExt message = new MessageExt();
        message.setKeys(command.requestId());
        message.setBody(message(command).getBody());
        return message;
    }
}
