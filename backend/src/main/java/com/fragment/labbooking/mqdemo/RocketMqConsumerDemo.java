package com.fragment.labbooking.mqdemo;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.common.message.MessageExt;

import java.nio.charset.StandardCharsets;
import java.util.List;

public class RocketMqConsumerDemo {

    public static void main(String[] args) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(RocketMqDemoConstants.CONSUMER_GROUP);
        consumer.setNamesrvAddr(RocketMqDemoConstants.NAME_SERVER);
        consumer.setInstanceName(RocketMqDemoConstants.INSTANCE_NAME);
        consumer.subscribe(RocketMqDemoConstants.TOPIC, "*");

        consumer.registerMessageListener((List<MessageExt> messages, ConsumeConcurrentlyContext context) -> {
            for (MessageExt message : messages) {
                String body = new String(message.getBody(), StandardCharsets.UTF_8);
                System.out.println("Received message on " + RocketMqDemoConstants.LABEL + ":");
                System.out.println("  consumerGroup = " + RocketMqDemoConstants.CONSUMER_GROUP);
                System.out.println("  instanceName = " + RocketMqDemoConstants.INSTANCE_NAME);
                System.out.println("  topic = " + message.getTopic());
                System.out.println("  keys = " + message.getKeys());
                System.out.println("  reconsumeTimes = " + message.getReconsumeTimes());
                System.out.println("  body = " + body);
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });

        consumer.start();
        System.out.println("RocketMQ consumer started.");
        System.out.println("Label: " + RocketMqDemoConstants.LABEL);
        System.out.println("Listening on topic: " + RocketMqDemoConstants.TOPIC);
        System.out.println("NameServer: " + RocketMqDemoConstants.NAME_SERVER);
        System.out.println("ConsumerGroup: " + RocketMqDemoConstants.CONSUMER_GROUP);
        System.out.println("InstanceName: " + RocketMqDemoConstants.INSTANCE_NAME);

        Thread.currentThread().join();
    }
}
