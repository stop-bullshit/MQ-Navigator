package cn.luckyh.mqx.parser;

import cn.luckyh.mqx.model.MqEndpoint;
import cn.luckyh.mqx.navigation.MqNavigationService;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;

/**
 * 枚举派生路由与接口发送封装识别回归。
 *
 * @author heng.wang
 * @since 2026-11-10
 */
public class RabbitMqComputedRoutingTest extends BasePlatformTestCase {

    // 回执实现内部发送位置用于导航和索引断言。
    private PsiIdentifier publishAnchor;

    @Override
    protected String getTestDataPath() {
        return Path.of("src/test/testData").toAbsolutePath().toString();
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // 发送样例复用现有Spring AMQP类型桩。
        myFixture.copyDirectoryToProject("stubs", "");
        configureRoutingModel();
    }

    public void testEnumLookupResolvesOnlyMatchingReceiptKey() {
        // 枚举查找只返回传入code对应的回执路由。
        myFixture.configureByText("LookupCase.java", """
                class LookupCase {
                    org.springframework.amqp.rabbit.core.RabbitTemplate template;
                    void send() {
                        template.convertAndSend("orders.receipts", Routes.ACK.getReceiptKey(), new Object());
                        template.convertAndSend("orders.receipts", Routes.getByCode("ACK").getReceiptKey(), new Object());
                        template.convertAndSend("orders.receipts", Routes.getByCode("NACK").getReceiptKey(), new Object());
                    }
                }
                """);
        MqValueResolver resolver = new MqValueResolver(getProject());
        List<PsiMethodCallExpression> sends = calls("convertAndSend");

        assertEquals(3, sends.size());
        assertEquals("orders.receipt.ack", resolver.resolveExpression(
                sends.get(0).getArgumentList().getExpressions()[1], new LinkedHashSet<>()).value());
        MqValueResolver.ResolvedValue accepted = resolver.resolveExpression(
                sends.get(1).getArgumentList().getExpressions()[1], new LinkedHashSet<>());
        MqValueResolver.ResolvedValue rejected = resolver.resolveExpression(
                sends.get(2).getArgumentList().getExpressions()[1], new LinkedHashSet<>());
        assertEquals(MqEndpoint.ResolutionState.STATIC, accepted.state());
        assertEquals("orders.receipt.ack", accepted.value());
        assertEquals(MqEndpoint.ResolutionState.STATIC, rejected.state());
        assertEquals("orders.receipt.nack", rejected.value());
    }

    public void testLombokLookupUsesConstructorAssignmentsAndMethodBody() {
        // Lombok路由枚举通过构造赋值和查找方法源码解析。
        myFixture.addFileToProject("LombokRoutes.java", """
                @lombok.Getter
                enum LombokRoutes {
                    READY("ACK", "orders.lombok.ack"), FAILED("NACK", "orders.lombok.nack");
                    private final String receiptCode;
                    private final String receiptKey;
                    LombokRoutes(String wireCode, String wireRoute) {
                        this.receiptCode = wireCode;
                        this.receiptKey = wireRoute;
                    }
                    public static LombokRoutes findReceipt(String code) {
                        for (LombokRoutes candidate : values()) {
                            if (org.apache.commons.lang3.StringUtils.equals(candidate.getReceiptCode(), code)) {
                                return candidate;
                            }
                        }
                        throw new IllegalArgumentException(code);
                    }
                }
                """);
        myFixture.configureByText("LombokLookupCase.java", """
                class LombokLookupCase {
                    org.springframework.amqp.rabbit.core.RabbitTemplate template;
                    void send() {
                        template.convertAndSend("orders.receipts", LombokRoutes.findReceipt("ACK").getReceiptKey(), new Object());
                        template.convertAndSend("orders.receipts", LombokRoutes.findReceipt("NACK").getReceiptKey(), new Object());
                    }
                }
                """);
        MqValueResolver resolver = new MqValueResolver(getProject());
        List<PsiMethodCallExpression> sends = calls("convertAndSend");
        assertEquals(2, sends.size());
        MqValueResolver.ResolvedValue accepted = resolver.resolveExpression(
                sends.get(0).getArgumentList().getExpressions()[1], new LinkedHashSet<>());
        MqValueResolver.ResolvedValue rejected = resolver.resolveExpression(
                sends.get(1).getArgumentList().getExpressions()[1], new LinkedHashSet<>());
        assertEquals(MqEndpoint.ResolutionState.STATIC, accepted.state());
        assertEquals("orders.lombok.ack", accepted.value());
        assertEquals(MqEndpoint.ResolutionState.STATIC, rejected.state());
        assertEquals("orders.lombok.nack", rejected.value());
    }

