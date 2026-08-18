package cn.luckyh.mqx.parser;

import com.intellij.psi.PsiIdentifier;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import cn.luckyh.mqx.model.MqEndpoint;

import java.nio.file.Path;
import java.util.LinkedHashSet;

public class RocketMqEndpointParserTest extends BasePlatformTestCase {

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
        myFixture.configureByText("RocketCase.java", """
                @org.apache.rocketmq.spring.annotation.RocketMQMessageListener(
                    topic = "notice", consumerGroup = "sms",
                    selectorExpression = "SMS||ALL")
                class RocketCase {
                    org.apache.rocketmq.spring.core.RocketMQTemplate template;
                    void publish() { template.syncSend("notice:SMS", new Object()); }
                }
                """);
        RocketMqEndpointParser parser = new RocketMqEndpointParser(new MqValueResolver(getProject()));
        MqEndpoint producer = parser.parseAnchor(identifier("syncSend"), new LinkedHashSet<>()).orElseThrow();
        MqEndpoint consumer = parser.parseAnchor(identifier("RocketCase"), new LinkedHashSet<>()).orElseThrow();
        assertEquals("notice", producer.destination().topic());
        assertEquals("SMS", producer.destination().tag());
        assertEquals("SMS||ALL", consumer.destination().selectorExpression());
    }

    public void testIncompleteRocketFileIsIsolated() {
        myFixture.configureByText("BrokenRocket.java", "class BrokenRocket { void x() { template.syncSend(");
        assertTrue(new RocketMqEndpointParser(new MqValueResolver(getProject()))
                .parseAnchor(identifier("syncSend"), new LinkedHashSet<>()).isEmpty());
    }

    private PsiIdentifier identifier(String text) {
        return com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                        myFixture.getFile(), PsiIdentifier.class).stream()
                .filter(identifier -> text.equals(identifier.getText()))
                .findFirst().orElseThrow();
    }
}
