package com.fragment.labbooking.common.reservation;

import com.alibaba.cloud.stream.binder.rocketmq.constant.RocketMQConst;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReservationCommandPublisher {

    public static final String OUTPUT_BINDING = "reservationCommand-out-0";
    public static final String TAG_CREATE = "CREATE";

    private final StreamBridge streamBridge;

    public void publish(ReservationCreateCommand command) {
        boolean accepted = streamBridge.send(
                OUTPUT_BINDING,
                MessageBuilder.withPayload(command)
                        .setHeader(RocketMQConst.Headers.TAGS, TAG_CREATE)
                        .setHeader(RocketMQConst.Headers.KEYS, command.requestId())
                        .build()
        );
        if (!accepted) {
            throw new IllegalStateException("RocketMQ binder rejected reservation command " + command.requestId());
        }
    }
}