    public void testUnprovenLookupAndComputedGetterStayDynamic() {
        // 额外过滤和优先分支不能被当成单纯code映射。
        myFixture.addFileToProject("GuardedRoutes.java", """
                enum GuardedRoutes {
                    ACK("ACK", "orders.guarded.ack", true), NACK("NACK", "orders.guarded.nack", false);
                    private final String code;
                    private final String receiptKey;
                    private final boolean enabled;
                    GuardedRoutes(String code, String receiptKey, boolean enabled) {
                        this.code = code;
                        this.receiptKey = receiptKey;
                        this.enabled = enabled;
                    }
                    public String getCode() { return code; }
                    public String getReceiptKey() { return receiptKey; }
                    public static GuardedRoutes filtered(String code) {
                        for (GuardedRoutes value : values()) {
                            if (value.enabled && org.apache.commons.lang3.StringUtils.equals(value.getCode(), code)) {
                                return value;
                            }
                        }
                        throw new IllegalArgumentException(code);
                    }
                    public static GuardedRoutes branched(String code) {
                        if ("ACK".equals(code)) { return NACK; }
                        for (GuardedRoutes value : values()) {
                            if (org.apache.commons.lang3.StringUtils.equals(value.getCode(), code)) {
                                return value;
                            }
                        }
                        throw new IllegalArgumentException(code);
                    }
                }
                """);
        // 计算型getter不能仅凭属性名回退到原始字段值。
        myFixture.addFileToProject("ComputedRoutes.java", """
                enum ComputedRoutes {
                    ACK("ACK", "orders.computed.ack");
                    private final String code;
                    private final String receiptKey;
                    ComputedRoutes(String code, String receiptKey) {
                        this.code = code;
                        this.receiptKey = receiptKey;
                    }
                    public String getCode() { return code; }
                    public String getReceiptKey() { return "computed." + receiptKey; }
                    public static ComputedRoutes lookup(String code) {
                        for (ComputedRoutes value : values()) {
                            if (org.apache.commons.lang3.StringUtils.equals(value.getCode(), code)) {
                                return value;
                            }
                        }
                        throw new IllegalArgumentException(code);
                    }
                }
                """);
        // 构造器转换code后不能按同名参数猜测字段原值。
        myFixture.addFileToProject("NormalizedRoutes.java", """
                enum NormalizedRoutes {
                    ACK("ACK", "orders.normalized.ack");
                    private final String code;
                    private final String receiptKey;
                    NormalizedRoutes(String code, String receiptKey) {
                        this.code = code.toLowerCase();
                        this.receiptKey = receiptKey;
                    }
                    public String getCode() { return code; }
                    public String getReceiptKey() { return receiptKey; }
                    public static NormalizedRoutes lookup(String code) {
                        for (NormalizedRoutes value : values()) {
                            if (org.apache.commons.lang3.StringUtils.equals(value.getCode(), code)) {
                                return value;
                            }
                        }
                        throw new IllegalArgumentException(code);
                    }
                }
                """);
        // 构造参数被改写后不能按字段直接赋值反推出原始code。
        myFixture.addFileToProject("ReassignedCodeRoutes.java", """
                enum ReassignedCodeRoutes {
                    ACK("ACK", "orders.reassigned.ack");
                    private final String code;
                    private final String receiptKey;
                    ReassignedCodeRoutes(String code, String receiptKey) {
                        code = code.toLowerCase();
                        this.code = code;
                        this.receiptKey = receiptKey;
                    }
                    public String getCode() { return code; }
                    public String getReceiptKey() { return receiptKey; }
                    public static ReassignedCodeRoutes lookup(String code) {
                        for (ReassignedCodeRoutes value : values()) {
                            if (org.apache.commons.lang3.StringUtils.equals(value.getCode(), code)) {
                                return value;
                            }
                        }
                        throw new IllegalArgumentException(code);
                    }
                }
                """);
        // 枚举构造值递归调用自身查找时保持动态，避免解析栈溢出。
        myFixture.addFileToProject("RecursiveRoutes.java", """
                enum RecursiveRoutes {
                    ACK(RecursiveRoutes.getByCode("ACK").getCode(), "orders.recursive.ack");
                    private final String code;
                    private final String receiptKey;
                    RecursiveRoutes(String code, String receiptKey) {
                        this.code = code;
                        this.receiptKey = receiptKey;
                    }
                    public String getCode() { return code; }
                    public String getReceiptKey() { return receiptKey; }
                    public static RecursiveRoutes getByCode(String code) {
                        for (RecursiveRoutes value : values()) {
                            if (org.apache.commons.lang3.StringUtils.equals(value.getCode(), code)) {
                                return value;
                            }
                        }
                        throw new IllegalArgumentException(code);
                    }
                }
                """);
        myFixture.configureByText("UnprovenLookupCase.java", """
                class UnprovenLookupCase {
                    org.springframework.amqp.rabbit.core.RabbitTemplate template;
                    void send() {
                        template.convertAndSend("orders.receipts", GuardedRoutes.filtered("ACK").getReceiptKey(), new Object());
                        template.convertAndSend("orders.receipts", GuardedRoutes.branched("ACK").getReceiptKey(), new Object());
                        template.convertAndSend("orders.receipts", ComputedRoutes.lookup("ACK").getReceiptKey(), new Object());
                        template.convertAndSend("orders.receipts", ComputedRoutes.ACK.getReceiptKey(), new Object());
                        template.convertAndSend("orders.receipts", NormalizedRoutes.lookup("ACK").getReceiptKey(), new Object());
                        template.convertAndSend("orders.receipts", ReassignedCodeRoutes.lookup("ACK").getReceiptKey(), new Object());
                        template.convertAndSend("orders.receipts", RecursiveRoutes.getByCode("ACK").getReceiptKey(), new Object());
                    }
                }
                """);
        MqValueResolver resolver = new MqValueResolver(getProject());
        List<PsiMethodCallExpression> sends = calls("convertAndSend");
        assertEquals(7, sends.size());
        for (PsiMethodCallExpression send : sends) {
            assertFalse(resolver.resolveExpression(send.getArgumentList().getExpressions()[1],
                    new LinkedHashSet<>()).resolved());
        }
    }

