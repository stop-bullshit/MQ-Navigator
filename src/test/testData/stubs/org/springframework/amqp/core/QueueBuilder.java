package org.springframework.amqp.core;
public class QueueBuilder {
    public static QueueBuilder durable(String name) { return new QueueBuilder(); }
    public QueueBuilder quorum() { return this; }
    public Queue build() { return new Queue(""); }
}
