package org.springframework.amqp.rabbit.annotation;
public @interface Exchange { String value() default ""; String type() default "direct"; }
