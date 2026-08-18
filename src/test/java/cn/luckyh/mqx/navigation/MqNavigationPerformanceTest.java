package cn.luckyh.mqx.navigation;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.builders.JavaModuleFixtureBuilder;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.JavaTestFixtureFactory;
import com.intellij.testFramework.fixtures.TestFixtureBuilder;
import cn.luckyh.mqx.model.MqEndpoint;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MqNavigationPerformanceTest {

    private static final int MODULE_COUNT = 50;
    private static final int ENDPOINTS_PER_MODULE = 40;

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private CodeInsightTestFixture fixture;
    private Path projectDirectory;
    private long fixtureStartedNanos;

    @Before
    public void setUp() throws Exception {
        fixtureStartedNanos = System.nanoTime();
        projectDirectory = temporaryFolder.newFolder("project").toPath();
        TestFixtureBuilder<IdeaProjectTestFixture> builder =
                JavaTestFixtureFactory.createFixtureBuilder("MqNavigationPerformanceTest");
        for (int module = 0; module < MODULE_COUNT; module++) {
            @SuppressWarnings("unchecked")
            JavaModuleFixtureBuilder<?> moduleBuilder = builder.addModule(JavaModuleFixtureBuilder.class);
            String moduleRoot = String.format("perf-module-%02d", module);
            Path contentRoot = Files.createDirectories(projectDirectory.resolve(moduleRoot));
            Path sourceRoot = Files.createDirectories(contentRoot.resolve("src/main/java"));
            moduleBuilder.addContentRoot(contentRoot.toString());
            moduleBuilder.addSourceRoot(sourceRoot.toString());
        }
        fixture = JavaTestFixtureFactory.getFixtureFactory().createCodeInsightFixture(builder.getFixture());
        fixture.setUp();
        prepareModules();
    }

    @After
    public void tearDown() throws Exception {
        if (fixture != null) {
            fixture.tearDown();
        }
    }

    @Test(timeout = 360_000)
    public void navigationAndRefreshMeetP95Budgets() throws Exception {
        createSources();
        DumbService.getInstance(fixture.getProject()).waitForSmartMode();
        long fixtureNanos = System.nanoTime() - fixtureStartedNanos;

        Future<PerformanceResult> future = ApplicationManager.getApplication().executeOnPooledThread(() ->
                ProgressManager.getInstance().runProcess(this::measure, new EmptyProgressIndicator()));
        PerformanceResult result = future.get();

        assertEquals(MODULE_COUNT, ModuleManager.getInstance(fixture.getProject()).getModules().length);
        assertEquals(MODULE_COUNT * ENDPOINTS_PER_MODULE, result.endpointCount());
        assertTrue("navigation p95 ns=" + result.navigationP95(), result.navigationP95() < 1_000_000_000L);
        assertTrue("refresh p95 ns=" + result.refreshP95(), result.refreshP95() < 3_000_000_000L);
        System.out.printf(
                "MQ_PERF modules=%d endpoints=%d nav[n=%d p50=%d p95=%d max=%d] "
                        + "refresh[n=%d p50=%d p95=%d max=%d] fixture=%d firstSnapshot=%d "
                        + "idea=%s jbr=%s os=%s cpus=%d maxHeap=%d%n",
                MODULE_COUNT, result.endpointCount(), result.navigationSamples().size(),
                percentile(result.navigationSamples(), 0.50), result.navigationP95(), max(result.navigationSamples()),
                result.refreshSamples().size(), percentile(result.refreshSamples(), 0.50),
                result.refreshP95(), max(result.refreshSamples()), fixtureNanos, result.firstSnapshotNanos(),
                ApplicationInfo.getInstance().getBuild().asString(), System.getProperty("java.runtime.version"),
                System.getProperty("os.name"), Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory());
    }

    private PerformanceResult measure() {
        MqNavigationService service = MqNavigationService.getInstance(fixture.getProject());
        long firstStart = System.nanoTime();
        List<MqEndpoint> endpoints = read(service::currentEndpoints);
        long firstSnapshot = System.nanoTime() - firstStart;
        assertEquals(MODULE_COUNT * ENDPOINTS_PER_MODULE, endpoints.size());
        assertEndpointCount(endpoints, MqEndpoint.SystemType.RABBITMQ, MqEndpoint.Role.PRODUCER, 350);
        assertEndpointCount(endpoints, MqEndpoint.SystemType.RABBITMQ, MqEndpoint.Role.CONSUMER, 350);
        assertEndpointCount(endpoints, MqEndpoint.SystemType.KAFKA, MqEndpoint.Role.PRODUCER, 350);
        assertEndpointCount(endpoints, MqEndpoint.SystemType.KAFKA, MqEndpoint.Role.CONSUMER, 350);
        assertEndpointCount(endpoints, MqEndpoint.SystemType.ROCKETMQ, MqEndpoint.Role.PRODUCER, 300);
        assertEndpointCount(endpoints, MqEndpoint.SystemType.ROCKETMQ, MqEndpoint.Role.CONSUMER, 300);

        List<List<MqEndpoint>> navigationGroups = List.of(
                endpointsFor(endpoints, MqEndpoint.SystemType.RABBITMQ, MqEndpoint.Role.PRODUCER),
                endpointsFor(endpoints, MqEndpoint.SystemType.RABBITMQ, MqEndpoint.Role.CONSUMER),
                endpointsFor(endpoints, MqEndpoint.SystemType.KAFKA, MqEndpoint.Role.PRODUCER),
                endpointsFor(endpoints, MqEndpoint.SystemType.KAFKA, MqEndpoint.Role.CONSUMER),
                endpointsFor(endpoints, MqEndpoint.SystemType.ROCKETMQ, MqEndpoint.Role.PRODUCER),
                endpointsFor(endpoints, MqEndpoint.SystemType.ROCKETMQ, MqEndpoint.Role.CONSUMER));

        Map<String, MqEndpoint> modules = new LinkedHashMap<>();
        for (MqEndpoint endpoint : endpoints) {
            if (endpoint.role() == MqEndpoint.Role.PRODUCER) {
                modules.putIfAbsent(endpoint.moduleName(), endpoint);
            }
        }
        List<MqEndpoint> moduleProducers = List.copyOf(modules.values());
        assertEquals(MODULE_COUNT, moduleProducers.size());

        for (int index = 0; index < 20; index++) {
            List<MqEndpoint> group = navigationGroups.get(index % navigationGroups.size());
            MqEndpoint endpoint = group.get(((index / navigationGroups.size()) * 13) % group.size());
            read(() -> service.findTargets(endpoint.sourcePointer().getElement()));
        }
        for (int index = 0; index < 3; index++) {
            DestinationChange change = changeDestination(moduleProducers.get(index), "warmup-" + index);
            DumbService.getInstance(fixture.getProject()).waitForSmartMode();
            service.requestRefresh();
            assertDestinationChanged(read(service::currentEndpoints), moduleProducers.get(index), change);
        }

        List<Long> navigation = new ArrayList<>(200);
        for (int index = 0; index < 200; index++) {
            List<MqEndpoint> group = navigationGroups.get(index % navigationGroups.size());
            MqEndpoint endpoint = group.get(((index / navigationGroups.size()) * 13) % group.size());
            long start = System.nanoTime();
            read(() -> service.findTargets(endpoint.sourcePointer().getElement()));
            navigation.add(System.nanoTime() - start);
        }

        List<Long> refresh = new ArrayList<>(40);
        for (int sample = 0; sample < 40; sample++) {
            MqEndpoint endpoint = moduleProducers.get(sample);
            DestinationChange change = changeDestination(endpoint, "sample-" + sample);
            long start = System.nanoTime();
            DumbService.getInstance(fixture.getProject()).waitForSmartMode();
            service.requestRefresh();
            assertDestinationChanged(read(service::currentEndpoints), endpoint, change);
            refresh.add(System.nanoTime() - start);
        }
        assertEquals(40, refresh.size());
        return new PerformanceResult(endpoints.size(), firstSnapshot, navigation, refresh,
                percentile(navigation, 0.95), percentile(refresh, 0.95));
    }

    private DestinationChange changeDestination(MqEndpoint endpoint, String replacement) {
        DestinationEdit edit = read(() -> {
            var element = endpoint.sourcePointer().getElement();
            PsiMethodCallExpression call = PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression.class);
            if (call == null || call.getArgumentList().getExpressions().length == 0
                    || !(call.getArgumentList().getExpressions()[0] instanceof PsiLiteralExpression literal)
                    || !(literal.getValue() instanceof String oldValue)) {
                return null;
            }
            Document document = PsiDocumentManager.getInstance(fixture.getProject())
                    .getDocument(call.getContainingFile());
            return document == null ? null : new DestinationEdit(document, literal.getTextRange(), oldValue);
        });
        assertNotNull(edit);
        String newValue = "perf-" + endpoint.moduleName() + '-' + endpoint.memberName() + '-' + replacement;
        ApplicationManager.getApplication().invokeAndWait(() -> WriteCommandAction.runWriteCommandAction(
                fixture.getProject(), () -> {
            edit.document().replaceString(edit.range().getStartOffset(), edit.range().getEndOffset(),
                    '"' + newValue + '"');
            PsiDocumentManager.getInstance(fixture.getProject()).commitDocument(edit.document());
        }));
        return new DestinationChange(edit.oldValue(), newValue);
    }

    private void assertDestinationChanged(
            List<MqEndpoint> endpoints, MqEndpoint original, DestinationChange change) {
        List<MqEndpoint> current = endpoints.stream()
                .filter(endpoint -> endpoint.system() == original.system()
                        && endpoint.role() == original.role()
                        && endpoint.moduleName().equals(original.moduleName())
                        && endpoint.className().equals(original.className())
                        && endpoint.memberName().equals(original.memberName()))
                .toList();
        assertEquals(1, current.size());
        String summary = current.get(0).destination().summary();
        assertTrue(summary, summary.contains(change.newValue()));
        assertFalse(summary, summary.contains(change.oldValue()));
    }

    private void assertEndpointCount(
            List<MqEndpoint> endpoints, MqEndpoint.SystemType system, MqEndpoint.Role role, long expected) {
        long actual = endpoints.stream()
                .filter(endpoint -> endpoint.system() == system && endpoint.role() == role)
                .count();
        assertEquals(system + " " + role, expected, actual);
    }

    private List<MqEndpoint> endpointsFor(
            List<MqEndpoint> endpoints, MqEndpoint.SystemType system, MqEndpoint.Role role) {
        return endpoints.stream()
                .filter(endpoint -> endpoint.system() == system && endpoint.role() == role)
                .toList();
    }

    private <T> T read(Supplier<T> supplier) {
        return ReadAction.compute(supplier::get);
    }

    private void prepareModules() {
        Module[] modules = ModuleManager.getInstance(fixture.getProject()).getModules();
        assertEquals(MODULE_COUNT, modules.length);
        for (int index = 1; index < modules.length; index++) {
            ModuleRootModificationUtil.addDependency(modules[index], modules[0]);
        }
    }

    private void createSources() throws Exception {
        Path stubs = Path.of("src/test/testData/stubs").toAbsolutePath();
        try (var stream = Files.walk(stubs)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String relative = stubs.relativize(file).toString().replace('\\', '/');
                writeProjectFile("perf-module-00/src/main/java/" + relative, Files.readString(file));
            }
        }
        for (int module = 0; module < MODULE_COUNT; module++) {
            String root = String.format("perf-module-%02d/src/main/java/perf/m%d/", module, module);
            writeProjectFile(root + "RabbitProducer" + module + ".java",
                    render("RabbitProducer.java.tpl", module, rabbitProducerMethods(module), ""));
            writeProjectFile(root + "RabbitConsumer" + module + ".java",
                    render("RabbitConsumer.java.tpl", module, rabbitConsumerMethods(module), ""));
            writeProjectFile(root + "KafkaProducer" + module + ".java",
                    render("KafkaProducer.java.tpl", module, kafkaProducerMethods(module), ""));
            writeProjectFile(root + "KafkaConsumer" + module + ".java",
                    render("KafkaConsumer.java.tpl", module, kafkaConsumerMethods(module), ""));
            writeProjectFile(root + "RocketProducer" + module + ".java",
                    render("RocketProducer.java.tpl", module, rocketProducerMethods(module), ""));
            writeProjectFile(root + "RocketConsumer" + module + ".java",
                    render("RocketConsumer.java.tpl", module, "", rocketConsumerClasses(module)));
        }
        var projectRoot = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(projectDirectory);
        if (projectRoot != null) {
            VfsUtil.markDirtyAndRefresh(false, true, true, projectRoot);
        }
    }

    private void writeProjectFile(String relativePath, String content) throws Exception {
        Path target = projectDirectory.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private String render(String name, int module, String methods, String classes) throws Exception {
        String template = Files.readString(Path.of("src/test/testData/performance/templates", name));
        return template.replace("${module}", Integer.toString(module))
                .replace("${methods}", methods)
                .replace("${classes}", classes);
    }

    private String rabbitProducerMethods(int module) {
        StringBuilder methods = new StringBuilder();
        for (int index = 0; index < 7; index++) {
            methods.append("  void sendR").append(index).append("() { template.convertAndSend(\"rabbit-")
                    .append(module).append('-').append(index).append("\", new Object()); }\n");
        }
        return methods.toString();
    }

    private String rabbitConsumerMethods(int module) {
        StringBuilder methods = new StringBuilder();
        for (int index = 0; index < 7; index++) {
            methods.append("  @org.springframework.amqp.rabbit.annotation.RabbitListener(queues=\"rabbit-")
                    .append(module).append('-').append(index).append("\")\n")
                    .append("  void receiveR").append(index).append("(Object value) {}\n");
        }
        return methods.toString();
    }

    private String kafkaProducerMethods(int module) {
        StringBuilder methods = new StringBuilder();
        for (int index = 0; index < 7; index++) {
            methods.append("  void sendK").append(index).append("() { template.send(\"kafka-")
                    .append(module).append('-').append(index).append("\", new Object()); }\n");
        }
        return methods.toString();
    }

    private String kafkaConsumerMethods(int module) {
        StringBuilder methods = new StringBuilder();
        for (int index = 0; index < 7; index++) {
            methods.append("  @org.springframework.kafka.annotation.KafkaListener(topics=\"kafka-")
                    .append(module).append('-').append(index).append("\", groupId=\"group-")
                    .append(module).append('-').append(index).append("\")\n")
                    .append("  void receiveK").append(index).append("(Object value) {}\n");
        }
        return methods.toString();
    }

    private String rocketProducerMethods(int module) {
        StringBuilder methods = new StringBuilder();
        for (int index = 0; index < 6; index++) {
            methods.append("  void sendM").append(index).append("() { template.syncSend(\"rocket-")
                    .append(module).append('-').append(index).append("\", new Object()); }\n");
        }
        return methods.toString();
    }

    private String rocketConsumerClasses(int module) {
        StringBuilder classes = new StringBuilder();
        for (int index = 1; index < 6; index++) {
            classes.append("@org.apache.rocketmq.spring.annotation.RocketMQMessageListener(topic=\"rocket-")
                    .append(module).append('-').append(index).append("\", consumerGroup=\"group-")
                    .append(module).append('-').append(index).append("\")\n")
                    .append("class RocketConsumer").append(module).append('_').append(index).append(" {}\n");
        }
        return classes.toString();
    }

    private static long percentile(List<Long> values, double percentile) {
        long[] sorted = values.stream().mapToLong(Long::longValue).sorted().toArray();
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(index, 0)];
    }

    private static long max(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).max().orElse(0L);
    }

    private record PerformanceResult(
            int endpointCount,
            long firstSnapshotNanos,
            List<Long> navigationSamples,
            List<Long> refreshSamples,
            long navigationP95,
            long refreshP95) {
    }

    private record DestinationEdit(Document document, TextRange range, String oldValue) {
    }

    private record DestinationChange(String oldValue, String newValue) {
    }
}
