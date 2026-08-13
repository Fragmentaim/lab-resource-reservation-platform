package com.fragment.labbooking.common.reservation;

import com.fragment.labbooking.common.outbox.MessageOutboxEnvelope;
import com.fragment.labbooking.service.reservation.ReservationConfirmationService;
import com.fragment.labbooking.service.reservation.ReservationResultService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

import java.util.function.Consumer;

/**
 * 消息消费端定义（。
 */
@Configuration
public class ReservationStreamConsumers {

    /**
     * 直接委托给 ReservationConfirmationService.confirm()
     */
    @Bean
    Consumer<ReservationCreateCommand> reservationCommandConsumer(
            ReservationConfirmationService confirmationService) {
        return confirmationService::confirm;
    }

    /**
     * 消费「结果事件」(ReservationResultEvent) 的消费者。
     * 消息来源：ReservationConfirmationService 落库后通过 Outbox 发布的结果事件。
     * 处理逻辑：反序列化出 ReservationResultEvent，委托给 ReservationResultService.handle()——
     *   收敛 Redis 状态 + 发站内通知 + (仅成功时)建提醒/排自动取消。
     *
     * 注意：消息体是 Outbox 信封(MessageOutboxEnvelope)，真正的业务事件在 envelope.getPayload() 里，
     * 所以要先用 ObjectMapper 反序列化出来，再交给 resultService 处理。
     */
    @Bean
    Consumer<Message<MessageOutboxEnvelope>> reservationResultConsumer(
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            ReservationResultService resultService) {
        return message -> {
            MessageOutboxEnvelope envelope = message.getPayload();
            try {
                // 从 Outbox 信封里取出真正的预约结果事件。
                ReservationResultEvent event =
                        objectMapper.readValue(envelope.getPayload(), ReservationResultEvent.class);
                // 交给收尾服务处理（Redis收敛 + 通知 + 提醒/自动取消）。
                resultService.handle(envelope.getEventId(), event);
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                // 信封内容解析不了 → 数据有问题，抛异常让 MQ 重投。
                throw new IllegalArgumentException("Invalid reservation result payload", exception);
            }
        };
    }
}
