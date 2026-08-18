package cn.luckyh.mqx.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record MqDestination(
        @Nullable String topic,
        @Nullable String exchange,
        @Nullable String queue,
        @Nullable String routingKey,
        @NotNull List<String> bindingKeys,
        @Nullable String exchangeType,
        @Nullable String topicPattern,
        @Nullable String tag,
        @Nullable String selectorType,
        @Nullable String selectorExpression,
        @Nullable String consumerGroup) {

    public MqDestination {
        bindingKeys = List.copyOf(bindingKeys);
    }

    public static MqDestination rabbitProducer(String exchange, String routingKey) {
        return new MqDestination(null, exchange, null, routingKey, List.of(), null,
                null, null, null, null, null);
    }

    public static MqDestination rabbitConsumer(
            String exchange, String queue, List<String> bindingKeys, String exchangeType) {
        return new MqDestination(null, exchange, queue, null, bindingKeys, exchangeType,
                null, null, null, null, null);
    }

    public static MqDestination kafkaTopic(String topic, String consumerGroup) {
        return new MqDestination(topic, null, null, null, List.of(), null,
                null, null, null, null, consumerGroup);
    }

    public static MqDestination kafkaPattern(String topicPattern, String consumerGroup) {
        return new MqDestination(null, null, null, null, List.of(), null,
                topicPattern, null, null, null, consumerGroup);
    }

    public static MqDestination rocketProducer(String topic, String tag) {
        return new MqDestination(topic, null, null, null, List.of(), null,
                null, tag, null, null, null);
    }

    public static MqDestination rocketConsumer(
            String topic, String consumerGroup, String selectorType, String selectorExpression) {
        return new MqDestination(topic, null, null, null, List.of(), null,
                null, null, selectorType, selectorExpression, consumerGroup);
    }

    public String summary() {
        if (topic != null) {
            String selector = selectorExpression != null ? ", selector=" + selectorExpression : "";
            String group = consumerGroup != null ? ", group=" + consumerGroup : "";
            String tagValue = tag != null && !tag.isEmpty() ? ", tag=" + tag : "";
            return "topic=" + topic + tagValue + selector + group;
        }
        if (topicPattern != null) {
            return "pattern=" + topicPattern + (consumerGroup != null ? ", group=" + consumerGroup : "");
        }
        String queueValue = queue != null ? ", queue=" + queue : "";
        String keyValue = routingKey != null ? ", key=" + routingKey
                : (!bindingKeys.isEmpty() ? ", keys=" + String.join("|", bindingKeys) : "");
        return "exchange=" + (exchange == null ? "" : exchange) + keyValue + queueValue;
    }
}
