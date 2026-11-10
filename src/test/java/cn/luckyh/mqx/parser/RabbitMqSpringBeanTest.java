package cn.luckyh.mqx.parser;

import cn.luckyh.mqx.model.MqEndpoint;
import cn.luckyh.mqx.navigation.MqNavigationService;
import cn.luckyh.mqx.spring.SpringBeanSource;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.extensions.ExtensionPoint;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.SimpleModificationTracker;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.testFramework.ExtensionTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Spring定义来源补充RabbitMQ拓扑解析回归。
 *
 * @author heng.wang
 * @since 2026-11-10
 */
public class RabbitMqSpringBeanTest extends BasePlatformTestCase {

    private PsiMethod queueBean;
    private PsiMethod exchangeBean;
    private SimpleModificationTracker modelChanges;

    @Override
    protected String getTestDataPath() {
        return Path.of("src/test/testData").toAbsolutePath().toString();
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Java独立测试按需注册Bean来源扩展点，并在本测试结束后卸载。
        var extensionArea = ApplicationManager.getApplication().getExtensionArea();
        String extensionName = SpringBeanSource.EP_NAME.getName();
        if (!extensionArea.hasExtensionPoint(extensionName)) {
            extensionArea.registerExtensionPoint(extensionName, SpringBeanSource.class.getName(),
                    ExtensionPoint.Kind.INTERFACE, true);
            Disposer.register(getTestRootDisposable(), () -> extensionArea.unregisterExtensionPoint(extensionName));
        }
        // Bean模型样例复用已有AMQP类型桩，不创建真实Spring容器。
        myFixture.copyDirectoryToProject("stubs", "");
        modelChanges = new SimpleModificationTracker();
        PsiJavaFile definitions = (PsiJavaFile) myFixture.addFileToProject("BeanFactories.java", """
                import org.springframework.context.annotation.Bean;
                import org.springframework.amqp.core.Queue;
                import org.springframework.amqp.core.QueueBuilder;
                import org.springframework.amqp.core.TopicExchange;
                class BeanFactories {
                    @Bean(name = {"ordersQueue", "receiptQueue"})
                    Queue queueFactory() { return QueueBuilder.durable("orders.work.queue").build(); }
                    @Bean(name = {"ordersExchange", "eventsAlias"})
                    TopicExchange exchangeFactory() {
                        return new TopicExchange("orders.events", true, false);
                    }
                }
                """);
        queueBean = definitions.getClasses()[0].findMethodsByName("queueFactory", false)[0];
        exchangeBean = definitions.getClasses()[0].findMethodsByName("exchangeFactory", false)[0];
    }

    public void testBeanParametersUseQualifiedOriginsAndNavigate() throws Exception {
        // Bean限定名称定位其它配置类的工厂，MQ名称读取实际构造值。
        installModel(defaultNames(), List.of(queueBean, exchangeBean));
        myFixture.configureByText("BeanParameterCase.java", """
                import org.springframework.context.annotation.Bean;
                import org.springframework.beans.factory.annotation.Qualifier;
                import org.springframework.amqp.core.*;
                class BeanParameterCase {
                    @Bean Binding binding(@Qualifier("receiptQueue") Queue queue,
                            @Qualifier("eventsAlias") TopicExchange exchange) {
                        return BindingBuilder.bind(queue).to(exchange).with("#.created");
                    }
                    org.springframework.amqp.rabbit.core.RabbitTemplate template;
                    void publish() { template.convertAndSend("orders.events", "orders.created", new Object()); }
                    @org.springframework.amqp.rabbit.annotation.RabbitListener(queues = "orders.work.queue")
                    void consume(Object body) {}
                }
                """);
        LinkedHashSet<Object> dependencies = new LinkedHashSet<>();
        MqEndpoint binding = binding("binding", dependencies);
        assertTrue(binding.isResolved());
        assertEquals("orders.work.queue", binding.destination().queue());
        assertEquals("orders.events", binding.destination().exchange());
        assertEquals("topic", binding.destination().exchangeType());
        assertEquals(List.of("#.created"), binding.destination().bindingKeys());
        assertTrue(dependencies.contains(modelChanges));

        // Topic绑定使真实发送端准确跳转到按队列声明的消费者。
        PsiIdentifier producer = PsiTreeUtil.findChildrenOfType(myFixture.getFile(), PsiIdentifier.class).stream()
                .filter(identifier -> "convertAndSend".equals(identifier.getText())).findFirst().orElseThrow();
        Future<MqNavigationService.NavigationOutcome> future = ApplicationManager.getApplication()
                .executeOnPooledThread(() -> ProgressManager.getInstance().runProcess(
                        () -> ReadAction.compute(() -> MqNavigationService.getInstance(getProject())
                                .findTargets(producer)), new EmptyProgressIndicator()));
        MqNavigationService.NavigationOutcome outcome = future.get(20, TimeUnit.SECONDS);
        assertEquals(MqNavigationService.NavigationStatus.ONE_MATCH, outcome.status());
        assertEquals("consume", outcome.targets().get(0).memberName());
    }

