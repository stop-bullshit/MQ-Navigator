package org.example.messaging;

public class MessagePublisher {
    private org.springframework.amqp.rabbit.core.RabbitTemplate template;

    public void publish(String exchange, String routingKey, Object message) {
        template.convertAndSend(exchange, routingKey, message);
    }
}
