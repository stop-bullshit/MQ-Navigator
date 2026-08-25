package cn.luckyh.mqx.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

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
        @Nullable String consumerGroup,
        @Nullable RabbitQueueSettings rabbitQueueSettings) {

    public MqDestination {
        bindingKeys = List.copyOf(bindingKeys);
    }

    public static MqDestination rabbitProducer(String exchange, String routingKey) {
        return new MqDestination(null, exchange, null, routingKey, List.of(), null,
                null, null, null, null, null, null);
    }

    public static MqDestination rabbitConsumer(
            String exchange, String queue, List<String> bindingKeys, String exchangeType) {
        return new MqDestination(null, exchange, queue, null, bindingKeys, exchangeType,
                null, null, null, null, null, null);
    }

    public static MqDestination rabbitQueue(String queue, RabbitQueueSettings rabbitQueueSettings) {
        return rabbitQueue("", queue, List.of(), "direct", rabbitQueueSettings);
    }

    public static MqDestination rabbitQueue(
            String exchange,
            String queue,
            List<String> bindingKeys,
            String exchangeType,
            RabbitQueueSettings rabbitQueueSettings) {
        return new MqDestination(null, exchange, queue, null, bindingKeys, exchangeType,
                null, null, null, null, null, rabbitQueueSettings);
    }

    public static MqDestination kafkaTopic(String topic, String consumerGroup) {
        return new MqDestination(topic, null, null, null, List.of(), null,
                null, null, null, null, consumerGroup, null);
    }

    public static MqDestination kafkaPattern(String topicPattern, String consumerGroup) {
        return new MqDestination(null, null, null, null, List.of(), null,
                topicPattern, null, null, null, consumerGroup, null);
    }

    public static MqDestination rocketProducer(String topic, String tag) {
        return new MqDestination(topic, null, null, null, List.of(), null,
                null, tag, null, null, null, null);
    }

    public static MqDestination rocketConsumer(
            String topic, String consumerGroup, String selectorType, String selectorExpression) {
        return new MqDestination(topic, null, null, null, List.of(), null,
                null, null, selectorType, selectorExpression, consumerGroup, null);
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
        String result = "exchange=" + (exchange == null ? "" : exchange) + keyValue + queueValue;
        return rabbitQueueSettings == null ? result : result + rabbitQueueSettings.summary();
    }

    public record RabbitQueueSettings(
            boolean deadLetterExchangeConfigured,
            @Nullable String deadLetterExchange,
            boolean deadLetterRoutingKeyConfigured,
            @Nullable String deadLetterRoutingKey,
            boolean messageTtlConfigured,
            @Nullable Long messageTtlMillis,
            @NotNull List<String> diagnostics) {

        public RabbitQueueSettings {
            diagnostics = List.copyOf(diagnostics);
            if (!deadLetterExchangeConfigured && deadLetterExchange != null) {
                throw new IllegalArgumentException("Dead-letter exchange value requires a configured argument");
            }
            if (!deadLetterRoutingKeyConfigured && deadLetterRoutingKey != null) {
                throw new IllegalArgumentException("Dead-letter routing key value requires a configured argument");
            }
            if (!messageTtlConfigured && messageTtlMillis != null) {
                throw new IllegalArgumentException("Message TTL value requires a configured argument");
            }
            if (messageTtlMillis != null && messageTtlMillis < 0) {
                throw new IllegalArgumentException("Message TTL must not be negative");
            }
        }

        public RabbitQueueKind kind() {
            if (deadLetterExchangeConfigured && messageTtlConfigured) {
                return RabbitQueueKind.DELAY;
            }
            return deadLetterExchangeConfigured
                    ? RabbitQueueKind.DEAD_LETTER_SOURCE : RabbitQueueKind.STANDARD;
        }

        private String summary() {
            StringBuilder result = new StringBuilder(", type=").append(kind().label);
            if (messageTtlConfigured) {
                result.append(", ttl=").append(messageTtlMillis == null ? "?" : messageTtlMillis + "ms");
            }
            if (deadLetterExchangeConfigured) {
                result.append(", dlx=");
                if (deadLetterExchange == null) {
                    result.append('?');
                } else if (deadLetterExchange.isEmpty()) {
                    result.append("<default>");
                } else {
                    result.append(deadLetterExchange);
                }
                result.append(", dead-key=");
                if (!deadLetterRoutingKeyConfigured) {
                    result.append("<original>");
                } else {
                    result.append(Objects.requireNonNullElse(deadLetterRoutingKey, "?"));
                }
            }
            if (!diagnostics.isEmpty()) {
                result.append(", unresolved");
            }
            return result.toString();
        }
    }

    public enum RabbitQueueKind {
        STANDARD("standard"),
        DEAD_LETTER_SOURCE("dead-letter"),
        DELAY("delay");

        private final String label;

        RabbitQueueKind(String label) {
            this.label = label;
        }
    }
}
