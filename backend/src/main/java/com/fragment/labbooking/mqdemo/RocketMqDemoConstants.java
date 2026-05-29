package com.fragment.labbooking.mqdemo;

public final class RocketMqDemoConstants {

    public static final String NAME_SERVER = propertyOrDefault("rocketmq.demo.namesrv", "192.168.91.129:9876");
    public static final String TOPIC = propertyOrDefault("rocketmq.demo.topic", "lab-booking-demo-topic");
    public static final String PRODUCER_GROUP = propertyOrDefault("rocketmq.demo.producer-group", "lab-booking-demo-producer-group");
    public static final String CONSUMER_GROUP = propertyOrDefault("rocketmq.demo.consumer-group", "lab-booking-demo-consumer-group");
    public static final String TAG = propertyOrDefault("rocketmq.demo.tag", "demo-tag");
    public static final String INSTANCE_NAME = propertyOrDefault("rocketmq.demo.instance-name", "demo-instance");
    public static final String LABEL = propertyOrDefault("rocketmq.demo.label", INSTANCE_NAME);

    private RocketMqDemoConstants() {
    }

    private static String propertyOrDefault(String key, String defaultValue) {
        String value = System.getProperty(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
