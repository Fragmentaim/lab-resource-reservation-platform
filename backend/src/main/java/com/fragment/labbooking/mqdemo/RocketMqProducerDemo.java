package com.fragment.labbooking.mqdemo;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

public class RocketMqProducerDemo {

    public static void main(String[] args) throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer(RocketMqDemoConstants.PRODUCER_GROUP);
        producer.setNamesrvAddr(RocketMqDemoConstants.NAME_SERVER);
        producer.setInstanceName(RocketMqDemoConstants.INSTANCE_NAME);
        producer.start();

        try {
            System.out.println("RocketMQ producer sent message successfully.");
            System.out.println("Label: " + RocketMqDemoConstants.LABEL);
            System.out.println("Topic: " + RocketMqDemoConstants.TOPIC);
            System.out.println("Tag: " + RocketMqDemoConstants.TAG);
            System.out.println("ProducerGroup: " + RocketMqDemoConstants.PRODUCER_GROUP);
            System.out.println("InstanceName: " + RocketMqDemoConstants.INSTANCE_NAME);
            int messageCount = Integer.getInteger("rocketmq.demo.message-count", 1);
            for (int i = 1; i <= messageCount; i++) {
                String body = "hello rocketmq from " + RocketMqDemoConstants.LABEL + " #" + i + " @ " + LocalDateTime.now();
                Message message = new Message(
                        RocketMqDemoConstants.TOPIC,
                        RocketMqDemoConstants.TAG,
                        "demo-key-" + System.currentTimeMillis() + "-" + i,
                        body.getBytes(StandardCharsets.UTF_8)
                );

                SendResult sendResult = producer.send(message);
                System.out.println("Body: " + body);
                System.out.println("SendResult: " + sendResult);
            }
        } finally {
            producer.shutdown();
        }
    }
}
