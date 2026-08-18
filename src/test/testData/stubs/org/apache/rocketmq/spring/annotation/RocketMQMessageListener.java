package org.apache.rocketmq.spring.annotation;
public @interface RocketMQMessageListener {
    String topic();
    String consumerGroup();
    SelectorType selectorType() default SelectorType.TAG;
    String selectorExpression() default "*";
}
