package cn.luckyh.mqx.navigation;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.SmartPointerManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import cn.luckyh.mqx.model.MqDestination;
import cn.luckyh.mqx.model.MqEndpoint;

import java.nio.file.Path;
import java.util.concurrent.Future;

public class MqLineMarkerProviderTest extends BasePlatformTestCase {

    @Override
    protected String getTestDataPath() {
        return Path.of("src/test/testData").toAbsolutePath().toString();
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.copyDirectoryToProject("stubs", "");
    }

    public void testProducerMethodNameHasMarker() {
        myFixture.configureByText("MarkerCase.java", """
                class MarkerCase {
                    org.springframework.kafka.core.KafkaTemplate<String, Object> template;
                    void publish() { template.send("orders", new Object()); }
                }
                """);
        PsiIdentifier identifier = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                        myFixture.getFile(), PsiIdentifier.class).stream()
                .filter(item -> "send".equals(item.getText()))
                .findFirst().orElseThrow();
        assertNotNull(new MqLineMarkerProvider().getLineMarkerInfo(identifier));
    }

    public void testConsumerMethodNameHasMarker() {
        myFixture.configureByText("ConsumerMarkerCase.java", """
                class ConsumerMarkerCase {
                    @org.springframework.kafka.annotation.KafkaListener(topics = "orders")
                    void consume(Object value) {}
                }
                """);
        PsiIdentifier identifier = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                        myFixture.getFile(), PsiIdentifier.class).stream()
                .filter(item -> "consume".equals(item.getText()))
                .findFirst().orElseThrow();
        assertNotNull(new MqLineMarkerProvider().getLineMarkerInfo(identifier));
    }

    public void testBothRabbitProducerOverloadsHaveMarkersInsideLambda() {
        myFixture.addFileToProject("RabbitPublisher.java", """
                class RabbitPublisher {
                    org.springframework.amqp.rabbit.core.RabbitTemplate template;
                    void publish(String exchange, String key, Object body) {
                        template.convertAndSend(exchange, key, body);
                    }
                    void publish(String exchange, Object body) { publish(exchange, "", body); }
                }
                """);
        myFixture.configureByText("RabbitMarkerCase.java", """
                class RabbitMarkerCase {
                    RabbitPublisher publisher;
                    void sync(java.util.List<String> items) {
                        items.forEach(body -> {
                            publisher.publish("risk.exchange", "risk.route", body);
                            publisher.publish("business.exchange", body);
                        });
                    }
                }
                """);
        var identifiers = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                        myFixture.getFile(), PsiIdentifier.class).stream()
                .filter(item -> "publish".equals(item.getText())).toList();
        MqLineMarkerProvider provider = new MqLineMarkerProvider();

        assertEquals(2, identifiers.size());
        for (PsiIdentifier identifier : identifiers) {
            assertNotNull(provider.getLineMarkerInfo(identifier));
        }
    }

    public void testClassLevelConsumersHaveMarkers() {
        myFixture.configureByText("ClassConsumers.java", """
                @org.springframework.amqp.rabbit.annotation.RabbitListener(queues = "orders")
                class RabbitClassConsumer {}
                @org.apache.rocketmq.spring.annotation.RocketMQMessageListener(
                    topic = "notice", consumerGroup = "group")
                class RocketClassConsumer {}
                """);
        var identifiers = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                myFixture.getFile(), PsiIdentifier.class);
        MqLineMarkerProvider provider = new MqLineMarkerProvider();
        assertNotNull(provider.getLineMarkerInfo(identifiers.stream()
                .filter(item -> "RabbitClassConsumer".equals(item.getText())).findFirst().orElseThrow()));
        assertNotNull(provider.getLineMarkerInfo(identifiers.stream()
                .filter(item -> "RocketClassConsumer".equals(item.getText())).findFirst().orElseThrow()));
    }

    public void testMultipleTargetRendererIncludesProjectContext() throws Exception {
        myFixture.configureByText("RendererCase.java", """
                class RendererCase {
                    org.springframework.kafka.core.KafkaTemplate<String, Object> template;
                    void publish() { template.send("orders", new Object()); }
                    @org.springframework.kafka.annotation.KafkaListener(topics = "orders", groupId = "first")
                    void consumeOne(Object value) {}
                    @org.springframework.kafka.annotation.KafkaListener(topics = "orders", groupId = "second")
                    void consumeTwo(Object value) {}
                }
                """);
        PsiIdentifier source = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                        myFixture.getFile(), PsiIdentifier.class).stream()
                .filter(item -> "send".equals(item.getText()))
                .findFirst().orElseThrow();
        Future<MqNavigationService.NavigationOutcome> future = ApplicationManager.getApplication()
                .executeOnPooledThread(() -> ProgressManager.getInstance().runProcess(
                        () -> ReadAction.compute(
                                () -> MqNavigationService.getInstance(getProject()).findTargets(source)),
                        new EmptyProgressIndicator()));
        MqNavigationService.NavigationOutcome outcome = future.get();
        MqTargetRenderer renderer = new MqTargetRenderer(outcome.targets());
        var target = outcome.targets().get(0).sourcePointer().getElement();
        assertNotNull(target);
        assertEquals("RendererCase#consumeOne", renderer.getElementText(target));
        assertTrue(renderer.getContainerText(target).contains("topic=orders"));
        assertTrue(renderer.getContainerText(target).contains("group=first"));
    }

    public void testRabbitTargetsShowDistinctMethodsModulesAndQueueSuffixes() {
        myFixture.configureByText("TargetLabels.java", """
                class EventListener { void orderStatusChanged(Object message) {} }
                class BillingListener { void processData(Object message) {} }
                """);
        var identifiers = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                myFixture.getFile(), PsiIdentifier.class);
        PsiIdentifier event = identifiers.stream()
                .filter(item -> "orderStatusChanged".equals(item.getText())).findFirst().orElseThrow();
        PsiIdentifier billing = identifiers.stream()
                .filter(item -> "processData".equals(item.getText())).findFirst().orElseThrow();
        var pointers = SmartPointerManager.getInstance(getProject());
        MqEndpoint eventTarget = new MqEndpoint("event", MqEndpoint.SystemType.RABBITMQ,
                MqEndpoint.Role.CONSUMER,
                MqDestination.rabbitConsumer("orders.status",
                        "orders.status.data.process", java.util.List.of(), "fanout"),
                MqEndpoint.ResolutionState.STATIC, null, pointers.createSmartPsiElementPointer(event),
                "sample-project.order-processing.main", "TargetLabels.java",
                "EventListener", "orderStatusChanged");
        MqEndpoint billingTarget = new MqEndpoint("billing", MqEndpoint.SystemType.RABBITMQ,
                MqEndpoint.Role.CONSUMER,
                MqDestination.rabbitConsumer("orders.status",
                        "orders.status.billing.calc", java.util.List.of(), "fanout"),
                MqEndpoint.ResolutionState.STATIC, null, pointers.createSmartPsiElementPointer(billing),
                "sample-project.billing.billing-service.main", "TargetLabels.java",
                "BillingListener", "processData");
        MqTargetRenderer renderer = new MqTargetRenderer(java.util.List.of(eventTarget, billingTarget));

        assertEquals("EventListener#orderStatusChanged", renderer.getElementText(event));
        assertEquals("order-processing · queue data.process", renderer.getContainerText(event));
        assertEquals("BillingListener#processData", renderer.getElementText(billing));
        assertEquals("billing-service · queue billing.calc", renderer.getContainerText(billing));

        MqEndpoint unrelatedQueue = new MqEndpoint("unrelated", MqEndpoint.SystemType.RABBITMQ,
                MqEndpoint.Role.CONSUMER,
                MqDestination.rabbitConsumer("orders.status", "another.queue",
                        java.util.List.of(), "fanout"),
                MqEndpoint.ResolutionState.STATIC, null, pointers.createSmartPsiElementPointer(billing),
                "sample-project.billing.billing-service.main", "TargetLabels.java",
                "BillingListener", "processData");
        MqTargetRenderer fullQueueRenderer = new MqTargetRenderer(java.util.List.of(eventTarget, unrelatedQueue));
        assertEquals("order-processing · queue orders.status.data.process",
                fullQueueRenderer.getContainerText(event));
    }

    public void testSameSourcePointerUsesCommittedCurrentState() throws Exception {
        myFixture.configureByText("CurrentStateCase.java", """
                class CurrentStateCase {
                    org.springframework.kafka.core.KafkaTemplate<String, Object> template;
                    void publish() { template.send("orders", new Object()); }
                    @org.springframework.kafka.annotation.KafkaListener(topics = "orders")
                    void consume(Object value) {}
                }
                """);
        PsiIdentifier source = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                        myFixture.getFile(), PsiIdentifier.class).stream()
                .filter(item -> "send".equals(item.getText())).findFirst().orElseThrow();
        var pointer = SmartPointerManager.getInstance(getProject()).createSmartPsiElementPointer(source);
        String text = myFixture.getEditor().getDocument().getText();
        int listenerTopic = text.lastIndexOf("orders");
        WriteCommandAction.runWriteCommandAction(getProject(), () ->
                myFixture.getEditor().getDocument().replaceString(listenerTopic, listenerTopic + 6, "other"));
        com.intellij.psi.PsiDocumentManager.getInstance(getProject()).commitAllDocuments();

        Future<MqNavigationService.NavigationOutcome> future = ApplicationManager.getApplication()
                .executeOnPooledThread(() -> ProgressManager.getInstance().runProcess(
                        () -> ReadAction.compute(() -> MqNavigationService.getInstance(getProject())
                                .findTargets(pointer.getElement())),
                        new EmptyProgressIndicator()));
        assertEquals(MqNavigationService.NavigationStatus.NO_MATCH, future.get().status());
    }

    public void testRefreshActionIsAvailable() {
        assertNotNull(new cn.luckyh.mqx.action.RefreshMqIndexAction());
    }

    public void testMqTopologyToolWindowIsAvailable() {
        assertNotNull(new cn.luckyh.mqx.ui.MqToolWindowFactory());
        assertNotNull(cn.luckyh.mqx.ui.MqToolWindowService.getInstance(getProject()));
    }

    public void testRabbitQueueDeclarationHasNoProducerConsumerMarker() {
        myFixture.configureByText("QueueDeclarationMarker.java", """
                class QueueDeclarationMarker {
                    org.springframework.amqp.core.Queue declareQueue() {
                        return org.springframework.amqp.core.QueueBuilder.durable("orders").build();
                    }
                }
                """);
        PsiIdentifier durable = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                        myFixture.getFile(), PsiIdentifier.class).stream()
                .filter(item -> "durable".equals(item.getText()))
                .findFirst().orElseThrow();

        assertNull(new MqLineMarkerProvider().getLineMarkerInfo(durable));
    }
}
