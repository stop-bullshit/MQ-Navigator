package org.apache.rocketmq.spring.core;
public class RocketMQTemplate {
    public void syncSend(String destination, Object payload) {}
    public void asyncSend(String destination, Object payload) {}
    public void sendOneWay(String destination, Object payload) {}
}
