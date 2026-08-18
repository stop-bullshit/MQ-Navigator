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

    public void testRabbitQueueDeclarationAndListenerNavigateBothDirections() throws Exception {
        myFixture.addFileToProject("com/dhl/common/constant/CdtcDockingQueueConstants.java", """
                package com.dhl.common.constant;
                public class CdtcDockingQueueConstants {
                    public static final String ACCOMPANYING_DOCUMENTS_QUEUE = "cdtc.ib.documents";
                }
                """);
        myFixture.configureByText("CdtcQueueMqConfiguration.java", """
                import com.dhl.common.constant.CdtcDockingQueueConstants;
                class CdtcQueueMqConfiguration {
                    org.springframework.amqp.core.Queue declareDocumentsQueue() {
                        return org.springframework.amqp.core.QueueBuilder.durable(
                            CdtcDockingQueueConstants.ACCOMPANYING_DOCUMENTS_QUEUE).quorum().build();
                    }

                    @org.springframework.amqp.rabbit.annotation.RabbitListener(
                        queues = CdtcDockingQueueConstants.ACCOMPANYING_DOCUMENTS_QUEUE)
                    void consumeDocuments(Object value) {}
                }
                """);
        MqNavigationService service = MqNavigationService.getInstance(getProject());

        MqNavigationService.NavigationOutcome fromDeclaration = findTargets(service, identifier("durable"));
        assertEquals(MqNavigationService.NavigationStatus.ONE_MATCH, fromDeclaration.status());
        assertEquals("consumeDocuments", fromDeclaration.targets().get(0).memberName());

        MqNavigationService.NavigationOutcome fromListener = findTargets(service, identifier("consumeDocuments"));
        assertEquals(MqNavigationService.NavigationStatus.ONE_MATCH, fromListener.status());
        assertEquals(MqEndpoint.Role.DECLARATION, fromListener.targets().get(0).role());
        assertEquals("declareDocumentsQueue", fromListener.targets().get(0).memberName());
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
        myFixture.addFileToProject("com/dhl/mq/MqConstants.java", """
                package com.dhl.mq;
                public class MqConstants {
                    public static final String QUEUE = "orders.queue";
                    public static final String EXCHANGE = "orders.exchange";
                }
                """);
        myFixture.configureByText("RabbitTopologyCase.java", """
                import org.example.messaging.MessagePublisher;
                import com.dhl.mq.MqConstants;
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
        myFixture.addFileToProject("com/dhl/mq/MultiBindingConstants.java", """
                package com.dhl.mq;
                public class MultiBindingConstants {
                    public static final String QUEUE = "shared.queue";
                    public static final String FIRST = "first.exchange";
                    public static final String SECOND = "second.exchange";
                }
                """);
        myFixture.configureByText("RabbitMultipleBindingCase.java", """
                import org.example.messaging.MessagePublisher;
                import com.dhl.mq.MultiBindingConstants;
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
        myFixture.addFileToProject("com/dhl/mq/TopicConstants.java", """
                package com.dhl.mq;
                public class TopicConstants {
                    public static final String QUEUE = "topic.queue";
                    public static final String EXCHANGE = "topic.exchange";
                    public static final String ROUTING_KEY = "orders.created";
                }
                """);
        myFixture.configureByText("RabbitTopicTopologyCase.java", """
                import org.example.messaging.MessagePublisher;
                import com.dhl.mq.TopicConstants;
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
        myFixture.addFileToProject("com/dhl/mq/CheckPointConstants.java", """
                package com.dhl.mq;
                public class CheckPointConstants {
                    public static final String QUEUE = "checkpoint.ib.hi.clearance-base";
                    public static final String EXCHANGE = "data.center.in.ib.checkpoint";
                }
                """);
        myFixture.addFileToProject("com/dhl/mq/CheckPointRoutingKey.java", """
                package com.dhl.mq;
                public enum CheckPointRoutingKey {
                    CHECKPOINT_IB_HI("checkpoint.ib.hi", "HI");
                    private final String code;
                    private final String description;
                    CheckPointRoutingKey(String code, String description) {
                        this.code = code;
                        this.description = description;
                    }
                    public String getCode() { return code; }
                }
                """);
        myFixture.addFileToProject("com/dhl/mq/RabbitTemplateUtil.java", """
                package com.dhl.mq;
                public class RabbitTemplateUtil {
                    org.springframework.amqp.rabbit.core.RabbitTemplate template;
                    public void clearanceConvertAndSend(String exchange, String routingKey, Object message) {
                        template.convertAndSend(exchange, routingKey, message);
                    }
                }
                """);
        myFixture.addFileToProject("CheckPointTopology.java", """
                import com.dhl.mq.CheckPointConstants;
                import com.dhl.mq.CheckPointRoutingKey;
                class CheckPointTopology {
                    org.springframework.amqp.core.Queue queue() {
                        return org.springframework.amqp.core.QueueBuilder.durable(CheckPointConstants.QUEUE).build();
                    }
                    org.springframework.amqp.core.TopicExchange exchange() {
                        return org.springframework.amqp.core.ExchangeBuilder
                            .topicExchange(CheckPointConstants.EXCHANGE).durable(true).build();
                    }
                    void binding() {
                        org.springframework.amqp.core.BindingBuilder.bind(queue())
                            .to(exchange()).with(CheckPointRoutingKey.CHECKPOINT_IB_HI.getCode());
                    }
                }
                """);
        myFixture.configureByText("CheckPointNavigationCase.java", """
                import com.dhl.mq.CheckPointConstants;
                import com.dhl.mq.CheckPointRoutingKey;
                import com.dhl.mq.RabbitTemplateUtil;
                class CheckPointNavigationCase {
                    RabbitTemplateUtil templateUtil;
                    void publish() {
                        templateUtil.clearanceConvertAndSend(
                            CheckPointConstants.EXCHANGE,
                            CheckPointRoutingKey.CHECKPOINT_IB_HI.getCode(),
                            new Object());
                    }
                    @org.springframework.amqp.rabbit.annotation.RabbitListener(queues = CheckPointConstants.QUEUE)
                    void consume(Object value) {}
                }
                """);

        MqNavigationService.NavigationOutcome outcome = findTargets(
                MqNavigationService.getInstance(getProject()), identifier("consume"));

        assertEquals(MqNavigationService.NavigationStatus.ONE_MATCH, outcome.status());
        assertEquals("publish", outcome.targets().get(0).memberName());
        assertEquals("checkpoint.ib.hi", outcome.targets().get(0).destination().routingKey());
    }

    public void testRabbitTopologyAcrossQueueExchangeAndBindingConfigurations() throws Exception {
        myFixture.addFileToProject("com/dhl/mq/CdtcConstants.java", """
                package com.dhl.mq;
                public class CdtcConstants {
                    public static final String QUEUE = "cdtc.queue";
                    public static final String EXCHANGE = "cdtc.exchange";
                    public static final String ROUTING_KEY = "cdtc.route";
                }
                """);
        myFixture.addFileToProject("com/dhl/mq/CdtcQueueConfiguration.java", """
                package com.dhl.mq;
                public class CdtcQueueConfiguration {
                    public org.springframework.amqp.core.Queue cdtcQueue() {
                        org.springframework.amqp.core.Queue queue =
                            org.springframework.amqp.core.QueueBuilder.durable(CdtcConstants.QUEUE).quorum().build();
                        return queue;
                    }
                }
                """);
        myFixture.addFileToProject("com/dhl/mq/CdtcExchangeConfiguration.java", """
                package com.dhl.mq;
                public class CdtcExchangeConfiguration {
                    public org.springframework.amqp.core.TopicExchange cdtcExchange() {
                        return new org.springframework.amqp.core.TopicExchange(CdtcConstants.EXCHANGE, true, false);
                    }
                }
                """);
        myFixture.addFileToProject("com/dhl/mq/CdtcBindingConfiguration.java", """
                package com.dhl.mq;
                public class CdtcBindingConfiguration {
                    CdtcQueueConfiguration cdtcQueue = new CdtcQueueConfiguration();
                    CdtcExchangeConfiguration cdtcExchange = new CdtcExchangeConfiguration();
                    public void bind() {
                        org.springframework.amqp.core.Binding binding =
                            org.springframework.amqp.core.BindingBuilder.bind(cdtcQueue.cdtcQueue())
                                .to(cdtcExchange.cdtcExchange()).with(CdtcConstants.ROUTING_KEY);
                    }
                }
                """);
        myFixture.configureByText("CdtcMessageCase.java", """
                import org.example.messaging.MessagePublisher;
                import com.dhl.mq.CdtcConstants;
                class CdtcMessageCase {
                    MessagePublisher publisher;
                    void publish() {
                        publisher.publish(
                            CdtcConstants.EXCHANGE, CdtcConstants.ROUTING_KEY, new Object());
                    }
                    @org.springframework.amqp.rabbit.annotation.RabbitListener(queues = CdtcConstants.QUEUE)
                    void consume(Object value) {}
                }
                """);

        MqNavigationService.NavigationOutcome outcome = findTargets(
                MqNavigationService.getInstance(getProject()), identifier("consume"));

        assertEquals(MqNavigationService.NavigationStatus.ONE_MATCH, outcome.status());
        assertEquals("publish", outcome.targets().get(0).memberName());
    }

    public void testRabbitListenerFindsProducerWithConditionalRoutingKeyAlias() throws Exception {
        myFixture.addFileToProject("com/dhl/common/constant/CcdiConstants.java", """
                package com.dhl.common.constant;
                public class CcdiConstants {
                    public static final String QUEUE = "base.ib.call.ccdi.high-val.ib";
                    public static final String EXCHANGE = "base.ib.ccdi.call";
                    public static final String HIGH_ROUTING_KEY = "ccdi.call.high-val.ib";
                    public static final String PROCESS_ROUTING_KEY = "ccdi.call.process.ib";
                }
                """);
        myFixture.configureByText("CcdiMessageCase.java", """
                import org.example.messaging.MessagePublisher;
                import com.dhl.common.constant.CcdiConstants;
                class CcdiMessageCase {
                    MessagePublisher publisher;

                    void publish(String declarationType) {
                        String routingKey = "D".equals(declarationType)
                            ? CcdiConstants.HIGH_ROUTING_KEY
                            : CcdiConstants.PROCESS_ROUTING_KEY;
                        publisher.publish(
                            CcdiConstants.EXCHANGE, routingKey, new Object());
                    }

                    @org.springframework.amqp.rabbit.annotation.RabbitListener(
                        queues = CcdiConstants.QUEUE)
                    void consume(Object value) {}
                }
                """);
        myFixture.addFileToProject("CcdiTopologyConfiguration.java", """
                import com.dhl.common.constant.CcdiConstants;
                class CcdiTopologyConfiguration {
                    org.springframework.amqp.core.Queue queue() {
                        return org.springframework.amqp.core.QueueBuilder.durable(CcdiConstants.QUEUE).build();
                    }
                    org.springframework.amqp.core.TopicExchange exchange() {
                        return org.springframework.amqp.core.ExchangeBuilder
                            .topicExchange(CcdiConstants.EXCHANGE).durable(true).build();
                    }
                    void binding() {
                        org.springframework.amqp.core.Binding binding =
                            org.springframework.amqp.core.BindingBuilder.bind(queue())
                                .to(exchange()).with(CcdiConstants.HIGH_ROUTING_KEY);
                    }
                }
                """);

        MqNavigationService.NavigationOutcome outcome = findTargets(
                MqNavigationService.getInstance(getProject()), identifier("consume"));

        assertEquals(MqNavigationService.NavigationStatus.ONE_MATCH, outcome.status());
        assertEquals("publish", outcome.targets().get(0).memberName());
        assertEquals("base.ib.ccdi.call", outcome.targets().get(0).destination().exchange());
        assertEquals("ccdi.call.high-val.ib", outcome.targets().get(0).destination().routingKey());
    }

    public void testRabbitListenerFindsProducerWithSwitchAssignedRoutingKey() throws Exception {
        myFixture.addFileToProject("org/example/mq/AssignmentConstants.java", """
                package org.example.mq;
                public class AssignmentConstants {
                    public static final String QUEUE = "assignment.data.pre.queue";
                    public static final String EXCHANGE = "assignment.task.exchange";
                    public static final String DATA_PRE_ROUTING_KEY = "assignment.data.pre";
                    public static final String OTHER_ROUTING_KEY = "assignment.other";
                }
                """);
        myFixture.configureByText("AssignmentMessageCase.java", """
                import org.example.messaging.MessagePublisher;
                import org.example.mq.AssignmentConstants;
                class AssignmentMessageCase {
                    MessagePublisher publisher;

                    void publish(String taskType) {
                        String routingKey;
                        switch (taskType) {
                            case "DATA_PRE_PROCESS" -> routingKey = AssignmentConstants.DATA_PRE_ROUTING_KEY;
                            default -> routingKey = AssignmentConstants.OTHER_ROUTING_KEY;
                        }
                        publisher.publish(AssignmentConstants.EXCHANGE, routingKey, new Object());
                    }

                    @org.springframework.amqp.rabbit.annotation.RabbitListener(
                        queues = AssignmentConstants.QUEUE)
                    void consume(Object value) {}
                }
                """);
        myFixture.addFileToProject("AssignmentTopologyConfiguration.java", """
                import org.example.mq.AssignmentConstants;
                class AssignmentTopologyConfiguration {
                    org.springframework.amqp.core.Queue queue() {
                        return org.springframework.amqp.core.QueueBuilder.durable(AssignmentConstants.QUEUE).build();
                    }
                    org.springframework.amqp.core.TopicExchange exchange() {
                        return org.springframework.amqp.core.ExchangeBuilder
                            .topicExchange(AssignmentConstants.EXCHANGE).durable(true).build();
                    }
                    void binding() {
                        org.springframework.amqp.core.Binding binding =
                            org.springframework.amqp.core.BindingBuilder.bind(queue())
                                .to(exchange()).with(AssignmentConstants.DATA_PRE_ROUTING_KEY);
                    }
                }
                """);

        MqNavigationService.NavigationOutcome outcome = findTargets(
                MqNavigationService.getInstance(getProject()), identifier("consume"));

        assertEquals(MqNavigationService.NavigationStatus.ONE_MATCH, outcome.status());
        assertEquals("publish", outcome.targets().get(0).memberName());
        assertEquals("assignment.task.exchange", outcome.targets().get(0).destination().exchange());
        assertEquals("assignment.data.pre", outcome.targets().get(0).destination().routingKey());
    }

    public void testRabbitListenerFindsProducerWithLocalExchangeAndClassicSwitch() throws Exception {
        myFixture.addFileToProject("org/example/mq/AssignmentConstants.java", """
                package org.example.mq;
                public class AssignmentConstants {
                    public static final String QUEUE = "assignment.data.pre.queue";
                    public static final String EXCHANGE = "assignment.task.exchange";
                    public static final String DATA_PRE_ROUTING_KEY = "assignment.data.pre";
                    public static final String OTHER_ROUTING_KEY = "assignment.other";
                }
                """);
        myFixture.addFileToProject("org/example/messaging/RabbitTemplateUtil.java", """
                package org.example.messaging;
                public class RabbitTemplateUtil {
                    private org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;
                    public void clearanceConvertAndSend(String exchange, String routingKey, Object message) {
                        rabbitTemplate.convertAndSend(exchange, routingKey, message);
                    }
                }
                """);
        myFixture.configureByText("AssignmentProducer.java", """
                import org.example.messaging.RabbitTemplateUtil;
                import org.example.mq.AssignmentConstants;
                class AssignmentProducer {
                    RabbitTemplateUtil rabbitTemplateUtil;
                    void publish(TaskType type) {
                        String exchange = AssignmentConstants.EXCHANGE;
                        String routingKey;
                        switch (type) {
                            case DATA_PRE_PROCESS:
                                routingKey = AssignmentConstants.DATA_PRE_ROUTING_KEY;
                                break;
                            case OTHER:
                                routingKey = AssignmentConstants.OTHER_ROUTING_KEY;
                                break;
                            default:
                                return;
                        }
                        rabbitTemplateUtil.clearanceConvertAndSend(exchange, routingKey, new Object());
                    }
                    @org.springframework.amqp.rabbit.annotation.RabbitListener(queues = AssignmentConstants.QUEUE)
                    void consume(Object value) {}
                }
                enum TaskType { DATA_PRE_PROCESS, OTHER }
                """);
        myFixture.addFileToProject("AssignmentTopologyConfiguration.java", """
                import org.example.mq.AssignmentConstants;
                class AssignmentTopologyConfiguration {
                    org.springframework.amqp.core.Queue queue() {
                        org.springframework.amqp.core.Queue queue = org.springframework.amqp.core.QueueBuilder
                            .durable(AssignmentConstants.QUEUE).quorum().build();
                        return queue;
                    }
                    org.springframework.amqp.core.TopicExchange exchange() {
                        org.springframework.amqp.core.TopicExchange exchange =
                            (org.springframework.amqp.core.TopicExchange) org.springframework.amqp.core.ExchangeBuilder
                                .topicExchange(AssignmentConstants.EXCHANGE).durable(true).build();
                        return exchange;
                    }
                    void binding() {
                        org.springframework.amqp.core.Binding binding = org.springframework.amqp.core.BindingBuilder
                            .bind(queue()).to(exchange()).with(AssignmentConstants.DATA_PRE_ROUTING_KEY);
                    }
                }
                """);

        MqNavigationService.NavigationOutcome outcome = findTargets(
                MqNavigationService.getInstance(getProject()), identifier("consume"));

        assertEquals(MqNavigationService.NavigationStatus.ONE_MATCH, outcome.status());
        assertEquals("publish", outcome.targets().get(0).memberName());
        assertEquals("assignment.task.exchange", outcome.targets().get(0).destination().exchange());
        assertEquals("assignment.data.pre", outcome.targets().get(0).destination().routingKey());
    }

    public void testRabbitListenerFindsDirectTemplateProducerWithLocalExchange() throws Exception {
        myFixture.configureByText("DirectTemplateProducer.java", """
                class DirectTemplateProducer {
                    org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate;
                    void publish() {
                        String exchange = "assignment.task.exchange";
                        rabbitTemplate.convertAndSend(exchange, "assignment.data.pre", new Object());
                    }
                    @org.springframework.amqp.rabbit.annotation.RabbitListener(queues = "assignment.data.pre.queue")
                    void consume(Object value) {}
                }
                """);
        myFixture.addFileToProject("DirectTemplateTopology.java", """
                class DirectTemplateTopology {
                    org.springframework.amqp.core.Queue queue() {
                        return org.springframework.amqp.core.QueueBuilder.durable("assignment.data.pre.queue").build();
                    }
                    org.springframework.amqp.core.TopicExchange exchange() {
                        return (org.springframework.amqp.core.TopicExchange) org.springframework.amqp.core.ExchangeBuilder
                            .topicExchange("assignment.task.exchange").durable(true).build();
                    }
                    void binding() {
                        org.springframework.amqp.core.Binding binding = org.springframework.amqp.core.BindingBuilder
                            .bind(queue()).to(exchange()).with("assignment.data.pre");
                    }
                }
                """);

        MqNavigationService.NavigationOutcome outcome = findTargets(
                MqNavigationService.getInstance(getProject()), identifier("consume"));

        assertEquals(MqNavigationService.NavigationStatus.ONE_MATCH, outcome.status());
        assertEquals("publish", outcome.targets().get(0).memberName());
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
