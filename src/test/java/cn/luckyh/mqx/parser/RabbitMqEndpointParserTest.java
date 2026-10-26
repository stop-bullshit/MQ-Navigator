package cn.luckyh.mqx.parser;

import com.intellij.psi.PsiIdentifier;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import cn.luckyh.mqx.model.MqDestination;
import cn.luckyh.mqx.model.MqEndpoint;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

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

    public void testDelegatingOverloadsKeepBothProducerDestinations() {
        myFixture.addFileToProject("OverloadedPublisher.java", """
                class OverloadedPublisher {
                    org.springframework.amqp.rabbit.core.RabbitTemplate template;
                    void publish(String exchange, String key, Object body) {
                        template.convertAndSend(exchange, key, body);
                    }
                    void publish(String exchange, Object body) {
                        this.publish(exchange, "", body);
                    }
                }
                """);
        myFixture.configureByText("OverloadCase.java", """
                class OverloadCase {
                    OverloadedPublisher publisher;
                    void sync(java.util.List<String> items) {
                        String exchange = "business.exchange";
                        items.forEach(body -> {
                            publisher.publish("risk.exchange", "risk.route", body);
                            publisher.publish(exchange, body);
                        });
                    }
                }
                """);
        RabbitMqEndpointParser parser = new RabbitMqEndpointParser(new MqValueResolver(getProject()));
        var calls = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                        myFixture.getFile(), PsiIdentifier.class).stream()
                .filter(item -> "publish".equals(item.getText())).toList();

        assertEquals(2, calls.size());
        MqEndpoint risk = parser.parseAnchor(calls.get(0), new LinkedHashSet<>()).orElseThrow();
        MqEndpoint business = parser.parseAnchor(calls.get(1), new LinkedHashSet<>()).orElseThrow();
        assertEquals("risk.exchange", risk.destination().exchange());
        assertEquals("risk.route", risk.destination().routingKey());
        assertEquals("business.exchange", business.destination().exchange());
        assertEquals("", business.destination().routingKey());
        assertEquals(MqEndpoint.ResolutionState.STATIC, business.resolutionState());
        List<MqEndpoint> producers = collectEndpoints().stream()
                .filter(endpoint -> endpoint.role() == MqEndpoint.Role.PRODUCER).toList();
        assertEquals(2, producers.size());
        assertTrue(producers.stream().allMatch(endpoint -> "OverloadCase.java".equals(endpoint.fileName())));
    }

    public void testNestedWrappersMapReorderedParametersAndStopCycles() {
        myFixture.addFileToProject("NestedPublisher.java", """
                class NestedPublisher {
                    org.springframework.amqp.rabbit.core.RabbitTemplate template;
                    void publish(Object body, String key, String exchange) {
                        forward(exchange, body, key);
                    }
                    void forward(String exchange, Object body, String key) {
                        retry(exchange, key, body);
                        template.convertAndSend(exchange, key, body);
                    }
                    void retry(String exchange, String key, Object body) {
                        forward(exchange, body, key);
                    }
                }
                """);
        myFixture.configureByText("NestedCase.java", """
                class NestedCase {
                    NestedPublisher publisher;
                    void sync() { publisher.publish(new Object(), "nested.route", "nested.exchange"); }
                }
                """);

        MqEndpoint endpoint = new RabbitMqEndpointParser(new MqValueResolver(getProject()))
                .parseAnchor(identifier("publish"), new LinkedHashSet<>()).orElseThrow();

        assertEquals("nested.exchange", endpoint.destination().exchange());
        assertEquals("nested.route", endpoint.destination().routingKey());
        List<MqEndpoint> producers = collectEndpoints().stream()
                .filter(item -> item.role() == MqEndpoint.Role.PRODUCER).toList();
        assertEquals(1, producers.size());
        assertEquals("NestedCase.java", producers.get(0).fileName());
    }

    public void testRepeatedNonMqBranchesDoNotExpandExponentially() {
        StringBuilder source = new StringBuilder("class RepeatedCalls {\n");
        source.append("void left0(String value) {} void right0(String value) {}\n");
        for (int level = 1; level <= 20; level++) {
            source.append("void left").append(level).append("(String value) { left")
                    .append(level - 1).append("(value); right").append(level - 1).append("(value); }\n");
            source.append("void right").append(level).append("(String value) { left")
                    .append(level - 1).append("(value); right").append(level - 1).append("(value); }\n");
        }
        myFixture.addFileToProject("RepeatedCalls.java", source.append('}').toString());
        myFixture.configureByText("RepeatedCase.java", """
                class RepeatedCase {
                    RepeatedCalls calls;
                    void run() { calls.left20("value"); }
                }
                """);
        RabbitMqEndpointParser parser = new RabbitMqEndpointParser(new MqValueResolver(getProject()));
        PsiIdentifier sourceCall = identifier("left20");
        long started = System.nanoTime();

        assertTrue(parser.parseAnchor(sourceCall, new LinkedHashSet<>()).isEmpty());
        assertTrue("Non-MQ call graph must not be expanded once per execution path",
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5_000);
    }

    public void testRepeatedMqBranchesDoNotExpandExponentially() {
        StringBuilder source = new StringBuilder("class RepeatedMqCalls {\n");
        source.append("org.springframework.amqp.rabbit.core.RabbitTemplate template;\n");
        source.append("void left0(String value) { template.convertAndSend(value, \"left\", new Object()); }\n");
        source.append("void right0(String value) { template.convertAndSend(value, \"right\", new Object()); }\n");
        for (int level = 1; level <= 20; level++) {
            source.append("void left").append(level).append("(String value) { left")
                    .append(level - 1).append("(value); right").append(level - 1).append("(value); }\n");
            source.append("void right").append(level).append("(String value) { left")
                    .append(level - 1).append("(value); right").append(level - 1).append("(value); }\n");
        }
        myFixture.addFileToProject("RepeatedMqCalls.java", source.append('}').toString());
        myFixture.configureByText("RepeatedMqCase.java", """
                class RepeatedMqCase {
                    RepeatedMqCalls calls;
                    void run() { calls.left20("repeated.exchange"); }
                }
                """);
        RabbitMqEndpointParser parser = new RabbitMqEndpointParser(new MqValueResolver(getProject()));
        PsiIdentifier sourceCall = identifier("left20");
        long started = System.nanoTime();

        MqEndpoint endpoint = parser.parseAnchor(sourceCall, new LinkedHashSet<>()).orElseThrow();

        assertEquals("repeated.exchange", endpoint.destination().exchange());
        assertTrue("MQ call graph must not be expanded once per execution path",
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5_000);
    }

    public void testNonMqAndPayloadOnlyCallsAreNotProducerWrappers() {
        myFixture.addFileToProject("OtherMethods.java", """
                class OtherMethods {
                    org.springframework.amqp.rabbit.core.RabbitTemplate template;
                    void notMq(String value) { notMq(value); }
                    void publish(String exchange, String key, Object body) {
                        template.convertAndSend(exchange, key, body);
                    }
                    void business(String body) { publish("fixed.exchange", "fixed.route", body); }
                }
                """);
        myFixture.configureByText("NonMqCase.java", """
                class NonMqCase {
                    OtherMethods methods;
                    void run() {
                        methods.notMq("value");
                        methods.business("body");
                        methods.missing("unknown");
                    }
                }
                """);
        RabbitMqEndpointParser parser = new RabbitMqEndpointParser(new MqValueResolver(getProject()));

        assertTrue(parser.parseAnchor(identifier("notMq"), new LinkedHashSet<>()).isEmpty());
        assertTrue(parser.parseAnchor(identifier("business"), new LinkedHashSet<>()).isEmpty());
        assertTrue(parser.parseAnchor(identifier("missing"), new LinkedHashSet<>()).isEmpty());
        List<MqEndpoint> producers = collectEndpoints().stream()
                .filter(endpoint -> endpoint.role() == MqEndpoint.Role.PRODUCER).toList();
        assertEquals(1, producers.size());
        assertEquals("business", producers.get(0).memberName());
    }

    public void testQueueBuilderDurableCreatesQueueDeclarationEndpoint() {
        myFixture.addFileToProject("org/example/common/constant/DocumentQueueConstants.java", """
                package org.example.common.constant;
                public class DocumentQueueConstants {
                    public static final String DOCUMENTS_QUEUE = "documents.queue";
                }
                """);
        myFixture.configureByText("DocumentQueueConfiguration.java", """
                import org.example.common.constant.DocumentQueueConstants;
                class DocumentQueueConfiguration {
                    org.springframework.amqp.rabbit.core.RabbitAdmin admin;
                    org.springframework.amqp.core.Queue documentsQueue() {
                        org.springframework.amqp.core.Queue queue =
                            org.springframework.amqp.core.QueueBuilder.durable(
                                DocumentQueueConstants.DOCUMENTS_QUEUE).quorum().build();
                        admin.declareQueue(queue);
                        return queue;
                    }
                }
                """);

        MqEndpoint endpoint = new RabbitMqEndpointParser(new MqValueResolver(getProject()))
                .parseAnchor(identifier("durable"), new LinkedHashSet<>())
                .orElseThrow();

        assertEquals(MqEndpoint.Role.DECLARATION, endpoint.role());
        assertEquals("documents.queue", endpoint.destination().queue());
        assertEquals("documentsQueue", endpoint.memberName());
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

    public void testQueueBuilderMapArgumentsExposeDelayQueueSettings() {
        myFixture.addFileToProject("QueueConstants.java", """
                class QueueConstants {
                    static final String QUEUE = "orders.delay.queue";
                    static final String DEAD_EXCHANGE = "orders.dead.exchange";
                    static final long DELAY_TIME = 300000L;
                }
                """);
        myFixture.configureByText("DelayQueueConfiguration.java", """
                import java.util.HashMap;
                import java.util.Map;
                class DelayQueueConfiguration {
                    org.springframework.amqp.core.Queue queue() {
                        Map<String, Object> args = new HashMap<>();
                        args.put("x-dead-letter-exchange", QueueConstants.DEAD_EXCHANGE);
                        args.put("x-message-ttl", QueueConstants.DELAY_TIME);
                        args.put("x-quorum-initial-group-size", 3);
                        return org.springframework.amqp.core.QueueBuilder.durable(QueueConstants.QUEUE)
                            .quorum().withArguments(args).build();
                    }
                }
                """);

        MqEndpoint endpoint = parseQueueDeclaration();
        MqDestination.RabbitQueueSettings settings = endpoint.destination().rabbitQueueSettings();

        assertNotNull(settings);
        assertEquals(MqDestination.RabbitQueueKind.DELAY, settings.kind());
        assertEquals("orders.dead.exchange", settings.deadLetterExchange());
        assertFalse(settings.deadLetterRoutingKeyConfigured());
        assertEquals(Long.valueOf(300000L), settings.messageTtlMillis());
        assertTrue(settings.diagnostics().isEmpty());
    }

    public void testQueueBuilderSingleArgumentsExposeDeadLetterSettings() {
        myFixture.configureByText("DeadLetterQueueConfiguration.java", """
                class DeadLetterQueueConfiguration {
                    org.springframework.amqp.core.Queue queue() {
                        return org.springframework.amqp.core.QueueBuilder.durable("ocr.queue")
                            .withArgument("x-dead-letter-exchange", "ocr.dead.exchange")
                            .withArgument("x-dead-letter-routing-key", "ocr.dead")
                            .build();
                    }
                }
                """);

        MqDestination.RabbitQueueSettings settings =
                parseQueueDeclaration().destination().rabbitQueueSettings();

        assertNotNull(settings);
        assertEquals(MqDestination.RabbitQueueKind.DEAD_LETTER_SOURCE, settings.kind());
        assertEquals("ocr.dead.exchange", settings.deadLetterExchange());
        assertTrue(settings.deadLetterRoutingKeyConfigured());
        assertEquals("ocr.dead", settings.deadLetterRoutingKey());
        assertFalse(settings.messageTtlConfigured());
    }

    public void testQueueBuilderMapOfAndZeroTtlRemainStatic() {
        myFixture.configureByText("MapOfQueueConfiguration.java", """
                import java.util.Map;
                class MapOfQueueConfiguration {
                    org.springframework.amqp.core.Queue queue() {
                        return org.springframework.amqp.core.QueueBuilder.durable("instant.queue")
                            .withArguments(Map.of(
                                "x-dead-letter-exchange", "instant.dead.exchange",
                                "x-dead-letter-routing-key", "",
                                "x-message-ttl", 0L))
                            .build();
                    }
                }
                """);

        MqDestination.RabbitQueueSettings settings =
                parseQueueDeclaration().destination().rabbitQueueSettings();

        assertNotNull(settings);
        assertEquals(MqDestination.RabbitQueueKind.DELAY, settings.kind());
        assertEquals("", settings.deadLetterRoutingKey());
        assertEquals(Long.valueOf(0L), settings.messageTtlMillis());
        assertTrue(settings.diagnostics().isEmpty());
    }

    public void testStaticMapAndSingleArgumentsCoverTenSamplesEach() {
        StringBuilder source = new StringBuilder("import java.util.Map;\nclass StaticSpecialQueueCases {\n");
        for (int index = 0; index < 10; index++) {
            long mapTtl = index < 5 ? 0L : index;
            source.append("org.springframework.amqp.core.Queue map").append(index).append("() { return ")
                    .append("org.springframework.amqp.core.QueueBuilder.durable(\"map.queue.")
                    .append(index).append("\").withArguments(Map.of(")
                    .append("\"x-dead-letter-exchange\", \"map.dead.").append(index).append("\", ")
                    .append("\"x-dead-letter-routing-key\", \"map.key.").append(index).append("\", ")
                    .append("\"x-message-ttl\", ").append(mapTtl).append("L)).build(); }\n");
            source.append("org.springframework.amqp.core.Queue single").append(index).append("() { return ")
                    .append("org.springframework.amqp.core.QueueBuilder.durable(\"single.queue.")
                    .append(index).append("\").withArgument(\"x-dead-letter-exchange\", \"single.dead.")
                    .append(index).append("\").withArgument(\"x-dead-letter-routing-key\", \"single.key.")
                    .append(index).append("\").withArgument(\"x-message-ttl\", ")
                    .append(index).append("L).build(); }\n");
        }
        source.append('}');
        myFixture.configureByText("StaticSpecialQueueCases.java", source.toString());

        Map<String, MqDestination.RabbitQueueSettings> settingsByQueue = new LinkedHashMap<>();
        for (MqEndpoint endpoint : collectEndpoints()) {
            if (endpoint.role() == MqEndpoint.Role.DECLARATION) {
                settingsByQueue.put(endpoint.destination().queue(), endpoint.destination().rabbitQueueSettings());
            }
        }

        assertEquals(20, settingsByQueue.size());
        for (int index = 0; index < 10; index++) {
            MqDestination.RabbitQueueSettings mapSettings = settingsByQueue.get("map.queue." + index);
            assertNotNull(mapSettings);
            assertEquals("map.dead." + index, mapSettings.deadLetterExchange());
            assertEquals("map.key." + index, mapSettings.deadLetterRoutingKey());
            assertEquals(Long.valueOf(index < 5 ? 0L : index), mapSettings.messageTtlMillis());

            MqDestination.RabbitQueueSettings singleSettings = settingsByQueue.get("single.queue." + index);
            assertNotNull(singleSettings);
            assertEquals("single.dead." + index, singleSettings.deadLetterExchange());
            assertEquals("single.key." + index, singleSettings.deadLetterRoutingKey());
            assertEquals(Long.valueOf(index), singleSettings.messageTtlMillis());
        }
    }

    public void testDynamicAndInvalidSpecialArgumentsKeepQueueVisible() {
        myFixture.configureByText("DynamicQueueConfiguration.java", """
                class DynamicQueueConfiguration {
                    String exchangeName() { return System.getProperty("dead.exchange"); }
                    org.springframework.amqp.core.Queue queue() {
                        return org.springframework.amqp.core.QueueBuilder.durable("dynamic.queue")
                            .withArgument("x-dead-letter-exchange", exchangeName())
                            .withArgument("x-message-ttl", "five-minutes")
                            .build();
                    }
                }
                """);

        MqEndpoint endpoint = parseQueueDeclaration();
        MqDestination.RabbitQueueSettings settings = endpoint.destination().rabbitQueueSettings();

        assertTrue(endpoint.isResolved());
        assertNotNull(settings);
        assertTrue(settings.deadLetterExchangeConfigured());
        assertNull(settings.deadLetterExchange());
        assertTrue(settings.messageTtlConfigured());
        assertNull(settings.messageTtlMillis());
        assertEquals(MqDestination.RabbitQueueKind.DELAY, settings.kind());
        assertTrue(settings.diagnostics().stream().anyMatch(message -> message.contains("x-dead-letter-exchange")));
        assertTrue(settings.diagnostics().stream().anyMatch(message -> message.contains("x-message-ttl")));
    }

    public void testConditionalMapValueIsReportedAsDynamic() {
        myFixture.configureByText("ConditionalQueueConfiguration.java", """
                import java.util.HashMap;
                import java.util.Map;
                class ConditionalQueueConfiguration {
                    org.springframework.amqp.core.Queue queue(boolean delayed) {
                        Map<String, Object> args = new HashMap<>();
                        args.put("x-dead-letter-exchange", "conditional.dead.exchange");
                        if (delayed) {
                            args.put("x-message-ttl", 1000L);
                        }
                        return org.springframework.amqp.core.QueueBuilder.durable("conditional.queue")
                            .withArguments(args).build();
                    }
                }
                """);

        MqDestination.RabbitQueueSettings settings =
                parseQueueDeclaration().destination().rabbitQueueSettings();

        assertNotNull(settings);
        assertEquals("conditional.dead.exchange", settings.deadLetterExchange());
        assertTrue(settings.messageTtlConfigured());
        assertNull(settings.messageTtlMillis());
        assertTrue(settings.diagnostics().stream().anyMatch(message -> message.contains("x-message-ttl")));
    }

    public void testDynamicSpecialValuesCoverFiveSamplesPerCategory() {
        StringBuilder source = new StringBuilder("""
                import java.util.HashMap;
                import java.util.Map;
                class DynamicSpecialQueueCases {
                    Object unknown() { return new Object(); }
                """);
        for (int index = 0; index < 5; index++) {
            source.append("org.springframework.amqp.core.Queue dynamicExchange").append(index)
                    .append("() { return org.springframework.amqp.core.QueueBuilder.durable(\"dynamic.exchange.")
                    .append(index).append("\").withArgument(\"x-dead-letter-exchange\", unknown()).build(); }\n");
            source.append("org.springframework.amqp.core.Queue dynamicKey").append(index)
                    .append("() { return org.springframework.amqp.core.QueueBuilder.durable(\"dynamic.key.")
                    .append(index).append("\").withArgument(\"x-dead-letter-exchange\", \"dead.exchange\")")
                    .append(".withArgument(\"x-dead-letter-routing-key\", unknown()).build(); }\n");
            source.append("org.springframework.amqp.core.Queue dynamicTtl").append(index)
                    .append("() { return org.springframework.amqp.core.QueueBuilder.durable(\"dynamic.ttl.")
                    .append(index).append("\").withArgument(\"x-message-ttl\", unknown()).build(); }\n");
            source.append("org.springframework.amqp.core.Queue invalidTtl").append(index)
                    .append("() { return org.springframework.amqp.core.QueueBuilder.durable(\"invalid.ttl.")
                    .append(index).append("\").withArgument(\"x-message-ttl\", -1L).build(); }\n");
            source.append("org.springframework.amqp.core.Queue nonNumericTtl").append(index)
                    .append("() { return org.springframework.amqp.core.QueueBuilder.durable(\"non.numeric.ttl.")
                    .append(index).append("\").withArgument(\"x-message-ttl\", \"invalid\").build(); }\n");
            source.append("org.springframework.amqp.core.Queue branchMap").append(index)
                    .append("(boolean delayed) { Map<String, Object> args = new HashMap<>(); ")
                    .append("args.put(\"x-dead-letter-exchange\", \"dead.exchange\"); ")
                    .append("if (delayed) { args.put(\"x-message-ttl\", 1000L); } return ")
                    .append("org.springframework.amqp.core.QueueBuilder.durable(\"branch.map.")
                    .append(index).append("\").withArguments(args).build(); }\n");
        }
        source.append('}');
        myFixture.configureByText("DynamicSpecialQueueCases.java", source.toString());

        Map<String, MqDestination.RabbitQueueSettings> settingsByQueue = new LinkedHashMap<>();
        for (MqEndpoint endpoint : collectEndpoints()) {
            if (endpoint.role() == MqEndpoint.Role.DECLARATION) {
                settingsByQueue.put(endpoint.destination().queue(), endpoint.destination().rabbitQueueSettings());
            }
        }

        assertEquals(30, settingsByQueue.size());
        for (int index = 0; index < 5; index++) {
            MqDestination.RabbitQueueSettings exchange = settingsByQueue.get("dynamic.exchange." + index);
            assertNotNull(exchange);
            assertNull(exchange.deadLetterExchange());
            assertTrue(exchange.diagnostics().stream().anyMatch(message -> message.contains("x-dead-letter-exchange")));

            MqDestination.RabbitQueueSettings routingKey = settingsByQueue.get("dynamic.key." + index);
            assertNotNull(routingKey);
            assertNull(routingKey.deadLetterRoutingKey());
            assertTrue(routingKey.diagnostics().stream().anyMatch(message -> message.contains("x-dead-letter-routing-key")));

            MqDestination.RabbitQueueSettings dynamicTtl = settingsByQueue.get("dynamic.ttl." + index);
            assertNotNull(dynamicTtl);
            assertNull(dynamicTtl.messageTtlMillis());
            assertTrue(dynamicTtl.diagnostics().stream().anyMatch(message -> message.contains("x-message-ttl")));

            MqDestination.RabbitQueueSettings invalidTtl = settingsByQueue.get("invalid.ttl." + index);
            assertNotNull(invalidTtl);
            assertNull(invalidTtl.messageTtlMillis());
            assertTrue(invalidTtl.diagnostics().stream().anyMatch(message -> message.contains("x-message-ttl")));

            MqDestination.RabbitQueueSettings nonNumericTtl = settingsByQueue.get("non.numeric.ttl." + index);
            assertNotNull(nonNumericTtl);
            assertNull(nonNumericTtl.messageTtlMillis());
            assertTrue(nonNumericTtl.diagnostics().stream().anyMatch(message -> message.contains("x-message-ttl")));

            MqDestination.RabbitQueueSettings branchMap = settingsByQueue.get("branch.map." + index);
            assertNotNull(branchMap);
            assertNull(branchMap.messageTtlMillis());
            assertTrue(branchMap.diagnostics().stream().anyMatch(message -> message.contains("x-message-ttl")));
        }
    }

    public void testQueueClassificationCoversTenSamplesPerKind() {
        StringBuilder source = new StringBuilder("class QueueClassificationCases {\n");
        for (int index = 0; index < 10; index++) {
            source.append("org.springframework.amqp.core.Queue standard").append(index).append("() { return ")
                    .append("org.springframework.amqp.core.QueueBuilder.durable(\"standard.")
                    .append(index).append("\").withArgument(\"x-message-ttl\", ")
                    .append(index).append("L).build(); }\n");
            source.append("org.springframework.amqp.core.Queue dead").append(index).append("() { return ")
                    .append("org.springframework.amqp.core.QueueBuilder.durable(\"dead.")
                    .append(index).append("\").withArgument(\"x-dead-letter-exchange\", \"dead.exchange\")")
                    .append(".build(); }\n");
            source.append("org.springframework.amqp.core.Queue delay").append(index).append("() { return ")
                    .append("org.springframework.amqp.core.QueueBuilder.durable(\"delay.")
                    .append(index).append("\").withArgument(\"x-dead-letter-exchange\", \"dead.exchange\")")
                    .append(".withArgument(\"x-message-ttl\", ").append(index).append("L).build(); }\n");
        }
        source.append('}');
        myFixture.configureByText("QueueClassificationCases.java", source.toString());

        List<MqEndpoint> declarations = collectEndpoints().stream()
                .filter(endpoint -> endpoint.role() == MqEndpoint.Role.DECLARATION)
                .toList();

        assertEquals(30, declarations.size());
        assertEquals(10, declarations.stream().filter(endpoint ->
                endpoint.destination().rabbitQueueSettings().kind()
                        == MqDestination.RabbitQueueKind.STANDARD).count());
        assertEquals(10, declarations.stream().filter(endpoint ->
                endpoint.destination().rabbitQueueSettings().kind()
                        == MqDestination.RabbitQueueKind.DEAD_LETTER_SOURCE).count());
        assertEquals(10, declarations.stream().filter(endpoint ->
                endpoint.destination().rabbitQueueSettings().kind()
                        == MqDestination.RabbitQueueKind.DELAY).count());
    }

    public void testMalformedSpecialArgumentsAreIsolatedAcrossFiveQueues() {
        myFixture.configureByText("MalformedSpecialQueueCases.java", """
                import java.util.Collections;
                import java.util.Map;
                class MalformedSpecialQueueCases {
                    Object unknown() { return new Object(); }
                    Map<String, Object> external() { return Collections.emptyMap(); }
                    org.springframework.amqp.core.Queue first(String key) {
                        return org.springframework.amqp.core.QueueBuilder.durable("first").withArgument(key, "x").build();
                    }
                    org.springframework.amqp.core.Queue second() {
                        return org.springframework.amqp.core.QueueBuilder.durable("second").withArguments(external()).build();
                    }
                    org.springframework.amqp.core.Queue third() {
                        return org.springframework.amqp.core.QueueBuilder.durable("third")
                            .withArgument("x-dead-letter-exchange", unknown()).build();
                    }
                    org.springframework.amqp.core.Queue fourth() {
                        return org.springframework.amqp.core.QueueBuilder.durable("fourth")
                            .withArgument("x-dead-letter-routing-key", unknown()).build();
                    }
                    org.springframework.amqp.core.Queue fifth() {
                        return org.springframework.amqp.core.QueueBuilder.durable("fifth")
                            .withArgument("x-message-ttl", unknown()).build();
                    }
                }
                """);

        List<MqEndpoint> declarations = collectEndpoints().stream()
                .filter(endpoint -> endpoint.role() == MqEndpoint.Role.DECLARATION)
                .toList();

        assertEquals(5, declarations.size());
        assertTrue(declarations.stream().allMatch(endpoint -> !endpoint.destination().queue().isBlank()));
    }

    private MqEndpoint parseQueueDeclaration() {
        return new RabbitMqEndpointParser(new MqValueResolver(getProject()))
                .parseAnchor(identifier("durable"), new LinkedHashSet<>())
                .orElseThrow();
    }

    private List<MqEndpoint> collectEndpoints() {
        return new RabbitMqEndpointParser(new MqValueResolver(getProject()))
                .collectEndpoints(getProject(), com.intellij.psi.search.GlobalSearchScope.projectScope(getProject()),
                        new LinkedHashSet<>());
    }

    private PsiIdentifier identifier(String text) {
        return com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                        myFixture.getFile(), PsiIdentifier.class).stream()
                .filter(identifier -> text.equals(identifier.getText()))
                .findFirst().orElseThrow();
    }
}
