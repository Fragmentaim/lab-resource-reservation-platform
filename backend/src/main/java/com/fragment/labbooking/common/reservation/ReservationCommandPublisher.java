package com.fragment.labbooking.common.reservation;

import com.alibaba.cloud.stream.binder.rocketmq.constant.RocketMQConst;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.common.redis.HotReservationRedisService;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 热门预约命令发布器：把"预约命令"作为 RocketMQ 事务消息发出去。
 */
@Component
@RequiredArgsConstructor
public class ReservationCommandPublisher {

    public static final String OUTPUT_BINDING = "reservationCommand-out-0";
    public static final String TAG_CREATE = "CREATE";
    private final StreamBridge streamBridge;

    /**
     * 发送一条热门预约事务消息，并返回 Redis 预占的结果。
     */
    public HotReservationRedisService.HotRequestState publish(ReservationCreateCommand command) {
        // 进程内上下文：用于把"本地事务(Redis预占)的结论"传回给当前请求线程。
        LocalTransactionContext transaction = new LocalTransactionContext(command);
        try {
            boolean accepted = streamBridge.send(
                    OUTPUT_BINDING,
                    MessageBuilder.withPayload(command)                       // 消息体 = 预约命令
                            .setHeader(RocketMQConst.Headers.TAGS, TAG_CREATE)   // 标签
                            .setHeader(RocketMQConst.Headers.KEYS, command.requestId())  // 消息 key = requestId（用于幂等/回查）
                            .setHeader(RocketMQConst.USER_TRANSACTIONAL_ARGS, transaction)  // 把上下文塞进事务参数，回调时能取回
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
        // 走到这说明半消息已发送、本地事务已有结论，从上下文里取出结果返回。
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
     * 本地事务上下文：在「当前进程内」传递 Redis 预占的结论。
     */
    static final class LocalTransactionContext {
        private final ReservationCreateCommand command;    // 原始预约命令
        private HotReservationRedisService.HotRequestState state;  // 预占成功的结果
        private BusinessException rejection;               // 预占失败的原因

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
                return state;   // 预占成功 → 返回结果
            }
            if (rejection != null) {
                throw rejection;  // 预占失败 → 抛业务异常
            }
            // 都没有 → 事务状态未知（MQ 还没回查完），提示用相同 Idempotency-Key 重试
            throw new BusinessException(503, "预约事务状态正在由消息队列确认，请使用相同 Idempotency-Key 重试");
        }
    }
}
