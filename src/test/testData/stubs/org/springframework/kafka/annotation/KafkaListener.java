package org.springframework.kafka.annotation;
public @interface KafkaListener {
    String[] topics() default {};
    String topicPattern() default "";
    String groupId() default "";
    String id() default "";
}
