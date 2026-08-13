package com.fragment.labbooking.reservation.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fragment.labbooking.common.exception.BusinessException;
import com.fragment.labbooking.reservation.redis.HotReservationRedisService;
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
 * RocketMQ 事务消息监听器：把「Redis 预占」变成半消息的本地事务。
 */
@Component("reservationTransactionListener")
@RequiredArgsConstructor
@Slf4j
public class ReservationTransactionListener implements TransactionListener {

    private final HotReservationRedisService hotRedis;   // 热门时段 Redis 状态机（预占/查询都靠它）
    private final ObjectMapper objectMapper;             // 反序列化消息体（回查时要用）

    /**
     * 半消息发成功后，broker 回调。
     */
    @Override
    public LocalTransactionState executeLocalTransaction(Message message, Object argument) {
        // 从消息参数里取回 publish() 塞进来的进程内上下文。
        ReservationCommandPublisher.LocalTransactionContext context = localContext(argument);
        if (context == null) {
            log.error("Reservation transaction is missing its local context");
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
        try {
            // 取出预约命令
            ReservationCreateCommand command = context.command();
            // 执行 Redis 预占
            HotReservationRedisService.HotRequestState state = hotRedis.accept(
                    command.requestId(), command.userId(), command.resourceId(), command.slotId());
            // 预占成功
            context.commit(state);
            return LocalTransactionState.COMMIT_MESSAGE;
        } catch (BusinessException exception) {
            // 业务异常：503 表示"Redis 状态未知" 返回 UNKNOW 等回查兜底。
            if (exception.getCode() != null && exception.getCode() == 503) {
                log.warn("Redis decision is unknown for reservation transaction: {}", exception.getMessage());
                return LocalTransactionState.UNKNOW;
            }
            // 其他业务异常(余量不足/重复预约等)：预占失败 → 回滚半消息，并记录失败原因。
            context.rollback(exception);
            return LocalTransactionState.ROLLBACK_MESSAGE;
        } catch (RuntimeException exception) {
            // 非业务异常（Redis 连不上/超时等）：决策不了 → UNKNOW，等 broker 回查。
            log.warn("Unable to execute reservation Redis transaction", exception);
            return LocalTransactionState.UNKNOW;
        }
    }

    /**
     * 第一次决策是 UNKNOW 时，broker 会延迟回查这里。
     */
    @Override
    public LocalTransactionState checkLocalTransaction(MessageExt message) {
        // 消息的 key 就是 requestId
        String requestId = message.getKeys();
        if (!StringUtils.hasText(requestId)) {
            log.error("Reservation transaction message has no requestId key");
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
        try {
            // 从消息体反序列化出原始命令，拿它和 Redis 记录比对。
            ReservationCreateCommand command = objectMapper.readValue(message.getBody(), ReservationCreateCommand.class);
            // 查 Redis 请求记录。
            HotReservationRedisService.HotRequestState state = hotRedis.getRequest(requestId);
            // 记录存在且四个字段都对得上 → 提交；否则回滚。
            return state != null && matches(command, state)
                    ? LocalTransactionState.COMMIT_MESSAGE
                    : LocalTransactionState.ROLLBACK_MESSAGE;
        } catch (IOException exception) {
            // 消息体解析不了 → 数据有问题，直接回滚。
            log.error("Rejecting malformed reservation transaction message. requestId={}", requestId, exception);
            return LocalTransactionState.ROLLBACK_MESSAGE;
        } catch (RuntimeException exception) {
            // Redis 连不上 → 还是决策不了 → 继续 UNKNOW，等下一次回查。
            log.warn("Reservation transaction check cannot reach Redis. requestId={}, reason={}",
                    requestId, exception.getMessage());
            return LocalTransactionState.UNKNOW;
        }
    }

    /** 把回调参数转成 publish() 塞进来的进程内上下文。 */
    private ReservationCommandPublisher.LocalTransactionContext localContext(Object argument) {
        return argument instanceof ReservationCommandPublisher.LocalTransactionContext context
                ? context : null;
    }

    /** 比对：Redis 里的请求记录和消息里的命令必须完全一致才算有效预占。 */
    private boolean matches(ReservationCreateCommand command,
                            HotReservationRedisService.HotRequestState state) {
        return command.requestId().equals(state.requestId())
                && command.userId().equals(state.userId())
                && command.resourceId().equals(state.resourceId())
                && command.slotId().equals(state.slotId());
    }
}
