package cn.luckyh.mqx.parser;

import com.intellij.psi.PsiIdentifier;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import cn.luckyh.mqx.model.MqEndpoint;

import java.nio.file.Path;
import java.util.LinkedHashSet;

public class KafkaEndpointParserTest extends BasePlatformTestCase {

    @Override
    protected String getTestDataPath() {
        return Path.of("src/test/testData").toAbsolutePath().toString();
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.copyDirectoryToProject("stubs", "");
    }

    public void testProducerAndListener() {
        myFixture.configureByText("KafkaCase.java", """
                class KafkaCase {
                    org.springframework.kafka.core.KafkaTemplate<String, Object> template;
                    void publish() { template.send("orders", new Object()); }
                    @org.springframework.kafka.annotation.KafkaListener(topics = "orders", groupId = "billing")
                    void consume(Object value) {}
                }
                """);
        KafkaEndpointParser parser = new KafkaEndpointParser(new MqValueResolver(getProject()));
        MqEndpoint producer = parser.parseAnchor(identifier("send"), new LinkedHashSet<>()).orElseThrow();
        MqEndpoint consumer = parser.parseAnchor(identifier("consume"), new LinkedHashSet<>()).orElseThrow();
        assertEquals("orders", producer.destination().topic());
        assertEquals("billing", consumer.destination().consumerGroup());
    }

    public void testIncompleteKafkaFileIsIsolated() {
        myFixture.configureByText("BrokenKafka.java", "class BrokenKafka { void x() { template.send(");
        assertTrue(new KafkaEndpointParser(new MqValueResolver(getProject()))
                .parseAnchor(identifier("send"), new LinkedHashSet<>()).isEmpty());
    }

    public void testProducerInsideAnonymousStrategyUsesEmptyClassName() {
        myFixture.configureByText("MessageStrategy.java", """
                class MessageStrategy {
                    org.springframework.kafka.core.KafkaTemplate<String, Object> template;
                    Runnable sender = new Runnable() {
                        public void run() { template.send("orders", new Object()); }
                    };
                }
                """);

        MqEndpoint endpoint = new KafkaEndpointParser(new MqValueResolver(getProject()))
                .parseAnchor(identifier("send"), new LinkedHashSet<>())
                .orElseThrow();

        assertEquals("", endpoint.className());
        assertEquals("run", endpoint.memberName());
    }

    private PsiIdentifier identifier(String text) {
        return com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                        myFixture.getFile(), PsiIdentifier.class).stream()
                .filter(identifier -> text.equals(identifier.getText()))
                .findFirst().orElseThrow();
    }
}
