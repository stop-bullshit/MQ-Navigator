package cn.luckyh.mqx.parser;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;

import java.nio.file.Path;
import java.util.LinkedHashSet;

public class MqValueResolverTest extends BasePlatformTestCase {

    @Override
    protected String getTestDataPath() {
        return Path.of("src/test/testData").toAbsolutePath().toString();
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.copyDirectoryToProject("stubs", "");
        myFixture.copyFileToProject("config/application.properties", "src/main/resources/application.properties");
    }

    public void testLiteralAndCompileTimeConstant() {
        PsiExpression literal = argument("template.send(\"orders\", new Object())");
        MqValueResolver.ResolvedValue literalValue = new MqValueResolver(getProject())
                .resolveExpression(literal, new LinkedHashSet<>());
        assertEquals("orders", literalValue.value());
        assertEquals(cn.luckyh.mqx.model.MqEndpoint.ResolutionState.STATIC, literalValue.state());

        PsiExpression constant = argument("template.send(PREFIX + SUFFIX, new Object())",
                "static final String PREFIX = \"ord\"; static final String SUFFIX = \"ers\";");
        assertEquals("orders", new MqValueResolver(getProject())
                .resolveExpression(constant, new LinkedHashSet<>()).value());
    }

    public void testDirectValueFieldUsesModuleConfiguration() {
        PsiExpression expression = argument("template.send(this.topic, new Object())",
                "@org.springframework.beans.factory.annotation.Value(\"${mq.topic}\") String topic;");
        LinkedHashSet<Object> dependencies = new LinkedHashSet<>();
        MqValueResolver.ResolvedValue value = new MqValueResolver(getProject())
                .resolveExpression(expression, dependencies);
        assertEquals("configured-orders", value.value());
        assertEquals(cn.luckyh.mqx.model.MqEndpoint.ResolutionState.CONFIG_RESOLVED, value.state());
        assertFalse(dependencies.isEmpty());
    }

    public void testLiteralPlaceholderIsNotConfigurationReference() {
        PsiExpression expression = argument("template.send(\"${mq.topic}\", new Object())");
        MqValueResolver.ResolvedValue value = new MqValueResolver(getProject())
                .resolveExpression(expression, new LinkedHashSet<>());
        assertEquals("${mq.topic}", value.value());
        assertEquals(cn.luckyh.mqx.model.MqEndpoint.ResolutionState.STATIC, value.state());
    }

    public void testLocalAliasAndGetterRemainDynamic() {
        PsiExpression alias = argument("String alias = topic; template.send(alias, new Object())",
                "@org.springframework.beans.factory.annotation.Value(\"${mq.topic}\") String topic;");
        assertEquals(cn.luckyh.mqx.model.MqEndpoint.ResolutionState.DYNAMIC,
                new MqValueResolver(getProject()).resolveExpression(alias, new LinkedHashSet<>()).state());
    }

    public void testLocalSwitchAssignmentsProduceStaticAlternatives() {
        PsiExpression routingKey = argument("""
                String type = "DATA_PRE_PROCESS";
                String key;
                switch (type) {
                    case "DATA_PRE_PROCESS" -> key = "orders.data.ready";
                    default -> key = "orders.other";
                }
                template.send(key, new Object())
                """);
        var values = new MqValueResolver(getProject())
                .resolveExpressionAlternatives(routingKey, new LinkedHashSet<>());
        assertEquals(2, values.size());
        assertEquals("orders.data.ready", values.get(0).value());
        assertEquals("orders.other", values.get(1).value());
    }

    public void testLocalStaticInitializerProducesStaticAlternative() {
        PsiExpression exchange = argument("""
                String exchange = "orders.task.exchange";
                template.send(exchange, new Object())
                """);

        var values = new MqValueResolver(getProject())
                .resolveExpressionAlternatives(exchange, new LinkedHashSet<>());

        assertEquals(1, values.size());
        assertEquals("orders.task.exchange", values.get(0).value());
    }

    public void testEnumConstantGetterResolvesStaticString() {
        PsiExpression routingKey = argument(
                "template.send(RoutingKey.ORDER_CREATED.getCode(), new Object())",
                "enum RoutingKey { ORDER_CREATED(\"orders.created\", \"description\");\n"
                        + "private final String code; private final String description;\n"
                        + "RoutingKey(String code, String description) { this.code = code; this.description = description; }\n"
                        + "public String getCode() { return code; } }");

        MqValueResolver.ResolvedValue value = new MqValueResolver(getProject())
                .resolveExpression(routingKey, new LinkedHashSet<>());

        assertEquals("orders.created", value.value());
        assertEquals(cn.luckyh.mqx.model.MqEndpoint.ResolutionState.STATIC, value.state());
    }

    public void testEnumConstantLombokGetterResolvesStaticStringWithoutMethodBody() {
        PsiExpression routingKey = argument(
                "template.send(RoutingKey.ORDER_CREATED.getCode(), new Object())",
                "@lombok.Getter enum RoutingKey { ORDER_CREATED(\"orders.created\", \"description\");\n"
                        + "private final String code; private final String description;\n"
                        + "RoutingKey(String code, String description) { this.code = code; this.description = description; } }");

        MqValueResolver.ResolvedValue value = new MqValueResolver(getProject())
                .resolveExpression(routingKey, new LinkedHashSet<>());

        assertEquals("orders.created", value.value());
        assertEquals(cn.luckyh.mqx.model.MqEndpoint.ResolutionState.STATIC, value.state());
    }

    private PsiExpression argument(String statement) {
        return argument(statement, "");
    }

    private PsiExpression argument(String statement, String member) {
        PsiJavaFile file = (PsiJavaFile) myFixture.configureByText("ValueCase.java", """
                class ValueCase {
                    org.springframework.amqp.rabbit.core.RabbitTemplate template;
                    %s
                    void send() { %s; }
                }
                """.formatted(member, statement));
        PsiMethod method = file.getClasses()[0].findMethodsByName("send", false)[0];
        var call = com.intellij.psi.util.PsiTreeUtil.findChildOfType(
                method, com.intellij.psi.PsiMethodCallExpression.class);
        assertNotNull(call);
        return call.getArgumentList().getExpressions()[0];
    }
}
