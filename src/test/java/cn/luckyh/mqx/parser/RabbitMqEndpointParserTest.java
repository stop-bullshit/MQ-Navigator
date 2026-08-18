package cn.luckyh.mqx.parser;

import com.intellij.psi.PsiIdentifier;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import cn.luckyh.mqx.model.MqEndpoint;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

public class RabbitMqEndpointParserTest extends BasePlatformTestCase {

    @Override
    protected String getTestDataPath() {
        return Path.of("src/test/testData").toAbsolutePath().toString();
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.copyDirectoryToProject("stubs", "");
    }

    public void testProducerAndQueueListener() {
        myFixture.configureByText("RabbitCase.java", """
                class RabbitCase {
                    org.springframework.amqp.rabbit.core.RabbitTemplate template;
                    void publish() { template.convertAndSend("orders", new Object()); }
                    @org.springframework.amqp.rabbit.annotation.RabbitListener(queues = "orders")
                    void consume(Object value) {}
                }
                """);
        RabbitMqEndpointParser parser = new RabbitMqEndpointParser(new MqValueResolver(getProject()));
        MqEndpoint producer = parser.parseAnchor(identifier("convertAndSend"), new LinkedHashSet<>()).orElseThrow();
        MqEndpoint consumer = parser.parseAnchor(identifier("consume"), new LinkedHashSet<>()).orElseThrow();
        assertEquals(MqEndpoint.Role.PRODUCER, producer.role());
        assertEquals("orders", producer.destination().routingKey());
        assertEquals(MqEndpoint.Role.CONSUMER, consumer.role());
        assertEquals("orders", consumer.destination().queue());
    }

    public void testIncompleteRabbitFileIsIsolated() {
        myFixture.configureByText("BrokenRabbit.java", "class BrokenRabbit { void x() { template.send(");
        assertTrue(new RabbitMqEndpointParser(new MqValueResolver(getProject()))
                .parseAnchor(identifier("send"), new LinkedHashSet<>()).isEmpty());
    }

    public void testQueueBuilderDurableCreatesQueueDeclarationEndpoint() {
        myFixture.addFileToProject("com/dhl/common/constant/CdtcDockingQueueConstants.java", """
                package com.dhl.common.constant;
                public class CdtcDockingQueueConstants {
                    public static final String ACCOMPANYING_DOCUMENTS_QUEUE = "cdtc.ib.documents";
                }
                """);
        myFixture.configureByText("CdtcQueueMqConfiguration.java", """
                import com.dhl.common.constant.CdtcDockingQueueConstants;
                class CdtcQueueMqConfiguration {
                    org.springframework.amqp.rabbit.core.RabbitAdmin admin;
                    org.springframework.amqp.core.Queue accompanyingDocumentsQueue() {
                        org.springframework.amqp.core.Queue queue =
                            org.springframework.amqp.core.QueueBuilder.durable(
                                CdtcDockingQueueConstants.ACCOMPANYING_DOCUMENTS_QUEUE).quorum().build();
                        admin.declareQueue(queue);
                        return queue;
                    }
                }
                """);

        MqEndpoint endpoint = new RabbitMqEndpointParser(new MqValueResolver(getProject()))
                .parseAnchor(identifier("durable"), new LinkedHashSet<>())
                .orElseThrow();

        assertEquals(MqEndpoint.Role.DECLARATION, endpoint.role());
        assertEquals("cdtc.ib.documents", endpoint.destination().queue());
        assertEquals("accompanyingDocumentsQueue", endpoint.memberName());
    }

    public void testCollectEndpointsKeepsRabbitBindingSource() {
        myFixture.configureByText("RabbitBindingCase.java", """
                class RabbitBindingCase {
                    org.springframework.amqp.core.Queue queue() {
                        return org.springframework.amqp.core.QueueBuilder.durable("orders.queue").build();
                    }
                    org.springframework.amqp.core.TopicExchange exchange() {
                        return (org.springframework.amqp.core.TopicExchange) org.springframework.amqp.core.ExchangeBuilder
                            .topicExchange("orders.exchange").durable(true).build();
                    }
                    void binding() {
                        org.springframework.amqp.core.Binding binding = org.springframework.amqp.core.BindingBuilder
                            .bind(queue()).to(exchange()).with("orders.created");
                    }
                }
                """);

        List<MqEndpoint> endpoints = new RabbitMqEndpointParser(new MqValueResolver(getProject()))
                .collectEndpoints(getProject(), com.intellij.psi.search.GlobalSearchScope.projectScope(getProject()),
                        new LinkedHashSet<>());
        MqEndpoint binding = endpoints.stream()
                .filter(endpoint -> endpoint.role() == MqEndpoint.Role.BINDING)
                .findFirst().orElseThrow();
        MqEndpoint exchange = endpoints.stream()
                .filter(endpoint -> endpoint.role() == MqEndpoint.Role.EXCHANGE_DECLARATION)
                .findFirst().orElseThrow();

        assertEquals("orders.exchange", binding.destination().exchange());
        assertEquals("orders.queue", binding.destination().queue());
        assertEquals(List.of("orders.created"), binding.destination().bindingKeys());
        assertEquals("binding", binding.memberName());
        assertEquals("bind", binding.sourcePointer().getElement().getText());
        assertEquals("orders.exchange", exchange.destination().exchange());
        assertEquals("topic", exchange.destination().exchangeType());
        assertEquals("exchange", exchange.memberName());
    }

    private PsiIdentifier identifier(String text) {
        return com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                        myFixture.getFile(), PsiIdentifier.class).stream()
                .filter(identifier -> text.equals(identifier.getText()))
                .findFirst().orElseThrow();
    }
}
