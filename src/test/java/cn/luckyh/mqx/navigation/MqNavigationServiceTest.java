package cn.luckyh.mqx.navigation;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiIdentifier;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import cn.luckyh.mqx.model.MqEndpoint;

import java.nio.file.Path;
import java.util.concurrent.Future;

public class MqNavigationServiceTest extends BasePlatformTestCase {

    @Override
    protected String getTestDataPath() {
        return Path.of("src/test/testData").toAbsolutePath().toString();
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.copyDirectoryToProject("stubs", "");
    }

    public void testProducerFindsSingleConsumerOffEdt() throws Exception {
        myFixture.configureByText("NavigationCase.java", """
                class NavigationCase {
                    org.springframework.kafka.core.KafkaTemplate<String, Object> template;
                    void publish() { template.send("orders", new Object()); }
                    @org.springframework.kafka.annotation.KafkaListener(topics = "orders")
                    void consume(Object value) {}
                }
                """);
        PsiIdentifier source = identifier("send");
        Future<MqNavigationService.NavigationOutcome> future = ApplicationManager.getApplication()
                .executeOnPooledThread(() -> ProgressManager.getInstance().runProcess(
                        () -> ReadAction.compute(
                                () -> MqNavigationService.getInstance(getProject()).findTargets(source)),
                        new EmptyProgressIndicator()));
        MqNavigationService.NavigationOutcome outcome = future.get();
        assertEquals(MqNavigationService.NavigationStatus.ONE_MATCH, outcome.status());
        assertEquals("consume", outcome.targets().get(0).memberName());
    }

    public void testConsumerFindsSingleProducerOffEdt() throws Exception {
        myFixture.configureByText("ReverseNavigationCase.java", """
                class ReverseNavigationCase {
                    org.springframework.kafka.core.KafkaTemplate<String, Object> template;
                    void publish() { template.send("orders", new Object()); }
                    @org.springframework.kafka.annotation.KafkaListener(topics = "orders")
                    void consume(Object value) {}
                }
                """);
        PsiIdentifier source = identifier("consume");
        Future<MqNavigationService.NavigationOutcome> future = ApplicationManager.getApplication()
                .executeOnPooledThread(() -> ProgressManager.getInstance().runProcess(
                        () -> ReadAction.compute(
                                () -> MqNavigationService.getInstance(getProject()).findTargets(source)),
                        new EmptyProgressIndicator()));
        MqNavigationService.NavigationOutcome outcome = future.get();
        assertEquals(MqNavigationService.NavigationStatus.ONE_MATCH, outcome.status());
        assertEquals("publish", outcome.targets().get(0).memberName());
    }

    public void testConsumerWithoutProducerReportsNoMatch() throws Exception {
        myFixture.configureByText("NoProducerCase.java", """
                class NoProducerCase {
                    @org.springframework.kafka.annotation.KafkaListener(topics = "orders")
                    void consume(Object value) {}
                }
                """);
        PsiIdentifier source = identifier("consume");
        Future<MqNavigationService.NavigationOutcome> future = ApplicationManager.getApplication()
                .executeOnPooledThread(() -> ProgressManager.getInstance().runProcess(
                        () -> ReadAction.compute(
                                () -> MqNavigationService.getInstance(getProject()).findTargets(source)),
                        new EmptyProgressIndicator()));
        assertEquals(MqNavigationService.NavigationStatus.NO_MATCH, future.get().status());
    }

    public void testMultipleConsumersAreStableAndComplete() throws Exception {
        myFixture.configureByText("MultipleConsumers.java", """
                class MultipleConsumers {
                    org.springframework.kafka.core.KafkaTemplate<String, Object> template;
                    void publish() { template.send("orders", new Object()); }
                    @org.springframework.kafka.annotation.KafkaListener(topics = "orders", groupId = "zeta")
                    void consumeZ(Object value) {}
                    @org.springframework.kafka.annotation.KafkaListener(topics = "orders", groupId = "alpha")
                    void consumeA(Object value) {}
                }
                """);
        PsiIdentifier source = identifier("send");
        Future<MqNavigationService.NavigationOutcome> future = ApplicationManager.getApplication()
                .executeOnPooledThread(() -> ProgressManager.getInstance().runProcess(
                        () -> ReadAction.compute(
                                () -> MqNavigationService.getInstance(getProject()).findTargets(source)),
                        new EmptyProgressIndicator()));
        MqNavigationService.NavigationOutcome outcome = future.get();
        assertEquals(MqNavigationService.NavigationStatus.MULTIPLE_MATCHES, outcome.status());
        assertEquals(2, outcome.targets().size());
        assertEquals("consumeA", outcome.targets().get(0).memberName());
        assertEquals("consumeZ", outcome.targets().get(1).memberName());
    }

