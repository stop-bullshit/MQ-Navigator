package org.springframework.amqp.rabbit.core;
public class RabbitTemplate implements org.springframework.amqp.core.AmqpTemplate {
    public void send(String routingKey, Object message) {}
    public void send(String exchange, String routingKey, Object message) {}
    public void convertAndSend(String routingKey, Object payload) {}
    public void convertAndSend(String exchange, String routingKey, Object payload) {}
}
