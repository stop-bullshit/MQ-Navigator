package cn.luckyh.mqx.match;

import cn.luckyh.mqx.model.MqDestination;
import cn.luckyh.mqx.model.MqEndpoint;

import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class MqEndpointMatcher {

    public MatchKind match(MqEndpoint first, MqEndpoint second) {
        if (first.system() != second.system()) {
            return MatchKind.NONE;
        }
        // 逻辑变动: 队列声明只参与拓扑计算不充当发送或消费跳转目标-20261023-1204-01
        if (first.role() == MqEndpoint.Role.DECLARATION || second.role() == MqEndpoint.Role.DECLARATION
                || first.role() == MqEndpoint.Role.BINDING || second.role() == MqEndpoint.Role.BINDING
                || first.role() == MqEndpoint.Role.EXCHANGE_DECLARATION
                || second.role() == MqEndpoint.Role.EXCHANGE_DECLARATION) {
            return MatchKind.NONE;
        }
        if (first.role() == MqEndpoint.Role.CONSUMER
                && second.role() == MqEndpoint.Role.PRODUCER) {
            return match(second.system(), second.destination(), first.destination());
        }
        if (first.role() == MqEndpoint.Role.PRODUCER
                && second.role() == MqEndpoint.Role.CONSUMER) {
            return match(first.system(), first.destination(), second.destination());
        }
        return MatchKind.NONE;
    }

    public MatchKind match(
            MqEndpoint.SystemType system,
            MqDestination producer,
            MqDestination consumer) {
        // 逻辑变动: 按三种消息系统的真实路由信息判断静态关系-20260817-1826-01
        return switch (system) {
            case RABBITMQ -> matchRabbit(producer, consumer);
            case KAFKA -> matchKafka(producer, consumer);
            case ROCKETMQ -> matchRocket(producer, consumer);
        };
    }

    private MatchKind matchRabbit(MqDestination producer, MqDestination consumer) {
        if (!Objects.equals(producer.exchange(), consumer.exchange())) {
            return MatchKind.NONE;
        }
        String type = Objects.requireNonNullElse(consumer.exchangeType(), "direct");
        if (consumer.exchange() != null && consumer.exchange().isEmpty()) {
            return Objects.equals(producer.routingKey(), consumer.queue()) ? MatchKind.EXACT : MatchKind.NONE;
        }
        return switch (type) {
            case "direct" -> consumer.bindingKeys().contains(producer.routingKey())
                    ? MatchKind.EXACT : MatchKind.NONE;
            case "topic" -> consumer.bindingKeys().stream()
                    .anyMatch(pattern -> rabbitTopicMatches(pattern, producer.routingKey()))
                    ? MatchKind.EXACT : MatchKind.NONE;
            case "fanout" -> MatchKind.EXACT;
            case "headers", "x-delayed-message" -> MatchKind.UNCERTAIN;
            default -> MatchKind.UNCERTAIN;
        };
    }

    private boolean rabbitTopicMatches(String pattern, String routingKey) {
        if (routingKey == null) {
            return false;
        }
        String[] bindingWords = pattern.isEmpty() ? new String[0] : pattern.split("\\.", -1);
        String[] routingWords = routingKey.isEmpty() ? new String[0] : routingKey.split("\\.", -1);
        boolean[] matched = new boolean[routingWords.length + 1];
        matched[0] = true;
        // 逻辑变动: topic井号按零个或多个完整路由段匹配-20261023-1204-01
        for (String bindingWord : bindingWords) {
            boolean[] next = new boolean[routingWords.length + 1];
            if ("#".equals(bindingWord)) {
                next[0] = matched[0];
                for (int index = 1; index < next.length; index++) {
                    next[index] = matched[index]
                            || (!routingWords[index - 1].isEmpty() && next[index - 1]);
                }
            } else {
                for (int index = 1; index < next.length; index++) {
                    next[index] = matched[index - 1] && (bindingWord.equals(routingWords[index - 1])
                            || "*".equals(bindingWord) && !routingWords[index - 1].isEmpty());
                }
            }
            matched = next;
        }
        return matched[routingWords.length];
    }

    private MatchKind matchKafka(MqDestination producer, MqDestination consumer) {
        if (producer.topic() == null) {
            return MatchKind.NONE;
        }
        if (consumer.topic() != null) {
            return producer.topic().equals(consumer.topic()) ? MatchKind.EXACT : MatchKind.NONE;
        }
        if (consumer.topicPattern() == null) {
            return MatchKind.NONE;
        }
        try {
            return Pattern.compile(consumer.topicPattern()).matcher(producer.topic()).matches()
                    ? MatchKind.EXACT : MatchKind.NONE;
        } catch (PatternSyntaxException ignored) {
            return MatchKind.NONE;
        }
    }

    private MatchKind matchRocket(MqDestination producer, MqDestination consumer) {
        if (!Objects.equals(producer.topic(), consumer.topic())) {
            return MatchKind.NONE;
        }
        if ("SQL92".equalsIgnoreCase(consumer.selectorType())) {
            return MatchKind.UNCERTAIN;
        }
        String selector = Objects.requireNonNullElse(consumer.selectorExpression(), "*");
        if ("*".equals(selector)) {
            return MatchKind.EXACT;
        }
        String tag = Objects.requireNonNullElse(producer.tag(), "");
        for (String accepted : selector.split("\\|\\|")) {
            if (tag.equals(accepted.trim())) {
                return MatchKind.EXACT;
            }
        }
        return MatchKind.NONE;
    }

    public enum MatchKind {
        NONE,
        EXACT,
        UNCERTAIN
    }
}
