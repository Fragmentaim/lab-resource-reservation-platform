package com.fragment.labbooking.common.reservation;

import com.alibaba.cloud.stream.binder.rocketmq.constant.RocketMQConst;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.common.redis.HotReservationRedisService;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/** Sends a hot-reservation command as a RocketMQ transaction message. */
@Component
@RequiredArgsConstructor
public class ReservationCommandPublisher {

    public static final String OUTPUT_BINDING = "reservationCommand-out-0";
    public static final String TAG_CREATE = "CREATE";

    private final StreamBridge streamBridge;

    public HotReservationRedisService.HotRequestState publish(ReservationCreateCommand command) {
        LocalTransactionContext transaction = new LocalTransactionContext(command);
        try {
            boolean accepted = streamBridge.send(
                    OUTPUT_BINDING,
                    MessageBuilder.withPayload(command)
                            .setHeader(RocketMQConst.Headers.TAGS, TAG_CREATE)
                            .setHeader(RocketMQConst.Headers.KEYS, command.requestId())
                            .setHeader(RocketMQConst.USER_TRANSACTIONAL_ARGS, transaction)
                            .build()
            );
            if (!accepted) {
                throw unavailable(null);
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
        return transaction.result();
    }

    private BusinessException unavailable(Throwable cause) {
        BusinessException exception = new BusinessException(503, "热门预约消息暂时无法受理，请稍后重试");
        if (cause != null) {
            exception.initCause(cause);
        }
        return exception;
    }

    /**
     * Carries the local Redis decision back through StreamBridge's synchronous
     * transaction send. It is process-local and is never serialized to MQ.
     */
    static final class LocalTransactionContext {
        private final ReservationCreateCommand command;
        private HotReservationRedisService.HotRequestState state;
        private BusinessException rejection;

        LocalTransactionContext(ReservationCreateCommand command) {
            this.command = command;
        }

        ReservationCreateCommand command() {
            return command;
        }

        void commit(HotReservationRedisService.HotRequestState state) {
            this.state = state;
        }

        void rollback(BusinessException rejection) {
            this.rejection = rejection;
        }

        HotReservationRedisService.HotRequestState result() {
            if (state != null) {
                return state;
            }
            if (rejection != null) {
                throw rejection;
            }
            throw new BusinessException(503, "预约事务状态正在由消息队列确认，请使用相同 Idempotency-Key 重试");
        }
    }
}