    public void testManualRefreshKeepsCurrentResult() throws Exception {
        myFixture.configureByText("RefreshCase.java", """
                class RefreshCase {
                    org.springframework.kafka.core.KafkaTemplate<String, Object> template;
                    void publish() { template.send("orders", new Object()); }
                    @org.springframework.kafka.annotation.KafkaListener(topics = "orders")
                    void consume(Object value) {}
                }
                """);
        PsiIdentifier source = identifier("send");
        MqNavigationService service = MqNavigationService.getInstance(getProject());
        service.requestRefresh();
        Future<MqNavigationService.NavigationOutcome> future = ApplicationManager.getApplication()
                .executeOnPooledThread(() -> ProgressManager.getInstance().runProcess(
                        () -> ReadAction.compute(() -> service.findTargets(source)),
                        new EmptyProgressIndicator()));
        assertEquals(MqNavigationService.NavigationStatus.ONE_MATCH, future.get().status());
    }

    public void testRabbitQueueDeclarationIsNotAConsumerNavigationTarget() throws Exception {
        myFixture.addFileToProject("org/example/common/constant/DocumentQueueConstants.java", """
                package org.example.common.constant;
                public class DocumentQueueConstants {
                    public static final String DOCUMENTS_QUEUE = "documents.queue";
                }
                """);
        myFixture.configureByText("DocumentQueueConfiguration.java", """
                import org.example.common.constant.DocumentQueueConstants;
                class DocumentQueueConfiguration {
                    org.springframework.amqp.core.Queue declareDocumentsQueue() {
                        return org.springframework.amqp.core.QueueBuilder.durable(
                            DocumentQueueConstants.DOCUMENTS_QUEUE).quorum().build();
                    }

                    @org.springframework.amqp.rabbit.annotation.RabbitListener(
                        queues = DocumentQueueConstants.DOCUMENTS_QUEUE)
                    void consumeDocuments(Object value) {}
                }
                """);
        MqNavigationService service = MqNavigationService.getInstance(getProject());

        MqNavigationService.NavigationOutcome fromDeclaration = findTargets(service, identifier("durable"));
        assertEquals(MqNavigationService.NavigationStatus.NO_MATCH, fromDeclaration.status());

        MqNavigationService.NavigationOutcome fromListener = findTargets(service, identifier("consumeDocuments"));
        assertEquals(MqNavigationService.NavigationStatus.NO_MATCH, fromListener.status());
        assertTrue(service.currentEndpoints().stream().anyMatch(endpoint ->
                endpoint.role() == MqEndpoint.Role.DECLARATION
                        && "documents.queue".equals(endpoint.destination().queue())));
    }

    public void testProducerDoesNotNavigateToQueueDeclarationWithoutConsumer() throws Exception {
        myFixture.configureByText("QueueOnlyCase.java", """
                class QueueOnlyCase {
                    org.springframework.amqp.rabbit.core.RabbitTemplate template;
                    org.springframework.amqp.core.Queue queue() {
                        return org.springframework.amqp.core.QueueBuilder.durable("queue.only").build();
                    }
                    void publish() { template.convertAndSend("queue.only", new Object()); }
                }
                """);
        MqNavigationService service = MqNavigationService.getInstance(getProject());

        MqNavigationService.NavigationOutcome outcome = findTargets(service, identifier("convertAndSend"));

        assertEquals(MqNavigationService.NavigationStatus.NO_MATCH, outcome.status());
        assertTrue(service.currentEndpoints().stream().anyMatch(endpoint ->
                endpoint.role() == MqEndpoint.Role.DECLARATION
                        && "queue.only".equals(endpoint.destination().queue())));
    }

