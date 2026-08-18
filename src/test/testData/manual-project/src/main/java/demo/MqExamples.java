package demo;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.annotation.SelectorType;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;

class Producers {
    private static final String STATIC_KAFKA_TOPIC = "static-orders";

    private final RabbitTemplate rabbit;
    private final KafkaTemplate<String, Object> kafka;
    private final RocketMQTemplate rocket;

    @Value("${mq.kafka-topic}")
    private String kafkaTopic;

    Producers(RabbitTemplate rabbit, KafkaTemplate<String, Object> kafka, RocketMQTemplate rocket) {
        this.rabbit = rabbit;
        this.kafka = kafka;
        this.rocket = rocket;
    }

    void rabbitDefault() {
        rabbit.convertAndSend("orders", new Object());
    }

    void rabbitDirect() {
        rabbit.convertAndSend("billing", "invoice.paid", new Object());
    }

    void rabbitTopic() {
        rabbit.convertAndSend("events", "orders.created", new Object());
    }

    void rabbitFanout() {
        rabbit.convertAndSend("audit", "ignored", new Object());
    }

    void kafkaConfigured() {
        kafka.send(kafkaTopic, new Object());
    }

    void kafkaStatic() {
        kafka.send(STATIC_KAFKA_TOPIC, new Object());
    }

    void kafkaPatternAndGroups() {
        kafka.send("orders.created", new Object());
        kafka.send("shared-events", new Object());
    }

    void kafkaDynamic(String runtimeTopic) {
        kafka.send(runtimeTopic, new Object());
    }

    void kafkaExcluded() {
        kafka.sendDefault(new Object());
    }

    void rocketSms() {
        rocket.syncSend("notice:SMS", new Object());
    }

    void rocketEmail() {
        rocket.sendOneWay("notice:EMAIL", new Object());
    }

    void rocketDynamic(String destination) {
        rocket.syncSend(destination, new Object());
    }
}

class Consumers {
    @RabbitListener(queues = "orders")
    void rabbitDefault(Object value) {}

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue("orders.events"),
            exchange = @Exchange(value = "events", type = "topic"),
            key = "orders.*"))
    void rabbitTopic(Object value) {}

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue("billing.invoice"),
            exchange = @Exchange(value = "billing", type = "direct"),
            key = "invoice.paid"))
    void rabbitDirect(Object value) {}

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue("audit.one"),
            exchange = @Exchange(value = "audit", type = "fanout")))
    void rabbitFanoutOne(Object value) {}

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue("audit.two"),
            exchange = @Exchange(value = "audit", type = "fanout")))
    void rabbitFanoutTwo(Object value) {}

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue("headers.queue"),
            exchange = @Exchange(value = "headers", type = "headers")))
    void rabbitUncertain(Object value) {}

    @KafkaListener(topics = "${mq.kafka-topic}", groupId = "orders")
    void kafkaConfigured(Object value) {}

    @KafkaListener(topics = "static-orders", groupId = "static")
    void kafkaStatic(Object value) {}

    @KafkaListener(topicPattern = "orders\\..*", groupId = "pattern")
    void kafkaPattern(Object value) {}

    @KafkaListener(topics = "shared-events", groupId = "group-one")
    void kafkaGroupOne(Object value) {}

    @KafkaListener(topics = "shared-events", groupId = "group-two")
    void kafkaGroupTwo(Object value) {}

    @KafkaListener(topicPattern = "[", groupId = "invalid-pattern")
    void kafkaInvalidPattern(Object value) {}
}

class QueueDeclarations {
    org.springframework.amqp.rabbit.core.RabbitAdmin admin;

    org.springframework.amqp.core.Queue ordersQueue() {
        org.springframework.amqp.core.Queue queue = org.springframework.amqp.core.QueueBuilder
                .durable("orders").quorum().build();
        admin.declareQueue(queue);
        return queue;
    }
}

@RocketMQMessageListener(topic = "notice", consumerGroup = "sms", selectorExpression = "SMS||ALL")
class RocketConsumer {}

@RocketMQMessageListener(topic = "notice", consumerGroup = "all", selectorExpression = "*")
class RocketAllConsumer {}

@RocketMQMessageListener(topic = "notice", consumerGroup = "email", selectorExpression = "EMAIL")
class RocketEmailConsumer {}

@RocketMQMessageListener(
        topic = "notice",
        consumerGroup = "sql92",
        selectorType = SelectorType.SQL92,
        selectorExpression = "region = 'cn'")
class RocketSqlConsumer {}

class ValueExamples {
    private final KafkaTemplate<String, Object> kafka;

    @Value("${mq.kafka-topic}")
    private String configuredTopic;

    @Value("${mq.ambiguous-topic}")
    private String ambiguousTopic;

    @Value("#{systemProperties['user.name']}")
    private String spelTopic;

    ValueExamples(KafkaTemplate<String, Object> kafka) {
        this.kafka = kafka;
    }

    void configuredField() {
        kafka.send(this.configuredTopic, new Object());
    }

    void ambiguousField() {
        kafka.send(ambiguousTopic, new Object());
    }

    void literalPlaceholder() {
        kafka.send("${mq.kafka-topic}", new Object());
    }

    void unsupportedFlows(String constructorOrMethodValue) {
        String alias = configuredTopic;
        kafka.send(alias, new Object());
        kafka.send(topicFromMethod(), new Object());
        kafka.send(constructorOrMethodValue, new Object());
        kafka.send(spelTopic, new Object());
    }

    private String topicFromMethod() {
        return configuredTopic;
    }
}
