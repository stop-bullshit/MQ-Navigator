package org.springframework.amqp.core;
public class ExchangeBuilder {
    public static ExchangeBuilder topicExchange(String name) { return new ExchangeBuilder(); }
    public static ExchangeBuilder fanoutExchange(String name) { return new ExchangeBuilder(); }
    public static ExchangeBuilder directExchange(String name) { return new ExchangeBuilder(); }
    public static ExchangeBuilder headersExchange(String name) { return new ExchangeBuilder(); }
    public ExchangeBuilder durable(boolean durable) { return this; }
    public Exchange build() { return new Exchange(); }
}