    public void testTopicBindingConnectsBillingConsumerToActualProducer() throws Exception {
        myFixture.configureByText("BillingNavigationCase.java", """
                class BillingQueueConfiguration {
                    org.springframework.amqp.core.Queue billingInitQueue() {
                        return org.springframework.amqp.core.QueueBuilder.durable(
                            "billing.init.queue").quorum().build();
                    }
                }
                class BillingExchangeConfiguration {
                    org.springframework.amqp.core.Exchange billingExchange() {
                        return org.springframework.amqp.core.ExchangeBuilder
                            .topicExchange("billing.events").durable(true).build();
                    }
                }
                class BillingBindingConfiguration {
                    BillingQueueConfiguration queues;
                    BillingExchangeConfiguration exchanges;
                    void billingInitBinding() {
                        org.springframework.amqp.core.BindingBuilder
                            .bind(queues.billingInitQueue())
                            .to(exchanges.billingExchange()).with("#.init");
                    }
                }
                class MessagePublisher {
                    org.springframework.amqp.rabbit.core.RabbitTemplate template;
                    void publishMessage(String exchange, String routingKey, Object message) {
                        template.convertAndSend(exchange, routingKey, message);
                    }
                }
                class BillingPublishAspect {
                    MessagePublisher template;
                    void publishBilling(boolean direct) {
                        String exchange = "billing.events";
                        String routingKey = "calc.billing.init";
                        if (direct) {
                            template.publishMessage(exchange, routingKey, new Object());
                            return;
                        }
                        template.publishMessage(exchange, routingKey, new Object());
                    }
                }
                class BillingListener {
                    @org.springframework.amqp.rabbit.annotation.RabbitListener(
                        queues = "billing.init.queue")
                    void processData(Object message) {}
                }
                """);
        MqNavigationService service = MqNavigationService.getInstance(getProject());
        PsiIdentifier producer = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                        myFixture.getFile(), PsiIdentifier.class).stream()
                .filter(identifier -> "publishMessage".equals(identifier.getText()))
                .filter(identifier -> identifier.getParent() instanceof com.intellij.psi.PsiReferenceExpression)
                .filter(identifier -> {
                    var method = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
                            identifier, com.intellij.psi.PsiMethod.class);
                    return method != null && "publishBilling".equals(method.getName());
                })
                .findFirst().orElseThrow();

        MqNavigationService.NavigationOutcome fromConsumer = findTargets(service, identifier("processData"));
        MqNavigationService.NavigationOutcome fromProducer = findTargets(service, producer);

        assertEquals(MqNavigationService.NavigationStatus.MULTIPLE_MATCHES, fromConsumer.status());
        assertEquals(2, fromConsumer.targets().size());
        assertTrue(fromConsumer.targets().stream().allMatch(endpoint ->
                endpoint.role() == MqEndpoint.Role.PRODUCER
                        && "publishBilling".equals(endpoint.memberName())));
        assertEquals(MqNavigationService.NavigationStatus.ONE_MATCH, fromProducer.status());
        assertEquals("processData", fromProducer.targets().get(0).memberName());
        assertTrue(service.currentEndpoints().stream().anyMatch(endpoint ->
                endpoint.role() == MqEndpoint.Role.BINDING
                        && "#.init".equals(endpoint.destination().bindingKeys().get(0))));
    }

    public void testRabbitProducerPrefersListenerOverQueueDeclaration() throws Exception {
        myFixture.configureByText("RabbitPriorityCase.java", """
                class RabbitPriorityCase {
                    static final String QUEUE = "orders";
                    org.springframework.amqp.rabbit.core.RabbitTemplate template;
                    org.springframework.amqp.core.Queue declareOrdersQueue() {
                        return org.springframework.amqp.core.QueueBuilder.durable(QUEUE).build();
                    }
                    void publish() { template.convertAndSend(QUEUE, new Object()); }
                    @org.springframework.amqp.rabbit.annotation.RabbitListener(queues = QUEUE)
                    void consume(Object value) {}
                }
                """);

        MqNavigationService.NavigationOutcome outcome = findTargets(
                MqNavigationService.getInstance(getProject()), identifier("convertAndSend"));

        assertEquals(MqNavigationService.NavigationStatus.ONE_MATCH, outcome.status());
        assertEquals(MqEndpoint.Role.CONSUMER, outcome.targets().get(0).role());
        assertEquals("consume", outcome.targets().get(0).memberName());
    }

    public void testRabbitQueueBindingNavigatesToGenericExchangeProducer() throws Exception {
        myFixture.addFileToProject("org/example/mq/MqConstants.java", """
                package org.example.mq;
                public class MqConstants {
                    public static final String QUEUE = "orders.queue";
                    public static final String EXCHANGE = "orders.exchange";
                }
                """);
        myFixture.configureByText("RabbitTopologyCase.java", """
                import org.example.messaging.MessagePublisher;
                import org.example.mq.MqConstants;
                class RabbitTopologyCase {
                    org.springframework.amqp.rabbit.core.RabbitAdmin admin;
                    MessagePublisher publisher;

                    org.springframework.amqp.core.Queue ordersQueue() {
                        return org.springframework.amqp.core.QueueBuilder.durable(MqConstants.QUEUE).build();
                    }

                    org.springframework.amqp.core.FanoutExchange ordersExchange() {
                        return new org.springframework.amqp.core.FanoutExchange(MqConstants.EXCHANGE, true, false);
                    }

                    void declareBinding() {
                        org.springframework.amqp.core.Binding binding =
                            org.springframework.amqp.core.BindingBuilder.bind(ordersQueue()).to(ordersExchange());
                        admin.declareBinding(binding);
                    }

                    void publish() {
                        publisher.publish(MqConstants.EXCHANGE, "", new Object());
                    }

                    @org.springframework.amqp.rabbit.annotation.RabbitListener(queues = MqConstants.QUEUE)
                    void consume(Object value) {}
                }
                """);

        MqNavigationService service = MqNavigationService.getInstance(getProject());
        MqNavigationService.NavigationOutcome outcome = findTargets(service, identifier("consume"));

        assertEquals(MqNavigationService.NavigationStatus.ONE_MATCH, outcome.status());
        assertEquals("publish", outcome.targets().get(0).memberName());
        assertEquals("orders.exchange", outcome.targets().get(0).destination().exchange());
    }

    public void testRabbitQueueKeepsMultipleBindings() throws Exception {
        myFixture.addFileToProject("org/example/mq/MultiBindingConstants.java", """
                package org.example.mq;
                public class MultiBindingConstants {
                    public static final String QUEUE = "shared.queue";
                    public static final String FIRST = "first.exchange";
                    public static final String SECOND = "second.exchange";
                }
                """);
        myFixture.configureByText("RabbitMultipleBindingCase.java", """
                import org.example.messaging.MessagePublisher;
                import org.example.mq.MultiBindingConstants;
                class RabbitMultipleBindingCase {
                    org.springframework.amqp.rabbit.core.RabbitAdmin admin;
                    MessagePublisher publisher;

                    org.springframework.amqp.core.Queue sharedQueue() {
                        return org.springframework.amqp.core.QueueBuilder.durable(MultiBindingConstants.QUEUE).build();
                    }
                    org.springframework.amqp.core.FanoutExchange firstExchange() {
                        return new org.springframework.amqp.core.FanoutExchange(MultiBindingConstants.FIRST, true, false);
                    }
                    org.springframework.amqp.core.FanoutExchange secondExchange() {
                        return new org.springframework.amqp.core.FanoutExchange(MultiBindingConstants.SECOND, true, false);
                    }
                    void firstBinding() {
                        admin.declareBinding(org.springframework.amqp.core.BindingBuilder.bind(sharedQueue()).to(firstExchange()));
                    }
                    void secondBinding() {
                        admin.declareBinding(org.springframework.amqp.core.BindingBuilder.bind(sharedQueue()).to(secondExchange()));
                    }
                    void publishFirst() {
                        publisher.publish(MultiBindingConstants.FIRST, "", new Object());
                    }
                    void publishSecond() {
                        publisher.publish(MultiBindingConstants.SECOND, "", new Object());
                    }
                    @org.springframework.amqp.rabbit.annotation.RabbitListener(queues = MultiBindingConstants.QUEUE)
                    void consume(Object value) {}
                }
                """);

        MqNavigationService.NavigationOutcome outcome = findTargets(
                MqNavigationService.getInstance(getProject()), identifier("consume"));

        assertEquals(MqNavigationService.NavigationStatus.MULTIPLE_MATCHES, outcome.status());
        assertEquals(2, outcome.targets().size());
    }

    public void testRabbitTopicBindingUsesRoutingKeyAndExchangeBuilder() throws Exception {
        myFixture.addFileToProject("org/example/mq/TopicConstants.java", """
                package org.example.mq;
                public class TopicConstants {
                    public static final String QUEUE = "topic.queue";
                    public static final String EXCHANGE = "topic.exchange";
                    public static final String ROUTING_KEY = "orders.created";
                }
                """);
        myFixture.configureByText("RabbitTopicTopologyCase.java", """
                import org.example.messaging.MessagePublisher;
                import org.example.mq.TopicConstants;
                class RabbitTopicTopologyCase {
                    org.springframework.amqp.rabbit.core.RabbitAdmin admin;
                    MessagePublisher publisher;

                    org.springframework.amqp.core.Queue topicQueue() {
                        return org.springframework.amqp.core.QueueBuilder.durable(TopicConstants.QUEUE).build();
                    }
                    org.springframework.amqp.core.Exchange topicExchange() {
                        return org.springframework.amqp.core.ExchangeBuilder
                            .topicExchange(TopicConstants.EXCHANGE).durable(true).build();
                    }
                    void declareBinding() {
                        admin.declareBinding(org.springframework.amqp.core.BindingBuilder.bind(topicQueue())
                            .to(topicExchange()).with(TopicConstants.ROUTING_KEY));
                    }
                    void publish() {
                        publisher.publish(
                            TopicConstants.EXCHANGE, TopicConstants.ROUTING_KEY, new Object());
                    }
                    @org.springframework.amqp.rabbit.annotation.RabbitListener(queues = TopicConstants.QUEUE)
                    void consume(Object value) {}
                }
                """);

        MqNavigationService.NavigationOutcome outcome = findTargets(
                MqNavigationService.getInstance(getProject()), identifier("consume"));

        assertEquals(MqNavigationService.NavigationStatus.ONE_MATCH, outcome.status());
        assertEquals("publish", outcome.targets().get(0).memberName());
        assertEquals("topic.exchange", outcome.targets().get(0).destination().exchange());
        assertEquals("orders.created", outcome.targets().get(0).destination().routingKey());
    }

    public void testRabbitListenerFindsProducerThroughEnumRoutingKeyGetter() throws Exception {
        myFixture.addFileToProject("org/example/mq/OrderEventConstants.java", """
                package org.example.mq;
                public class OrderEventConstants {
                    public static final String QUEUE = "orders.created.queue";
                    public static final String EXCHANGE = "orders.events";
                }
                """);
        myFixture.addFileToProject("org/example/mq/OrderRoutingKey.java", """
                package org.example.mq;
                public enum OrderRoutingKey {
                    ORDER_CREATED("orders.created", "HI");
                    private final String code;
                    private final String description;
                    OrderRoutingKey(String code, String description) {
                        this.code = code;
                        this.description = description;
                    }
                    public String getCode() { return code; }
                }
                """);
        myFixture.addFileToProject("org/example/mq/MessagePublisher.java", """
                package org.example.mq;
                public class MessagePublisher {
                    org.springframework.amqp.rabbit.core.RabbitTemplate template;
                    public void publishMessage(String exchange, String routingKey, Object message) {
                        template.convertAndSend(exchange, routingKey, message);
                    }
                }
                """);
        myFixture.addFileToProject("OrderEventTopology.java", """
                import org.example.mq.OrderEventConstants;
                import org.example.mq.OrderRoutingKey;
                class OrderEventTopology {
                    org.springframework.amqp.core.Queue queue() {
                        return org.springframework.amqp.core.QueueBuilder.durable(OrderEventConstants.QUEUE).build();
                    }
                    org.springframework.amqp.core.TopicExchange exchange() {
                        return org.springframework.amqp.core.ExchangeBuilder
                            .topicExchange(OrderEventConstants.EXCHANGE).durable(true).build();
                    }
                    void binding() {
                        org.springframework.amqp.core.BindingBuilder.bind(queue())
                            .to(exchange()).with(OrderRoutingKey.ORDER_CREATED.getCode());
                    }
                }
                """);
        myFixture.configureByText("OrderEventNavigationCase.java", """
                import org.example.mq.OrderEventConstants;
                import org.example.mq.OrderRoutingKey;
                import org.example.mq.MessagePublisher;
                class OrderEventNavigationCase {
                    MessagePublisher templateUtil;
                    void publish() {
                        templateUtil.publishMessage(
                            OrderEventConstants.EXCHANGE,
                            OrderRoutingKey.ORDER_CREATED.getCode(),
                            new Object());
                    }
                    @org.springframework.amqp.rabbit.annotation.RabbitListener(queues = OrderEventConstants.QUEUE)
                    void consume(Object value) {}
                }
                """);

        MqNavigationService.NavigationOutcome outcome = findTargets(
                MqNavigationService.getInstance(getProject()), identifier("consume"));

        assertEquals(MqNavigationService.NavigationStatus.ONE_MATCH, outcome.status());
        assertEquals("publish", outcome.targets().get(0).memberName());
        assertEquals("orders.created", outcome.targets().get(0).destination().routingKey());
    }

    public void testRabbitTopologyAcrossQueueExchangeAndBindingConfigurations() throws Exception {
        myFixture.addFileToProject("org/example/mq/DocumentConstants.java", """
                package org.example.mq;
                public class DocumentConstants {
                    public static final String QUEUE = "documents.queue";
                    public static final String EXCHANGE = "documents.exchange";
                    public static final String ROUTING_KEY = "documents.created";
                }
                """);
        myFixture.addFileToProject("org/example/mq/DocumentQueueConfiguration.java", """
                package org.example.mq;
                public class DocumentQueueConfiguration {
                    public org.springframework.amqp.core.Queue documentQueue() {
                        org.springframework.amqp.core.Queue queue =
                            org.springframework.amqp.core.QueueBuilder.durable(DocumentConstants.QUEUE).quorum().build();
                        return queue;
                    }
                }
                """);
        myFixture.addFileToProject("org/example/mq/DocumentExchangeConfiguration.java", """
                package org.example.mq;
                public class DocumentExchangeConfiguration {
                    public org.springframework.amqp.core.TopicExchange documentExchange() {
                        return new org.springframework.amqp.core.TopicExchange(DocumentConstants.EXCHANGE, true, false);
                    }
                }
                """);
        myFixture.addFileToProject("org/example/mq/DocumentBindingConfiguration.java", """
                package org.example.mq;
                public class DocumentBindingConfiguration {
                    DocumentQueueConfiguration documentQueue = new DocumentQueueConfiguration();
                    DocumentExchangeConfiguration documentExchange = new DocumentExchangeConfiguration();
                    public void bind() {
                        org.springframework.amqp.core.Binding binding =
                            org.springframework.amqp.core.BindingBuilder.bind(documentQueue.documentQueue())
                                .to(documentExchange.documentExchange()).with(DocumentConstants.ROUTING_KEY);
                    }
                }
                """);
        myFixture.configureByText("DocumentMessageCase.java", """
                import org.example.messaging.MessagePublisher;
                import org.example.mq.DocumentConstants;
                class DocumentMessageCase {
                    MessagePublisher publisher;
                    void publish() {
                        publisher.publish(
                            DocumentConstants.EXCHANGE, DocumentConstants.ROUTING_KEY, new Object());
                    }
                    @org.springframework.amqp.rabbit.annotation.RabbitListener(queues = DocumentConstants.QUEUE)
                    void consume(Object value) {}
                }
                """);

        MqNavigationService.NavigationOutcome outcome = findTargets(
                MqNavigationService.getInstance(getProject()), identifier("consume"));

        assertEquals(MqNavigationService.NavigationStatus.ONE_MATCH, outcome.status());
        assertEquals("publish", outcome.targets().get(0).memberName());
    }

    public void testRabbitListenerFindsProducerWithConditionalRoutingKeyAlias() throws Exception {
        myFixture.addFileToProject("org/example/common/constant/BillingConstants.java", """
                package org.example.common.constant;
                public class BillingConstants {
                    public static final String QUEUE = "billing.high-priority.queue";
                    public static final String EXCHANGE = "billing.events";
                    public static final String HIGH_ROUTING_KEY = "billing.high";
                    public static final String PROCESS_ROUTING_KEY = "billing.process";
                }
                """);
        myFixture.configureByText("BillingMessageCase.java", """
                import org.example.messaging.MessagePublisher;
                import org.example.common.constant.BillingConstants;
                class BillingMessageCase {
                    MessagePublisher publisher;

                    void publish(String declarationType) {
                        String routingKey = "D".equals(declarationType)
                            ? BillingConstants.HIGH_ROUTING_KEY
                            : BillingConstants.PROCESS_ROUTING_KEY;
                        publisher.publish(
                            BillingConstants.EXCHANGE, routingKey, new Object());
                    }

                    @org.springframework.amqp.rabbit.annotation.RabbitListener(
                        queues = BillingConstants.QUEUE)
                    void consume(Object value) {}
                }
                """);
        myFixture.addFileToProject("BillingTopologyConfiguration.java", """
                import org.example.common.constant.BillingConstants;
                class BillingTopologyConfiguration {
                    org.springframework.amqp.core.Queue queue() {
                        return org.springframework.amqp.core.QueueBuilder.durable(BillingConstants.QUEUE).build();
                    }
                    org.springframework.amqp.core.TopicExchange exchange() {
                        return org.springframework.amqp.core.ExchangeBuilder
                            .topicExchange(BillingConstants.EXCHANGE).durable(true).build();
                    }
                    void binding() {
                        org.springframework.amqp.core.Binding binding =
                            org.springframework.amqp.core.BindingBuilder.bind(queue())
                                .to(exchange()).with(BillingConstants.HIGH_ROUTING_KEY);
                    }
                }
                """);

        MqNavigationService.NavigationOutcome outcome = findTargets(
                MqNavigationService.getInstance(getProject()), identifier("consume"));

        assertEquals(MqNavigationService.NavigationStatus.ONE_MATCH, outcome.status());
        assertEquals("publish", outcome.targets().get(0).memberName());
        assertEquals("billing.events", outcome.targets().get(0).destination().exchange());
        assertEquals("billing.high", outcome.targets().get(0).destination().routingKey());
    }

    public void testRabbitListenerFindsProducerWithSwitchAssignedRoutingKey() throws Exception {
        myFixture.addFileToProject("org/example/mq/OrderTaskConstants.java", """
                package org.example.mq;
                public class OrderTaskConstants {
                    public static final String QUEUE = "orders.data.ready.queue";
                    public static final String EXCHANGE = "orders.task.exchange";
                    public static final String DATA_PRE_ROUTING_KEY = "orders.data.ready";
                    public static final String OTHER_ROUTING_KEY = "orders.other";
                }
                """);
        myFixture.configureByText("AssignmentMessageCase.java", """
                import org.example.messaging.MessagePublisher;
                import org.example.mq.OrderTaskConstants;
                class AssignmentMessageCase {
                    MessagePublisher publisher;

                    void publish(String taskType) {
                        String routingKey;
                        switch (taskType) {
                            case "DATA_PRE_PROCESS" -> routingKey = OrderTaskConstants.DATA_PRE_ROUTING_KEY;
                            default -> routingKey = OrderTaskConstants.OTHER_ROUTING_KEY;
                        }
                        publisher.publish(OrderTaskConstants.EXCHANGE, routingKey, new Object());
                    }

                    @org.springframework.amqp.rabbit.annotation.RabbitListener(
                        queues = OrderTaskConstants.QUEUE)
                    void consume(Object value) {}
                }
                """);
        myFixture.addFileToProject("OrderTaskTopologyConfiguration.java", """
                import org.example.mq.OrderTaskConstants;
                class OrderTaskTopologyConfiguration {
                    org.springframework.amqp.core.Queue queue() {
                        return org.springframework.amqp.core.QueueBuilder.durable(OrderTaskConstants.QUEUE).build();
                    }
                    org.springframework.amqp.core.TopicExchange exchange() {
                        return org.springframework.amqp.core.ExchangeBuilder
                            .topicExchange(OrderTaskConstants.EXCHANGE).durable(true).build();
                    }
                    void binding() {
                        org.springframework.amqp.core.Binding binding =
                            org.springframework.amqp.core.BindingBuilder.bind(queue())
                                .to(exchange()).with(OrderTaskConstants.DATA_PRE_ROUTING_KEY);
                    }
                }
                """);

        MqNavigationService.NavigationOutcome outcome = findTargets(
                MqNavigationService.getInstance(getProject()), identifier("consume"));

        assertEquals(MqNavigationService.NavigationStatus.ONE_MATCH, outcome.status());
        assertEquals("publish", outcome.targets().get(0).memberName());
        assertEquals("orders.task.exchange", outcome.targets().get(0).destination().exchange());
        assertEquals("orders.data.ready", outcome.targets().get(0).destination().routingKey());
    }

    public void testRabbitListenerFindsProducerWithLocalExchangeAndClassicSwitch() throws Exception {
        myFixture.addFileToProject("org/example/mq/OrderTaskConstants.java", """
                package org.example.mq;
                public class OrderTaskConstants {
                    public static final String QUEUE = "orders.data.ready.queue";
                    public static final String EXCHANGE = "orders.task.exchange";
                    public static final String DATA_PRE_ROUTING_KEY = "orders.data.ready";
                    public static final String OTHER_ROUTING_KEY = "orders.other";
                }
                """);
        myFixture.addFileToProject("org/example/messaging/DelegatingMessagePublisher.java", """
                package org.example.messaging;
                public class DelegatingMessagePublisher {
                    private org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;
                    public void publishMessage(String exchange, String routingKey, Object message) {
                        rabbitTemplate.convertAndSend(exchange, routingKey, message);
                    }
                }
                """);
        myFixture.configureByText("OrderTaskProducer.java", """
                import org.example.messaging.DelegatingMessagePublisher;
                import org.example.mq.OrderTaskConstants;
                class OrderTaskProducer {
                    DelegatingMessagePublisher publisher;
                    void publish(TaskType type) {
                        String exchange = OrderTaskConstants.EXCHANGE;
                        String routingKey;
                        switch (type) {
                            case DATA_PRE_PROCESS:
                                routingKey = OrderTaskConstants.DATA_PRE_ROUTING_KEY;
                                break;
                            case OTHER:
                                routingKey = OrderTaskConstants.OTHER_ROUTING_KEY;
                                break;
                            default:
                                return;
                        }
                        publisher.publishMessage(exchange, routingKey, new Object());
                    }
                    @org.springframework.amqp.rabbit.annotation.RabbitListener(queues = OrderTaskConstants.QUEUE)
                    void consume(Object value) {}
                }
                enum TaskType { DATA_PRE_PROCESS, OTHER }
                """);
        myFixture.addFileToProject("OrderTaskTopologyConfiguration.java", """
                import org.example.mq.OrderTaskConstants;
                class OrderTaskTopologyConfiguration {
                    org.springframework.amqp.core.Queue queue() {
                        org.springframework.amqp.core.Queue queue = org.springframework.amqp.core.QueueBuilder
                            .durable(OrderTaskConstants.QUEUE).quorum().build();
                        return queue;
                    }
                    org.springframework.amqp.core.TopicExchange exchange() {
                        org.springframework.amqp.core.TopicExchange exchange =
                            (org.springframework.amqp.core.TopicExchange) org.springframework.amqp.core.ExchangeBuilder
                                .topicExchange(OrderTaskConstants.EXCHANGE).durable(true).build();
                        return exchange;
                    }
                    void binding() {
                        org.springframework.amqp.core.Binding binding = org.springframework.amqp.core.BindingBuilder
                            .bind(queue()).to(exchange()).with(OrderTaskConstants.DATA_PRE_ROUTING_KEY);
                    }
                }
                """);

        MqNavigationService.NavigationOutcome outcome = findTargets(
                MqNavigationService.getInstance(getProject()), identifier("consume"));

        assertEquals(MqNavigationService.NavigationStatus.ONE_MATCH, outcome.status());
        assertEquals("publish", outcome.targets().get(0).memberName());
        assertEquals("orders.task.exchange", outcome.targets().get(0).destination().exchange());
        assertEquals("orders.data.ready", outcome.targets().get(0).destination().routingKey());
    }

    public void testRabbitListenerFindsDirectTemplateProducerWithLocalExchange() throws Exception {
        myFixture.configureByText("DirectTemplateProducer.java", """
                class DirectTemplateProducer {
                    org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;
                    void publish() {
                        String exchange = "orders.task.exchange";
                        rabbitTemplate.convertAndSend(exchange, "orders.data.ready", new Object());
                    }
                    @org.springframework.amqp.rabbit.annotation.RabbitListener(queues = "orders.data.ready.queue")
                    void consume(Object value) {}
                }
                """);
        myFixture.addFileToProject("DirectTemplateTopology.java", """
                class DirectTemplateTopology {
                    org.springframework.amqp.core.Queue queue() {
                        return org.springframework.amqp.core.QueueBuilder.durable("orders.data.ready.queue").build();
                    }
                    org.springframework.amqp.core.TopicExchange exchange() {
                        return (org.springframework.amqp.core.TopicExchange) org.springframework.amqp.core.ExchangeBuilder
                            .topicExchange("orders.task.exchange").durable(true).build();
                    }
                    void binding() {
                        org.springframework.amqp.core.Binding binding = org.springframework.amqp.core.BindingBuilder
                            .bind(queue()).to(exchange()).with("orders.data.ready");
                    }
                }
                """);

        MqNavigationService.NavigationOutcome outcome = findTargets(
                MqNavigationService.getInstance(getProject()), identifier("consume"));

        assertEquals(MqNavigationService.NavigationStatus.ONE_MATCH, outcome.status());
        assertEquals("publish", outcome.targets().get(0).memberName());
    }

    public void testDelegatingRabbitOverloadNavigatesBothWays() throws Exception {
        myFixture.addFileToProject("FanoutPublisher.java", """
                class FanoutPublisher {
                    org.springframework.amqp.rabbit.core.RabbitTemplate template;
                    void publish(String exchange, String key, Object body) {
                        template.convertAndSend(exchange, key, body);
                    }
                    void publish(String exchange, Object body) { publish(exchange, "", body); }
                }
                """);
        myFixture.configureByText("FanoutOverloadCase.java", """
                class FanoutOverloadCase {
                    FanoutPublisher publisher;
                    void sync() { publisher.publish("business.exchange", new Object()); }
                    @org.springframework.amqp.rabbit.annotation.RabbitListener(bindings =
                        @org.springframework.amqp.rabbit.annotation.QueueBinding(
                            value = @org.springframework.amqp.rabbit.annotation.Queue("business.queue"),
                            exchange = @org.springframework.amqp.rabbit.annotation.Exchange(
                                value = "business.exchange", type = "fanout")))
                    void consume(Object value) {}
                }
                """);
        MqNavigationService service = MqNavigationService.getInstance(getProject());

        var forward = findTargets(service, identifier("publish"));
        var reverse = findTargets(service, identifier("consume"));

        assertEquals(MqNavigationService.NavigationStatus.ONE_MATCH, forward.status());
        assertEquals("consume", forward.targets().get(0).memberName());
        assertEquals(MqNavigationService.NavigationStatus.ONE_MATCH, reverse.status());
        assertEquals("sync", reverse.targets().get(0).memberName());
        assertEquals("business.exchange", reverse.targets().get(0).destination().exchange());
        assertEquals("", reverse.targets().get(0).destination().routingKey());
    }

    private MqNavigationService.NavigationOutcome findTargets(
            MqNavigationService service, PsiIdentifier source) throws Exception {
        Future<MqNavigationService.NavigationOutcome> future = ApplicationManager.getApplication()
                .executeOnPooledThread(() -> ProgressManager.getInstance().runProcess(
                        () -> ReadAction.compute(() -> service.findTargets(source)),
                        new EmptyProgressIndicator()));
        return future.get();
    }

    private PsiIdentifier identifier(String text) {
        return com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                        myFixture.getFile(), PsiIdentifier.class).stream()
                .filter(identifier -> text.equals(identifier.getText()))
                .findFirst().orElseThrow();
    }
}