    public void testInternalPublisherMapsInterfaceCallerCodes() {
        // 接口调用的消息编码决定实现内部发送位置的候选路由。
        myFixture.configureByText("ComputedRoutingCase.java", """
                class ComputedRoutingCase {
                    ReceiptNotifier notifier;
                    void accepted() { notifier.notify(MessageCode.ACK.getCode(), new Object()); }
                    void rejected() { notifier.notify(MessageCode.NACK.getCode(), new Object()); }
                }
                """);
        RabbitMqEndpointParser parser = new RabbitMqEndpointParser(new MqValueResolver(getProject()));
        assertEquals(2, calls("notify").size());
        MqEndpoint source = parser.parseAnchor(publishAnchor, new LinkedHashSet<>()).orElseThrow();
        assertEquals(MqEndpoint.ResolutionState.STATIC, source.resolutionState());
        assertEquals("orders.receipts", source.destination().exchange());
        assertTrue(Set.of("orders.receipt.ack", "orders.receipt.nack")
                .contains(source.destination().routingKey()));

        // 同一个内部发送锚点收集两条静态路由。
        List<MqEndpoint> indexed = parser.collectEndpoints(getProject(),
                        GlobalSearchScope.projectScope(getProject()), new LinkedHashSet<>()).stream()
                .filter(endpoint -> endpoint.role() == MqEndpoint.Role.PRODUCER)
                .filter(endpoint -> "ReceiptNotifierImpl.java".equals(endpoint.fileName()))
                .toList();
        assertEquals(2, indexed.size());
        Set<String> routes = new LinkedHashSet<>();
        for (MqEndpoint endpoint : indexed) {
            assertTrue(endpoint.isResolved());
            assertEquals("orders.receipts", endpoint.destination().exchange());
            assertNotNull(endpoint.sourcePointer().getElement());
            assertEquals(publishAnchor.getTextOffset(), endpoint.sourcePointer().getElement().getTextOffset());
            routes.add(endpoint.destination().routingKey());
        }
        assertEquals(Set.of("orders.receipt.ack", "orders.receipt.nack"), routes);
    }