    public void testInjectedFieldsUseAliasesInsteadOfMqNames() {
        // 字段限定符和资源名称可以使用Bean别名，但不能把别名当成MQ名称。
        installModel(defaultNames(), List.of(queueBean, exchangeBean));
        configureBinding("""
                @org.springframework.beans.factory.annotation.Autowired
                @org.springframework.beans.factory.annotation.Qualifier("receiptQueue")
                Queue queue;
                @jakarta.annotation.Resource(name = "eventsAlias") TopicExchange exchange;
                @Bean Binding binding() { return BindingBuilder.bind(queue).to(exchange).with("orders.created"); }
                """);
        MqEndpoint binding = binding("binding", new LinkedHashSet<>());
        assertTrue(binding.isResolved());
        assertEquals("orders.work.queue", binding.destination().queue());
        assertEquals("orders.events", binding.destination().exchange());
        assertEquals("topic", binding.destination().exchangeType());
    }

    public void testUniqueTypeUsesDeclaredExchangeSubtype() {
        // Topic字段按声明子类型筛选，不与同上下文的Direct交换机混淆。
        PsiJavaFile other = (PsiJavaFile) myFixture.addFileToProject("OtherExchange.java", """
                import org.springframework.context.annotation.Bean;
                import org.springframework.amqp.core.DirectExchange;
                class OtherExchange {
                    @Bean DirectExchange directFactory() { return new DirectExchange("other.events", true, false); }
                }
                """);
        PsiMethod direct = other.getClasses()[0].findMethodsByName("directFactory", false)[0];
        installModel(Map.of(), List.of(queueBean, exchangeBean, direct));
        configureBinding("""
                @org.springframework.beans.factory.annotation.Autowired Queue injectedQueue;
                @org.springframework.beans.factory.annotation.Autowired TopicExchange injectedExchange;
                @Bean Binding binding() {
                    return BindingBuilder.bind(injectedQueue).to(injectedExchange).with("orders.created");
                }
                """);
        MqEndpoint binding = binding("binding", new LinkedHashSet<>());
        assertTrue(binding.isResolved());
        assertEquals("orders.work.queue", binding.destination().queue());
        assertEquals("orders.events", binding.destination().exchange());
        assertEquals("topic", binding.destination().exchangeType());
    }

    public void testMultipleTypeCandidatesStayDynamic() {
        // 同一类型存在多个Bean时不任意选择第一个Queue。
        PsiJavaFile other = (PsiJavaFile) myFixture.addFileToProject("OtherQueue.java", """
                import org.springframework.context.annotation.Bean;
                import org.springframework.amqp.core.Queue;
                class OtherQueue { @Bean Queue auditQueue() { return new Queue("audit.queue"); } }
                """);
        PsiMethod second = other.getClasses()[0].findMethodsByName("auditQueue", false)[0];
        installModel(defaultNames(), List.of(queueBean, exchangeBean, second));
        configureBinding("""
                @Bean Binding binding(Queue queue, TopicExchange exchange) {
                    return BindingBuilder.bind(queue).to(exchange).with("orders.created");
                }
                """);
        assertFalse(binding("binding", new LinkedHashSet<>()).isResolved());
    }

    public void testMissingModelPreservesDirectFactoryCalls() {
        // 没有Spring来源时注入位置保持动态，原工厂方法调用照常解析。
        ExtensionTestUtil.maskExtensions(SpringBeanSource.EP_NAME, List.of(), getTestRootDisposable());
        configureBinding("""
                BeanFactories definitions;
                @Bean Binding injected(Queue queue, TopicExchange exchange) {
                    return BindingBuilder.bind(queue).to(exchange).with("orders.created");
                }
                void direct() {
                    BindingBuilder.bind(definitions.queueFactory()).to(definitions.exchangeFactory())
                        .with("orders.created");
                }
                """);
        assertFalse(binding("injected", new LinkedHashSet<>()).isResolved());
        MqEndpoint direct = binding("direct", new LinkedHashSet<>());
        assertTrue(direct.isResolved());
        assertEquals("orders.work.queue", direct.destination().queue());
        assertEquals("orders.events", direct.destination().exchange());
    }

