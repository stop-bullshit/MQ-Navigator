package org.springframework.amqp.rabbit.annotation;
public @interface RabbitListener {
    String[] queues() default {};
    org.springframework.amqp.rabbit.annotation.Queue[] queuesToDeclare() default {};
    org.springframework.amqp.rabbit.annotation.QueueBinding[] bindings() default {};
}
