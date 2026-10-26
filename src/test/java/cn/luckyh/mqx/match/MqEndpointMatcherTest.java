package cn.luckyh.mqx.match;

import cn.luckyh.mqx.model.MqDestination;
import cn.luckyh.mqx.model.MqEndpoint;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class MqEndpointMatcherTest {

    private final MqEndpointMatcher matcher = new MqEndpointMatcher();

    @Test
    public void matchesRabbitDefaultDirectTopicAndFanout() {
        assertEquals(MqEndpointMatcher.MatchKind.EXACT, matcher.match(
                MqEndpoint.SystemType.RABBITMQ,
                MqDestination.rabbitProducer("", "orders"),
                MqDestination.rabbitConsumer("", "orders", List.of("orders"), "direct")));
        assertEquals(MqEndpointMatcher.MatchKind.EXACT, matcher.match(
                MqEndpoint.SystemType.RABBITMQ,
                MqDestination.rabbitProducer("events", "order.created"),
                MqDestination.rabbitConsumer("events", "q", List.of("order.*"), "topic")));
        assertEquals(MqEndpointMatcher.MatchKind.EXACT, matcher.match(
                MqEndpoint.SystemType.RABBITMQ,
                MqDestination.rabbitProducer("broadcast", "ignored"),
                MqDestination.rabbitConsumer("broadcast", "q", List.of(), "fanout")));
    }

    @Test
    public void topicHashMatchesZeroOrMoreRoutingSegments() {
        MqDestination initQueue = MqDestination.rabbitConsumer("billing.events", "billing.queue",
                List.of("#.init"), "topic");
        assertEquals(MqEndpointMatcher.MatchKind.EXACT, matcher.match(MqEndpoint.SystemType.RABBITMQ,
                MqDestination.rabbitProducer("billing.events", "calc.billing.init"), initQueue));
        assertEquals(MqEndpointMatcher.MatchKind.EXACT, matcher.match(MqEndpoint.SystemType.RABBITMQ,
                MqDestination.rabbitProducer("billing.events", "init"), initQueue));
        assertEquals(MqEndpointMatcher.MatchKind.NONE, matcher.match(MqEndpoint.SystemType.RABBITMQ,
                MqDestination.rabbitProducer("billing.events", "calc.billing.done"), initQueue));
        assertEquals(MqEndpointMatcher.MatchKind.EXACT, matcher.match(MqEndpoint.SystemType.RABBITMQ,
                MqDestination.rabbitProducer("orders", "order"),
                MqDestination.rabbitConsumer("orders", "queue", List.of("order.#"), "topic")));
        assertEquals(MqEndpointMatcher.MatchKind.EXACT, matcher.match(MqEndpoint.SystemType.RABBITMQ,
                MqDestination.rabbitProducer("orders", "order.created"),
                MqDestination.rabbitConsumer("orders", "queue", List.of("order.#"), "topic")));
        assertEquals(MqEndpointMatcher.MatchKind.EXACT, matcher.match(MqEndpoint.SystemType.RABBITMQ,
                MqDestination.rabbitProducer("orders", "order.created"),
                MqDestination.rabbitConsumer("orders", "queue", List.of("order.#.created"), "topic")));
        assertEquals(MqEndpointMatcher.MatchKind.EXACT, matcher.match(MqEndpoint.SystemType.RABBITMQ,
                MqDestination.rabbitProducer("orders", "order.created"),
                MqDestination.rabbitConsumer("orders", "queue", List.of("order.*"), "topic")));
        assertEquals(MqEndpointMatcher.MatchKind.NONE, matcher.match(MqEndpoint.SystemType.RABBITMQ,
                MqDestination.rabbitProducer("orders", "order"),
                MqDestination.rabbitConsumer("orders", "queue", List.of("order.*"), "topic")));
        assertEquals(MqEndpointMatcher.MatchKind.EXACT, matcher.match(MqEndpoint.SystemType.RABBITMQ,
                MqDestination.rabbitProducer("orders", ""),
                MqDestination.rabbitConsumer("orders", "queue", List.of("#"), "topic")));
    }

    @Test
    public void matchesKafkaPatternAndRocketTag() {
        assertEquals(MqEndpointMatcher.MatchKind.EXACT, matcher.match(
                MqEndpoint.SystemType.KAFKA,
                MqDestination.kafkaTopic("orders.eu", null),
                MqDestination.kafkaPattern("orders\\..*", "group")));
        assertEquals(MqEndpointMatcher.MatchKind.EXACT, matcher.match(
                MqEndpoint.SystemType.ROCKETMQ,
                MqDestination.rocketProducer("notice", "SMS"),
                MqDestination.rocketConsumer("notice", "group", "TAG", "SMS||ALL")));
        assertEquals(MqEndpointMatcher.MatchKind.UNCERTAIN, matcher.match(
                MqEndpoint.SystemType.ROCKETMQ,
                MqDestination.rocketProducer("notice", "SMS"),
                MqDestination.rocketConsumer("notice", "group", "SQL92", "type='sms'")));
    }

    @Test
    public void sameCompatibilityRuleServesReverseNavigation() {
        MqDestination producer = MqDestination.kafkaTopic("orders", null);
        MqDestination consumer = MqDestination.kafkaTopic("orders", "billing");
        assertEquals(MqEndpointMatcher.MatchKind.EXACT,
                matcher.match(MqEndpoint.SystemType.KAFKA, producer, consumer));
    }
}