    public void testNamedWrongTypeOrOpaqueDefinitionDoesNotFallBack() {
        // 名称已存在但类型或来源不适用时，不能退化为其它同类型Bean。
        PsiJavaFile wrong = (PsiJavaFile) myFixture.addFileToProject("WrongNamedBeans.java", """
                import org.springframework.context.annotation.Bean;
                import org.springframework.amqp.core.TopicExchange;
                class WrongNamedBeans {
                    @Bean("wrongQueue") TopicExchange wrongFactory() {
                        return new TopicExchange("wrong.events", true, false);
                    }
                }
                class ComponentQueue extends org.springframework.amqp.core.Queue {
                    ComponentQueue() { super("component.queue"); }
                }
                """);
        PsiMethod wrongType = wrong.getClasses()[0].findMethodsByName("wrongFactory", false)[0];
        PsiClass component = wrong.getClasses()[1];
        Map<String, List<PsiElement>> named = new LinkedHashMap<>(defaultNames());
        named.put("wrongQueue", List.of(wrongType));
        named.put("componentQueue", List.of(component));
        named.put("noSourceQueue", Collections.singletonList(null));
        installModel(named, List.of(queueBean, exchangeBean));
        configureBinding("""
                @javax.annotation.Resource(name = "wrongQueue") Queue wrong;
                @javax.annotation.Resource(name = "componentQueue") Queue component;
                @javax.annotation.Resource(name = "noSourceQueue") Queue opaque;
                @javax.annotation.Resource(name = "eventsAlias") TopicExchange exchange;
                @Bean Binding wrongType() { return BindingBuilder.bind(wrong).to(exchange).with("orders.created"); }
                @Bean Binding nonMethod() { return BindingBuilder.bind(component).to(exchange).with("orders.created"); }
                @Bean Binding noSource() { return BindingBuilder.bind(opaque).to(exchange).with("orders.created"); }
                """);
        assertFalse(binding("wrongType", new LinkedHashSet<>()).isResolved());
        assertFalse(binding("nonMethod", new LinkedHashSet<>()).isResolved());
        assertFalse(binding("noSource", new LinkedHashSet<>()).isResolved());
    }

    public void testResourceDefaultNameOnlyFallsBackWhenAbsent() {
        // 默认资源名称缺失时按类型定位，名称存在但错误类型时保留动态。
        Map<String, List<PsiElement>> named = new LinkedHashMap<>(defaultNames());
        named.put("wrongQueue", List.of(exchangeBean));
        named.put("opaqueQueue", Collections.singletonList(null));
        installModel(named, List.of(queueBean, exchangeBean));
        configureBinding("""
                @jakarta.annotation.Resource Queue unregisteredQueue;
                @jakarta.annotation.Resource Queue wrongQueue;
                @jakarta.annotation.Resource Queue opaqueQueue;
                @jakarta.annotation.Resource(name = "eventsAlias") TopicExchange exchange;
                @Bean Binding absentName() {
                    return BindingBuilder.bind(unregisteredQueue).to(exchange).with("orders.created");
                }
                @Bean Binding wrongName() { return BindingBuilder.bind(wrongQueue).to(exchange).with("orders.created"); }
                @Bean Binding opaqueName() { return BindingBuilder.bind(opaqueQueue).to(exchange).with("orders.created"); }
                """);
        assertTrue(binding("absentName", new LinkedHashSet<>()).isResolved());
        assertFalse(binding("wrongName", new LinkedHashSet<>()).isResolved());
        assertFalse(binding("opaqueName", new LinkedHashSet<>()).isResolved());
    }