    public void testInternalPublisherFindsBothReceiptListeners() throws Exception {
        // 两个已知业务code共同驱动同一个内部发送位置。
        myFixture.configureByText("ReceiptCallerCase.java", """
                class ReceiptCallerCase {
                    ReceiptNotifier notifier;
                    void accepted() { notifier.notify(MessageCode.ACK.getCode(), new Object()); }
                    void rejected() { notifier.notify(MessageCode.NACK.getCode(), new Object()); }
                }
                """);
        myFixture.addFileToProject("ReceiptListeners.java", """
                class ReceiptListeners {
                    @org.springframework.amqp.rabbit.annotation.RabbitListener(bindings =
                        @org.springframework.amqp.rabbit.annotation.QueueBinding(
                            value = @org.springframework.amqp.rabbit.annotation.Queue("orders.receipt.ack.queue"),
                            exchange = @org.springframework.amqp.rabbit.annotation.Exchange("orders.receipts"),
                            key = "orders.receipt.ack"))
                    void accepted(Object body) {}
                    @org.springframework.amqp.rabbit.annotation.RabbitListener(bindings =
                        @org.springframework.amqp.rabbit.annotation.QueueBinding(
                            value = @org.springframework.amqp.rabbit.annotation.Queue("orders.receipt.nack.queue"),
                            exchange = @org.springframework.amqp.rabbit.annotation.Exchange("orders.receipts"),
                            key = "orders.receipt.nack"))
                    void rejected(Object body) {}
                }
                """);

        // 内部发送导航返回两个准确的回执监听目标。
        Future<MqNavigationService.NavigationOutcome> future = ApplicationManager.getApplication()
                .executeOnPooledThread(() -> ProgressManager.getInstance().runProcess(
                        () -> ReadAction.compute(() -> MqNavigationService.getInstance(getProject())
                                .findTargets(publishAnchor)), new EmptyProgressIndicator()));
        MqNavigationService.NavigationOutcome outcome = future.get();
        assertEquals(MqNavigationService.NavigationStatus.MULTIPLE_MATCHES, outcome.status());
        assertEquals(2, outcome.targets().size());
        Set<String> members = new LinkedHashSet<>();
        for (MqEndpoint target : outcome.targets()) {
            assertEquals(MqEndpoint.Role.CONSUMER, target.role());
            assertEquals("ReceiptListeners.java", target.fileName());
            members.add(target.memberName());
        }
        assertEquals(Set.of("accepted", "rejected"), members);
    }

