package org.springframework.amqp.core;

import java.util.Map;

public class QueueBuilder {
    public static QueueBuilder durable(String name) { return new QueueBuilder(); }
    public QueueBuilder quorum() { return this; }
    public QueueBuilder withArgument(String key, Object value) { return this; }
    public QueueBuilder withArguments(Map<String, Object> arguments) { return this; }
    public Queue build() { return new Queue(""); }
}
