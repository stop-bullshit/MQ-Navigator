package org.springframework.amqp.core;
public class BindingBuilder {
    public static DestinationConfigurer bind(Queue queue) { return new DestinationConfigurer(); }

    public static class DestinationConfigurer {
        public ExchangeConfigurer to(Exchange exchange) { return new ExchangeConfigurer(); }
    }

    public static class ExchangeConfigurer {
        public Binding with(String routingKey) { return new Binding(); }
    }
}