    public void testRepeatedCallerBranchesDoNotExpandExponentially() {
        // 二十层汇合调用在计时前创建，两个入口分别提供已知code。
        StringBuilder source = new StringBuilder("""
                class RepeatedCallerCase {
                    ReceiptNotifier notifier;
                    void left0(String code) { notifier.notify(code, new Object()); }
                    void right0(String code) { notifier.notify(code, new Object()); }
                """);
        for (int level = 1; level <= 20; level++) {
            source.append("void left").append(level).append("(String code) { left")
                    .append(level - 1).append("(code); right").append(level - 1).append("(code); }\n");
            source.append("void right").append(level).append("(String code) { left")
                    .append(level - 1).append("(code); right").append(level - 1).append("(code); }\n");
        }
        source.append("""
                    void entry() {
                        left20(MessageCode.ACK.getCode());
                        right20(MessageCode.NACK.getCode());
                    }
                }
                """);
        myFixture.configureByText("RepeatedCallerCase.java", source.toString());
        RabbitMqEndpointParser parser = new RabbitMqEndpointParser(new MqValueResolver(getProject()));

        // 内部发送解析按源码表达式收敛，不能按所有逆向执行路径重复展开。
        long started = System.nanoTime();
        MqEndpoint endpoint = parser.parseAnchor(publishAnchor, new LinkedHashSet<>()).orElseThrow();
        long elapsed = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertEquals(MqEndpoint.ResolutionState.STATIC, endpoint.resolutionState());
        assertEquals("orders.receipts", endpoint.destination().exchange());
        assertTrue(Set.of("orders.receipt.ack", "orders.receipt.nack")
                .contains(endpoint.destination().routingKey()));
        assertTrue("Reverse caller traversal must not expand once per execution path: " + elapsed + " ms",
                elapsed < 10_000);

        // 汇合路径去重仍保留两个入口code对应的路由。
        List<MqEndpoint> indexed = parser.collectEndpoints(getProject(),
                        GlobalSearchScope.projectScope(getProject()), new LinkedHashSet<>()).stream()
                .filter(item -> item.role() == MqEndpoint.Role.PRODUCER)
                .filter(item -> "ReceiptNotifierImpl.java".equals(item.fileName()))
                .toList();
        Set<String> routes = new LinkedHashSet<>();
        for (MqEndpoint item : indexed) {
            assertTrue(item.isResolved());
            routes.add(item.destination().routingKey());
        }
        assertEquals(Set.of("orders.receipt.ack", "orders.receipt.nack"), routes);
    }

    public void testUnknownAndDynamicCodesDoNotBecomeStaticRoutes() {
        // 未知和运行时code不能展开成全部枚举路由。
        myFixture.configureByText("UnknownRoutingCase.java", """
                class UnknownRoutingCase {
                    ReceiptNotifier notifier;
                    org.springframework.amqp.rabbit.core.RabbitTemplate template;
                    void send(String runtimeCode) {
                        notifier.notify("MISSING", new Object());
                        notifier.notify(runtimeCode, new Object());
                        template.convertAndSend("orders.receipts", Routes.getByCode("MISSING").getReceiptKey(), new Object());
                        template.convertAndSend("orders.receipts", Routes.getByCode(runtimeCode).getReceiptKey(), new Object());
                    }
                }
                """);
        RabbitMqEndpointParser parser = new RabbitMqEndpointParser(new MqValueResolver(getProject()));
        MqValueResolver resolver = new MqValueResolver(getProject());
        assertEquals(2, calls("notify").size());
        assertEquals(2, calls("convertAndSend").size());
        var source = parser.parseAnchor(publishAnchor, new LinkedHashSet<>());
        assertTrue(source.isEmpty() || !source.get().isResolved());
        for (PsiMethodCallExpression send : calls("convertAndSend")) {
            assertFalse(resolver.resolveExpression(send.getArgumentList().getExpressions()[1],
                    new LinkedHashSet<>()).resolved());
        }

        // 未解析输入不能将内部发送位置写成静态端点。
        List<MqEndpoint> indexed = parser.collectEndpoints(getProject(),
                GlobalSearchScope.projectScope(getProject()), new LinkedHashSet<>());
        assertFalse(indexed.stream().anyMatch(endpoint -> endpoint.role() == MqEndpoint.Role.PRODUCER
                && "ReceiptNotifierImpl.java".equals(endpoint.fileName()) && endpoint.isResolved()));
    }