    public void testFactoryUsesReturnedObjectInsteadOfEarlierCreation() {
        // 工厂内无关构造不能覆盖实际返回的Queue或Exchange。
        PsiJavaFile definitions = (PsiJavaFile) myFixture.addFileToProject("ReturnedFactories.java", """
                import org.springframework.context.annotation.Bean;
                import org.springframework.amqp.core.*;
                class ReturnedFactories {
                    @Bean("ordersQueue") Queue actualQueue() {
                        Queue ignored = QueueBuilder.durable("discard.queue").build();
                        Queue actual = QueueBuilder.durable("returned.queue").quorum().build();
                        return actual;
                    }
                    @Bean("ordersExchange") TopicExchange actualExchange() {
                        TopicExchange ignored = new TopicExchange("discard.events", true, false);
                        return new TopicExchange("returned.events", true, false);
                    }
                    Queue fixedQueue(Queue ignored) {
                        Queue unused = new Queue("unused.parameter.queue");
                        return new Queue("fixed.return.queue");
                    }
                }
                """);
        PsiMethod queue = definitions.getClasses()[0].findMethodsByName("actualQueue", false)[0];
        PsiMethod exchange = definitions.getClasses()[0].findMethodsByName("actualExchange", false)[0];
        installModel(Map.of("ordersQueue", List.of(queue), "ordersExchange", List.of(exchange)),
                List.of(queue, exchange));
        configureBinding("""
                ReturnedFactories definitions;
                @Bean Binding binding(@org.springframework.beans.factory.annotation.Qualifier("ordersQueue") Queue queue,
                        @org.springframework.beans.factory.annotation.Qualifier("ordersExchange") TopicExchange exchange) {
                    return BindingBuilder.bind(queue).to(exchange).with("orders.created");
                }
                void fixedReturn() {
                    BindingBuilder.bind(definitions.fixedQueue(new Queue("manual.queue")))
                        .to(definitions.actualExchange()).with("orders.created");
                }
                """);
        MqEndpoint binding = binding("binding", new LinkedHashSet<>());
        assertTrue(binding.isResolved());
        assertEquals("returned.queue", binding.destination().queue());
        assertEquals("returned.events", binding.destination().exchange());
        // 直接工厂的未读取参数不影响固定返回对象的静态名称。
        MqEndpoint fixed = binding("fixedReturn", new LinkedHashSet<>());
        assertTrue(fixed.isResolved());
        assertEquals("fixed.return.queue", fixed.destination().queue());
        assertEquals("returned.events", fixed.destination().exchange());
    }

    public void testDirectFactoryArgumentsAreNotContainerInjected() {
        // 直接Java调用传入的对象不能被同一参数上的Bean限定符覆盖。
        PsiJavaFile definitions = (PsiJavaFile) myFixture.addFileToProject("EchoFactories.java", """
                import org.springframework.context.annotation.Bean;
                import org.springframework.beans.factory.annotation.Qualifier;
                import org.springframework.amqp.core.Queue;
                class EchoFactories {
                    @Bean("echoQueue") Queue echo(@Qualifier("ordersQueue") Queue supplied) { return supplied; }
                }
                """);
        PsiMethod echo = definitions.getClasses()[0].findMethodsByName("echo", false)[0];
        Map<String, List<PsiElement>> named = new LinkedHashMap<>(defaultNames());
        named.put("echoQueue", List.of(echo));
        installModel(named, List.of(queueBean, exchangeBean, echo));
        configureBinding("""
                EchoFactories definitions;
                @javax.annotation.Resource(name = "echoQueue") Queue injectedQueue;
                @javax.annotation.Resource(name = "eventsAlias") TopicExchange exchange;
                void direct() {
                    BindingBuilder.bind(definitions.echo(new Queue("manual.queue")))
                        .to(exchange).with("orders.created");
                }
                @Bean Binding containerBinding() {
                    return BindingBuilder.bind(injectedQueue).to(exchange).with("orders.created");
                }
                """);
        assertFalse(binding("direct", new LinkedHashSet<>()).isResolved());
        // 模型选中的工厂仍按容器语义解析其Bean参数。
        MqEndpoint container = binding("containerBinding", new LinkedHashSet<>());
        assertTrue(container.isResolved());
        assertEquals("orders.work.queue", container.destination().queue());
    }

