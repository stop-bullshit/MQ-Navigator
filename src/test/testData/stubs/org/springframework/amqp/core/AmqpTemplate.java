package org.springframework.amqp.core;
public interface AmqpTemplate {
    void send(String routingKey, Object message);
    void send(String exchange, String routingKey, Object message);
    void convertAndSend(String routingKey, Object payload);
    void convertAndSend(String exchange, String routingKey, Object payload);
}