    public void testRuntimeReassignmentDoesNotReuseInitialLookup() {
        // 回执路由被运行时值覆盖后不能继续沿用初始枚举路由。
        PsiFile implementation = publishAnchor.getContainingFile();
        Document document = PsiDocumentManager.getInstance(getProject()).getDocument(implementation);
        assertNotNull(document);
        String original = implementation.getText();
        String updated = original.replace("publisher.publish(EXCHANGE, key, body);",
                "key = body.toString();\n        publisher.publish(EXCHANGE, key, body);");
        assertFalse(original.equals(updated));
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            document.setText(updated);
            PsiDocumentManager.getInstance(getProject()).commitDocument(document);
        });
        publishAnchor = PsiTreeUtil.findChildrenOfType(implementation, PsiIdentifier.class).stream()
                .filter(identifier -> "publish".equals(identifier.getText()))
                .findFirst().orElseThrow();
        myFixture.configureByText("ReassignedRoutingCase.java", """
                class ReassignedRoutingCase {
                    ReceiptNotifier notifier;
                    void send() { notifier.notify(MessageCode.ACK.getCode(), new Object()); }
                }
                """);
        RabbitMqEndpointParser parser = new RabbitMqEndpointParser(new MqValueResolver(getProject()));
        var source = parser.parseAnchor(publishAnchor, new LinkedHashSet<>());
        assertTrue(source.isEmpty() || !source.get().isResolved());
        List<MqEndpoint> indexed = parser.collectEndpoints(getProject(),
                GlobalSearchScope.projectScope(getProject()), new LinkedHashSet<>());
        assertFalse(indexed.stream().anyMatch(endpoint -> endpoint.role() == MqEndpoint.Role.PRODUCER
                && "ReceiptNotifierImpl.java".equals(endpoint.fileName()) && endpoint.isResolved()));
    }

    public void testReassignedCallerParameterDoesNotReuseEarlierCode() {
        // 同一调用参数在两次发送之间被改写，第二次不能复用先前code。
        myFixture.configureByText("ReassignedCallerCase.java", """
                class ReassignedCallerCase {
                    ReceiptNotifier notifier;
                    void relay(String code, Object body) {
                        notifier.notify(code, body);
                        code = body.toString();
                        notifier.notify(code, body);
                    }
                    void entry() { relay(MessageCode.ACK.getCode(), new Object()); }
                }
                """);
        assertEquals(2, calls("notify").size());
        RabbitMqEndpointParser parser = new RabbitMqEndpointParser(new MqValueResolver(getProject()));
        var source = parser.parseAnchor(publishAnchor, new LinkedHashSet<>());
        assertTrue(source.isEmpty() || !source.get().isResolved());

        // 含运行时改写的调用上下文不能把内部发送位置写成静态路由。
        List<MqEndpoint> indexed = parser.collectEndpoints(getProject(),
                GlobalSearchScope.projectScope(getProject()), new LinkedHashSet<>());
        assertFalse(indexed.stream().anyMatch(endpoint -> endpoint.role() == MqEndpoint.Role.PRODUCER
                && "ReceiptNotifierImpl.java".equals(endpoint.fileName()) && endpoint.isResolved()));
    }

    public void testMultipleNotifierImplementationsAreNotChosenArbitrarily() {
        // 接口有多个实现时不能任意选择其中一个交换机。
        myFixture.addFileToProject("OtherReceiptNotifier.java", """
                class OtherReceiptNotifier implements ReceiptNotifier {
                    ReceiptPublisher publisher;
                    public void notify(String messageCode, Object body) {
                        String key = Routes.getByCode(messageCode).getReceiptKey();
                        publisher.publish("other.receipts", key, body);
                    }
                }
                """);
        myFixture.configureByText("AmbiguousRoutingCase.java", """
                class AmbiguousRoutingCase {
                    ReceiptNotifier notifier;
                    void send() { notifier.notify(MessageCode.ACK.getCode(), new Object()); }
                }
                """);
        RabbitMqEndpointParser parser = new RabbitMqEndpointParser(new MqValueResolver(getProject()));
        var endpoint = parser.parseAnchor(publishAnchor, new LinkedHashSet<>());

        assertTrue(endpoint.isEmpty() || !endpoint.get().isResolved());
        List<MqEndpoint> indexed = parser.collectEndpoints(getProject(),
                GlobalSearchScope.projectScope(getProject()), new LinkedHashSet<>());
        assertFalse(indexed.stream().anyMatch(item -> item.role() == MqEndpoint.Role.PRODUCER
                && "ReceiptNotifierImpl.java".equals(item.fileName()) && item.isResolved()));
    }

    // 消息编码、枚举路由和接口发送实现组成通用源码样例。
    private void configureRoutingModel() {
        myFixture.addFileToProject("org/apache/commons/lang3/StringUtils.java", """
                package org.apache.commons.lang3;
                public class StringUtils {
                    public static boolean equals(CharSequence left, CharSequence right) {
                        return java.util.Objects.equals(left, right);
                    }
                }
                """);
        myFixture.addFileToProject("MessageCode.java", """
                enum MessageCode {
                    ACK("ACK"), NACK("NACK");
                    private final String code;
                    MessageCode(String code) { this.code = code; }
                    public String getCode() { return code; }
                }
                """);
        myFixture.addFileToProject("Routes.java", """
                import org.apache.commons.lang3.StringUtils;
                enum Routes {
                    ACK(MessageCode.ACK.getCode(), "orders.receipt.ack"),
                    NACK(MessageCode.NACK.getCode(), "orders.receipt.nack");
                    private final String receiptMessageCode;
                    private final String receiptKey;
                    Routes(String receiptMessageCode, String receiptKey) {
                        this.receiptMessageCode = receiptMessageCode;
                        this.receiptKey = receiptKey;
                    }
                    public String getReceiptMessageCode() { return receiptMessageCode; }
                    public String getReceiptKey() { return receiptKey; }
                    public static Routes getByCode(String code) {
                        for (Routes value : values()) {
                            if (StringUtils.equals(value.getReceiptMessageCode(), code)) {
                                return value;
                            }
                        }
                        throw new IllegalArgumentException(code);
                    }
                }
                """);
        myFixture.addFileToProject("ReceiptNotifier.java", """
                interface ReceiptNotifier {
                    void notify(String messageCode, Object body);
                }
                """);
        myFixture.addFileToProject("ReceiptPublisher.java", """
                class ReceiptPublisher {
                    org.springframework.amqp.rabbit.core.RabbitTemplate template;
                    void publish(String exchange, String routingKey, Object body) {
                        template.convertAndSend(exchange, routingKey, body);
                    }
                }
                """);
        PsiFile notifierFile = myFixture.addFileToProject("ReceiptNotifierImpl.java", """
                class ReceiptNotifierImpl implements ReceiptNotifier {
                    static final String EXCHANGE = "orders.receipts";
                    ReceiptPublisher publisher;
                    public void notify(String messageCode, Object body) {
                        String key = Routes.getByCode(messageCode).getReceiptKey();
                        publisher.publish(EXCHANGE, key, body);
                    }
                }
                """);
        // 发送标记锚定实现文件中的publish调用，而不是外层接口调用。
        publishAnchor = PsiTreeUtil.findChildrenOfType(notifierFile, PsiIdentifier.class).stream()
                .filter(identifier -> "publish".equals(identifier.getText()))
                .findFirst().orElseThrow();
    }

    // 调用锚点从当前业务文件中按真实方法调用筛选。
    private List<PsiMethodCallExpression> calls(String methodName) {
        return PsiTreeUtil.findChildrenOfType(myFixture.getFile(), PsiMethodCallExpression.class).stream()
                .filter(call -> methodName.equals(call.getMethodExpression().getReferenceName()))
                .toList();
    }
}