    public void testCyclicFactoryDefinitionsStayDynamic() {
        // 互相返回的工厂方法不能无限递归或猜测Bean名称。
        PsiJavaFile definitions = (PsiJavaFile) myFixture.addFileToProject("CyclicFactories.java", """
                import org.springframework.context.annotation.Bean;
                import org.springframework.amqp.core.Queue;
                class CyclicFactories {
                    @Bean("ordersQueue") Queue first() { return second(); }
                    Queue second() { return first(); }
                }
                """);
        PsiMethod queue = definitions.getClasses()[0].findMethodsByName("first", false)[0];
        installModel(Map.of("ordersQueue", List.of(queue), "ordersExchange", List.of(exchangeBean)),
                List.of(queue, exchangeBean));
        configureBinding("""
                @Bean Binding binding(@org.springframework.beans.factory.annotation.Qualifier("ordersQueue") Queue queue,
                        @org.springframework.beans.factory.annotation.Qualifier("ordersExchange") TopicExchange exchange) {
                    return BindingBuilder.bind(queue).to(exchange).with("orders.created");
                }
                """);
        assertFalse(binding("binding", new LinkedHashSet<>()).isResolved());
    }

    public void testBusinessMethodParametersAreNotInjectedBeans() {
        // 普通业务方法的Queue参数不能仅按类型替换为Spring Bean。
        installModel(defaultNames(), List.of(queueBean, exchangeBean));
        configureBinding("""
                void binding(Queue queue, TopicExchange exchange) {
                    BindingBuilder.bind(queue).to(exchange).with("orders.created");
                }
                """);
        assertFalse(binding("binding", new LinkedHashSet<>()).isResolved());
    }

    public void testAutowireCandidateOnlyAffectsTypeInjection() {
        // 禁止类型注入的Bean仍可被明确的Resource名称引用。
        PsiJavaFile definitions = (PsiJavaFile) myFixture.addFileToProject("DisabledCandidate.java", """
                import org.springframework.context.annotation.Bean;
                import org.springframework.amqp.core.Queue;
                class DisabledCandidate {
                    @Bean(name = "disabledQueue", autowireCandidate = false)
                    Queue disabled() { return new Queue("disabled.queue"); }
                }
                """);
        PsiMethod disabled = definitions.getClasses()[0].findMethodsByName("disabled", false)[0];
        installModel(Map.of("disabledQueue", List.of(disabled), "ordersExchange", List.of(exchangeBean)),
                List.of(disabled, exchangeBean));
        configureBinding("""
                @org.springframework.beans.factory.annotation.Autowired Queue automatic;
                @org.springframework.beans.factory.annotation.Autowired
                @org.springframework.beans.factory.annotation.Qualifier("disabledQueue") Queue qualified;
                @javax.annotation.Resource(name = "disabledQueue") Queue named;
                @javax.annotation.Resource(name = "ordersExchange") TopicExchange exchange;
                @Bean Binding typeBinding() {
                    return BindingBuilder.bind(automatic).to(exchange).with("orders.created");
                }
                @Bean Binding namedBinding() {
                    return BindingBuilder.bind(named).to(exchange).with("orders.created");
                }
                @Bean Binding qualifiedBinding() {
                    return BindingBuilder.bind(qualified).to(exchange).with("orders.created");
                }
                """);
        assertFalse(binding("typeBinding", new LinkedHashSet<>()).isResolved());
        assertFalse(binding("qualifiedBinding", new LinkedHashSet<>()).isResolved());
        MqEndpoint named = binding("namedBinding", new LinkedHashSet<>());
        assertTrue(named.isResolved());
        assertEquals("disabled.queue", named.destination().queue());
    }

    public void testCustomQualifierDoesNotFallBackToUniqueType() {
        // 尚未支持的自定义限定注解不能被当作未指定限定符。
        myFixture.addFileToProject("ExternalQueue.java", """
                @org.springframework.beans.factory.annotation.Qualifier("missingQueue")
                @interface ExternalQueue {}
                """);
        installModel(defaultNames(), List.of(queueBean, exchangeBean));
        configureBinding("""
                @org.springframework.beans.factory.annotation.Autowired @ExternalQueue Queue queue;
                @javax.annotation.Resource(name = "eventsAlias") TopicExchange exchange;
                @Bean Binding binding() { return BindingBuilder.bind(queue).to(exchange).with("orders.created"); }
                """);
        assertFalse(binding("binding", new LinkedHashSet<>()).isResolved());
    }

