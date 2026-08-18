package perf.m${module};
@org.apache.rocketmq.spring.annotation.RocketMQMessageListener(
    topic="rocket-${module}-0", consumerGroup="group-${module}-0")
class RocketConsumer${module} {}
${classes}
