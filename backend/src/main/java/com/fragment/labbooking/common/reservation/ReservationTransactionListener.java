package com.fragment.labbooking.common.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.common.redis.HotReservationRedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;

/**
 * Makes Redis pre-reservation the local transaction of the RocketMQ half
 * message. RocketMQ checks the Redis request record when the first decision is
 * unknown, so the application does not need a scheduled republisher.
 */
@Component("reservationTransactionListener")
@RequiredArgsConstructor
@Slf4j
public class ReservationTransactionListener implements TransactionListener {

    private final HotReservationRedisService hotRedis;
    private final ObjectMapper objectMapper;

    @Override
    public LocalTransactionState executeLocalTransaction(Message message, Object argument) {
        ReservationCommandPublisher.LocalTransactionContext context = localContext(argument);
        if (context == null) {
            log.error("Reservation transaction is missing its local context");
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
        try {
            ReservationCreateCommand command = context.command();
            HotReservationRedisService.HotRequestState state = hotRedis.accept(
                    command.requestId(), command.userId(), command.resourceId(), command.slotId(),
                    command.expiresAtEpochMillis());
            context.commit(state);
            return LocalTransactionState.COMMIT_MESSAGE;
        } catch (BusinessException exception) {
            if (exception.getCode() != null && exception.getCode() == 503) {
                log.warn("Redis decision is unknown for reservation transaction: {}", exception.getMessage());
                return LocalTransactionState.UNKNOW;
            }
            context.rollback(exception);
            return LocalTransactionState.ROLLBACK_MESSAGE;
        } catch (RuntimeException exception) {
            log.warn("Unable to execute reservation Redis transaction", exception);
            return LocalTransactionState.UNKNOW;
        }
    }

    @Override
    public LocalTransactionState checkLocalTransaction(MessageExt message) {
        String requestId = message.getKeys();
        if (!StringUtils.hasText(requestId)) {
            log.error("Reservation transaction message has no requestId key");
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
        try {
            ReservationCreateCommand command = objectMapper.readValue(message.getBody(), ReservationCreateCommand.class);
            HotReservationRedisService.HotRequestState state = hotRedis.getRequest(requestId);
            return state != null && matches(command, state)
                    ? LocalTransactionState.COMMIT_MESSAGE
                    : LocalTransactionState.ROLLBACK_MESSAGE;
        } catch (IOException exception) {
            log.error("Rejecting malformed reservation transaction message. requestId={}", requestId, exception);
            return LocalTransactionState.ROLLBACK_MESSAGE;
        } catch (RuntimeException exception) {
            log.warn("Reservation transaction check cannot reach Redis. requestId={}, reason={}",
                    requestId, exception.getMessage());
            return LocalTransactionState.UNKNOW;
        }
    }

    private ReservationCommandPublisher.LocalTransactionContext localContext(Object argument) {
        return argument instanceof ReservationCommandPublisher.LocalTransactionContext context
                ? context : null;
    }

    private boolean matches(ReservationCreateCommand command,
                            HotReservationRedisService.HotRequestState state) {
        return command.requestId().equals(state.requestId())
                && command.userId().equals(state.userId())
                && command.resourceId().equals(state.resourceId())
                && command.slotId().equals(state.slotId());
    }
}