    public void testModelDependencyInvalidatesCachedTopology() throws Exception {
        // 模型定义切换只修改模型依赖，不依靠源码变动刷新缓存。
        PsiJavaFile alternate = (PsiJavaFile) myFixture.addFileToProject("AlternateQueue.java", """
                import org.springframework.context.annotation.Bean;
                import org.springframework.amqp.core.Queue;
                class AlternateQueue {
                    @Bean("ordersQueue") Queue alternateFactory() { return new Queue("alternate.work.queue"); }
                }
                """);
        PsiMethod alternateQueue = alternate.getClasses()[0].findMethodsByName("alternateFactory", false)[0];
        Map<String, List<PsiElement>> named = new LinkedHashMap<>(defaultNames());
        installModel(named, List.of(queueBean, exchangeBean, alternateQueue));
        configureBinding("""
                @javax.annotation.Resource(name = "ordersQueue") Queue queue;
                @javax.annotation.Resource(name = "eventsAlias") TopicExchange exchange;
                @Bean Binding binding() { return BindingBuilder.bind(queue).to(exchange).with("orders.created"); }
                """);
        MqNavigationService service = MqNavigationService.getInstance(getProject());
        List<MqEndpoint> before = readEndpoints(service);
        // 模型参与的快照标记为不可跨会话直接恢复。
        assertTrue(service.getState().springModelDependent);
        assertTrue(before.stream().anyMatch(endpoint -> endpoint.role() == MqEndpoint.Role.BINDING
                && "orders.work.queue".equals(endpoint.destination().queue())));

        // Spring上下文变化触发原服务重新定位Bean定义。
        named.put("ordersQueue", List.of(alternateQueue));
        modelChanges.incModificationCount();
        List<MqEndpoint> after = readEndpoints(service);
        assertTrue(after.stream().anyMatch(endpoint -> endpoint.role() == MqEndpoint.Role.BINDING
                && "alternate.work.queue".equals(endpoint.destination().queue())));
        assertFalse(after.stream().anyMatch(endpoint -> endpoint.role() == MqEndpoint.Role.BINDING
                && "orders.work.queue".equals(endpoint.destination().queue())));
    }

    // 模拟Spring定义查询，仅隔离模型API，不计算RabbitMQ对象的名称。
    private void installModel(Map<String, List<PsiElement>> named, List<PsiElement> typed) {
        SpringBeanSource source = (context, requiredType, beanName, dependencies) -> {
            dependencies.add(modelChanges);
            if (beanName != null) {
                return named.getOrDefault(beanName, List.of());
            }
            assertNotNull("Bean类型查询必须提供类型", requiredType);
            List<PsiElement> candidates = new ArrayList<>();
            for (PsiElement definition : typed) {
                if (definition instanceof PsiMethod method && method.getReturnType() != null
                        && TypeConversionUtil.isAssignable(requiredType, method.getReturnType())) {
                    candidates.add(method);
                }
            }
            return candidates;
        };
        ExtensionTestUtil.maskExtensions(SpringBeanSource.EP_NAME, List.of(source), getTestRootDisposable());
    }

    // 名称和别名均指向同一工厂定义，且与MQ运行名称不同。
    private Map<String, List<PsiElement>> defaultNames() {
        return Map.of("ordersQueue", List.of(queueBean), "receiptQueue", List.of(queueBean),
                "ordersExchange", List.of(exchangeBean), "eventsAlias", List.of(exchangeBean));
    }

    // 绑定样例共用框架导入，具体注入声明保留在对应测试中。
    private void configureBinding(String members) {
        myFixture.configureByText("BeanBindingCase.java", """
                import org.springframework.context.annotation.Bean;
                import org.springframework.amqp.core.*;
                class BeanBindingCase {
                    %s
                }
                """.formatted(members));
    }

    // 绑定结果按真实业务方法名定位，保留解析依赖供缓存断言。
    private MqEndpoint binding(String memberName, Set<Object> dependencies) {
        return new RabbitMqEndpointParser(new MqValueResolver(getProject()))
                .collectEndpoints(getProject(), GlobalSearchScope.projectScope(getProject()), dependencies).stream()
                .filter(endpoint -> endpoint.role() == MqEndpoint.Role.BINDING)
                .filter(endpoint -> memberName.equals(endpoint.memberName())).findFirst().orElseThrow();
    }

    // 项目拓扑在读动作和进度上下文中访问，保持服务调用方式一致。
    private List<MqEndpoint> readEndpoints(MqNavigationService service) throws Exception {
        Future<List<MqEndpoint>> future = ApplicationManager.getApplication().executeOnPooledThread(
                () -> ProgressManager.getInstance().runProcess(
                        () -> ReadAction.compute(service::currentEndpoints), new EmptyProgressIndicator()));
        return future.get(20, TimeUnit.SECONDS);
    }
}
