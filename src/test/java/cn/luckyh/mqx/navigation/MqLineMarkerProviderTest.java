package cn.luckyh.mqx.navigation;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.SmartPointerManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

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
        assertTrue(renderer.getElementText(target).contains("[KAFKA]"));
        assertTrue(renderer.getElementText(target).contains("RendererCase.java"));
        assertTrue(renderer.getElementText(target).contains("topic=orders"));
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

    public void testRabbitQueueDeclarationHasMarker() {
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

        assertNotNull(new MqLineMarkerProvider().getLineMarkerInfo(durable));
    }
}
