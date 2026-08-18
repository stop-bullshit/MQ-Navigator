package cn.luckyh.mqx.navigation;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiIdentifier;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Future;

public class MqBrokerIsolationTest extends BasePlatformTestCase {

    @Override
    protected String getTestDataPath() {
        return Path.of("src/test/testData").toAbsolutePath().toString();
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.copyDirectoryToProject("stubs", "");
    }

    public void testScanningNavigationAndRefreshOpenNoBrokerConnections() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (ServerSocket rabbit = new ServerSocket(0, 1, loopback);
             ServerSocket kafka = new ServerSocket(0, 1, loopback);
             ServerSocket rocket = new ServerSocket(0, 1, loopback)) {
            for (ServerSocket sentinel : List.of(rabbit, kafka, rocket)) {
                sentinel.setSoTimeout(100);
            }
            myFixture.addFileToProject("src/main/resources/application.properties", """
                    spring.rabbitmq.host=127.0.0.1
                    spring.rabbitmq.port=%d
                    spring.kafka.bootstrap-servers=127.0.0.1:%d
                    rocketmq.name-server=127.0.0.1:%d
                    """.formatted(rabbit.getLocalPort(), kafka.getLocalPort(), rocket.getLocalPort()));
            myFixture.configureByText("IsolationCase.java", """
                    class IsolationCase {
                        org.springframework.kafka.core.KafkaTemplate<String, Object> template;
                        void publish() { template.send("orders", new Object()); }
                        @org.springframework.kafka.annotation.KafkaListener(topics = "orders")
                        void consume(Object value) {}
                    }
                    """);
            PsiIdentifier source = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                            myFixture.getFile(), PsiIdentifier.class).stream()
                    .filter(item -> "send".equals(item.getText())).findFirst().orElseThrow();
            MqNavigationService service = MqNavigationService.getInstance(getProject());
            Future<MqNavigationService.NavigationOutcome> future = ApplicationManager.getApplication()
                    .executeOnPooledThread(() -> ProgressManager.getInstance().runProcess(
                            () -> ReadAction.compute(() -> service.findTargets(source)),
                            new EmptyProgressIndicator()));
            assertEquals(MqNavigationService.NavigationStatus.ONE_MATCH, future.get().status());
            service.requestRefresh();

            assertNoConnection(rabbit);
            assertNoConnection(kafka);
            assertNoConnection(rocket);
        }
    }

    private void assertNoConnection(ServerSocket sentinel) throws Exception {
        try {
            sentinel.accept().close();
            fail("Unexpected broker connection attempt on " + sentinel.getLocalPort());
        } catch (SocketTimeoutException expected) {
            // No queued TCP connection is the expected result.
        }
    }
}
