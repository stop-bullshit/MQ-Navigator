package org.springframework.amqp.rabbit.annotation;
public @interface QueueBinding {
    org.springframework.amqp.rabbit.annotation.Queue value();
    org.springframework.amqp.rabbit.annotation.Exchange exchange();
    String[] key() default {};
}
