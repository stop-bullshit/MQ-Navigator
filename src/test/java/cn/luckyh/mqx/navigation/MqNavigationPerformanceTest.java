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
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.builders.JavaModuleFixtureBuilder;
import com.intellij.testFramework.fixtures.CodeInsightTestFixture;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.JavaTestFixtureFactory;
import com.intellij.testFramework.fixtures.TestFixtureBuilder;
import com.intellij.ui.treeStructure.SimpleTree;
import cn.luckyh.mqx.model.MqEndpoint;
import cn.luckyh.mqx.ui.MqTopologyPanel;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.awt.Component;
import java.awt.Container;
import javax.swing.JLabel;
import javax.swing.tree.DefaultMutableTreeNode;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
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

    @Test(timeout = 600_000)
    public void sidebarVisibleRefreshMeetsP95Budget() throws Exception {
        createSidebarSources();
        DumbService.getInstance(fixture.getProject()).waitForSmartMode();
        Future<SidebarPerformanceResult> future = ApplicationManager.getApplication().executeOnPooledThread(() ->
                ProgressManager.getInstance().runProcess(this::measureSidebar, new EmptyProgressIndicator()));
        SidebarPerformanceResult result = future.get();
        assertEquals(MODULE_COUNT * ENDPOINTS_PER_MODULE, result.endpointCount());
        assertEquals(40, result.samples().size());
        assertTrue("sidebar refresh p95 ns=" + result.p95(), result.p95() < 3_000_000_000L);
        System.out.printf(
                "MQ_SIDEBAR_PERF modules=%d endpoints=%d refresh[n=%d p50=%d p95=%d max=%d] "
                        + "firstSnapshot=%d firstTree=%d idea=%s jbr=%s os=%s%n",
                MODULE_COUNT, result.endpointCount(), result.samples().size(),
                percentile(result.samples(), 0.50), result.p95(), max(result.samples()),
                result.firstSnapshotNanos(), result.firstTreeNanos(),
                ApplicationInfo.getInstance().getBuild().asString(), System.getProperty("java.runtime.version"),
                System.getProperty("os.name"));
    }

    private SidebarPerformanceResult measureSidebar() {
        MqNavigationService service = MqNavigationService.getInstance(fixture.getProject());
        long firstSnapshotStarted = System.nanoTime();
        List<MqEndpoint> endpoints = read(service::currentEndpoints);
        long firstSnapshot = System.nanoTime() - firstSnapshotStarted;
        assertEquals(MODULE_COUNT * ENDPOINTS_PER_MODULE, endpoints.size());
        assertTrue(endpoints.stream().allMatch(endpoint -> endpoint.system() == MqEndpoint.SystemType.RABBITMQ));
        MqTopologyPanel panel = onEdt(() -> new MqTopologyPanel(fixture.getProject()));
        long firstTreeStarted = System.nanoTime();
        service.requestRefresh();
        List<MqEndpoint> firstTreeEndpoints = read(service::currentEndpoints);
        refreshSidebar(service, panel, firstTreeEndpoints, "baseline",
                () -> treeMatches(panel, null, true));
        long firstTree = System.nanoTime() - firstTreeStarted;
        for (int sample = 0; sample < 3; sample++) {
            measureSidebarMutation(service, panel, sidebarMutation(endpoints, sample), false);
        }
        List<Long> samples = new ArrayList<>(40);
        for (int sample = 0; sample < 40; sample++) {
            samples.add(measureSidebarMutation(service, panel, sidebarMutation(endpoints, sample), true));
        }
        return new SidebarPerformanceResult(endpoints.size(), firstSnapshot, firstTree,
                samples, percentile(samples, 0.95));
    }

    private long measureSidebarMutation(
            MqNavigationService service,
            MqTopologyPanel panel,
            SidebarMutation mutation,
            boolean measured) {
        changeSidebarSource(mutation, true);
        long started = System.nanoTime();
        service.requestRefresh();
        List<MqEndpoint> appliedEndpoints = read(service::currentEndpoints);
        refreshSidebar(service, panel, appliedEndpoints, mutation.operation() + " applied",
                () -> treeMatches(panel, mutation, true));
        long elapsed = System.nanoTime() - started;
        changeSidebarSource(mutation, false);
        service.requestRefresh();
        List<MqEndpoint> restoredEndpoints = read(service::currentEndpoints);
        refreshSidebar(service, panel, restoredEndpoints, mutation.operation() + " restored",
                () -> treeMatches(panel, mutation, false));
        return measured ? elapsed : 0L;
    }

    private void refreshSidebar(
            MqNavigationService service,
            MqTopologyPanel panel,
            List<MqEndpoint> requestedEndpoints,
            String expectation,
            BooleanSupplier treeCondition) {
        onEdt(() -> {
            panel.refresh();
            return null;
        });
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (!treeCondition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                String treeState = onEdt(() -> {
                    SimpleTree tree = findComponent(panel, SimpleTree.class);
                    JLabel status = findComponent(panel, JLabel.class);
                    DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
                    List<String> modules = new ArrayList<>();
                    for (int index = 0; index < root.getChildCount(); index++) {
                        modules.add(renderedLabel(tree, (DefaultMutableTreeNode) root.getChildAt(index)));
                    }
                    return renderedLabel(tree, root) + " status=" + (status == null ? null : status.getText())
                            + " modules=" + modules;
                });
                throw new AssertionError("Timed out waiting for RabbitMQ sidebar refresh " + expectation
                        + ": requested="
                        + requestedEndpoints.size() + " service=" + read(service::currentEndpoints).size()
                        + " " + treeState);
            }
            LockSupport.parkNanos(10_000_000L);
        }
    }

    private SidebarMutation sidebarMutation(List<MqEndpoint> endpoints, int sample) {
        int module = sample % MODULE_COUNT;
        String className = "SidebarProducer" + module;
        String moduleName = endpoints.stream().filter(endpoint -> className.equals(endpoint.className()))
                .findFirst().orElseThrow().moduleName();
        String file = String.format("perf-module-%02d/src/main/java/perf/m%d/%s.java",
                module, module, className);
        return switch (sample % 3) {
            case 0 -> new SidebarMutation(SidebarOperation.ADD, file, moduleName,
                    "void send17() { template.convertAndSend(\"rabbit-" + module
                            + "-p-17\", new Object()); }",
                    "void send17() { String key = System.nanoTime() > 0 ? \"rabbit-" + module
                            + "-p-17\" : \"added-" + module
                            + "\"; template.convertAndSend(key, new Object()); }",
                    "key=added-" + module, null);
            case 1 -> new SidebarMutation(SidebarOperation.MODIFY, file, moduleName,
                    "\"rabbit-" + module + "-p-0\"", "\"rabbit-" + module + "-p-0-updated\"",
                    "key=rabbit-" + module + "-p-0-updated", "key=rabbit-" + module + "-p-0");
            default -> new SidebarMutation(SidebarOperation.DELETE, file, moduleName,
                    "void send18() { String key = System.nanoTime() > 0 ? \"rabbit-" + module
                            + "-p-18\" : \"deleted-" + module
                            + "\"; template.convertAndSend(key, new Object()); }",
                    "void send18() { template.convertAndSend(\"rabbit-" + module
                            + "-p-18\", new Object()); }", null, "key=deleted-" + module);
        };
    }

    private void changeSidebarSource(SidebarMutation mutation, boolean apply) {
        Document document = read(() -> {
            var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(
                    projectDirectory.resolve(mutation.relativePath()));
            PsiFile psiFile = file == null ? null : PsiManager.getInstance(fixture.getProject()).findFile(file);
            return psiFile == null ? null : PsiDocumentManager.getInstance(fixture.getProject()).getDocument(psiFile);
        });
        assertNotNull(document);
        String from = apply ? mutation.baselineText() : mutation.changedText();
        String to = apply ? mutation.changedText() : mutation.baselineText();
        onEdt(() -> {
            WriteCommandAction.runWriteCommandAction(fixture.getProject(), () -> {
                int offset = document.getText().indexOf(from);
                assertTrue("Missing source text: " + from, offset >= 0);
                document.replaceString(offset, offset + from.length(), to);
                PsiDocumentManager.getInstance(fixture.getProject()).commitDocument(document);
            });
            return null;
        });
    }

    private boolean treeMatches(MqTopologyPanel panel, SidebarMutation mutation, boolean applied) {
        return onEdt(() -> {
            SimpleTree tree = findComponent(panel, SimpleTree.class);
            DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
            int total = MODULE_COUNT * ENDPOINTS_PER_MODULE;
            int moduleCount = ENDPOINTS_PER_MODULE;
            if (mutation != null && applied) {
                total += mutation.operation().delta;
                moduleCount += mutation.operation().delta;
            }
            if (!renderedLabel(tree, root).equals("RabbitMQ (" + total + ")")) {
                return false;
            }
            if (mutation == null) {
                return root.getChildCount() == MODULE_COUNT;
            }
            DefaultMutableTreeNode moduleNode = null;
            for (int index = 0; index < root.getChildCount(); index++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) root.getChildAt(index);
                if (renderedLabel(tree, child).startsWith(mutation.moduleName() + " (")) {
                    moduleNode = child;
                    break;
                }
            }
            if (moduleNode == null) {
                return false;
            }
            List<String> labels = renderedLabels(tree, moduleNode);
            if (!labels.contains(mutation.moduleName() + " (" + moduleCount + ")")) {
                return false;
            }
            boolean currentPresent = mutation.currentIndicator() != null
                    && labels.stream().anyMatch(label -> label.endsWith(mutation.currentIndicator()));
            boolean baselinePresent = mutation.baselineIndicator() != null
                    && labels.stream().anyMatch(label -> label.endsWith(mutation.baselineIndicator()));
            return mutation.matches(applied, currentPresent, baselinePresent);
        });
    }

    private List<String> renderedLabels(SimpleTree tree, DefaultMutableTreeNode root) {
        List<String> labels = new ArrayList<>();
        Enumeration<?> nodes = root.depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            labels.add(renderedLabel(tree, (DefaultMutableTreeNode) nodes.nextElement()));
        }
        return labels;
    }

    private String renderedLabel(SimpleTree tree, DefaultMutableTreeNode node) {
        Component component = tree.getCellRenderer().getTreeCellRendererComponent(
                tree, node, false, false, node.isLeaf(), 0, false);
        return ((JLabel) component).getText();
    }

    private <T extends Component> T findComponent(Container container, Class<T> componentType) {
        for (Component component : container.getComponents()) {
            if (componentType.isInstance(component)) {
                return componentType.cast(component);
            }
            if (component instanceof Container childContainer) {
                T child = findComponent(childContainer, componentType);
                if (child != null) {
                    return child;
                }
            }
        }
        return null;
    }

    private <T> T onEdt(Supplier<T> supplier) {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            return supplier.get();
        }
        AtomicReference<T> result = new AtomicReference<>();
        ApplicationManager.getApplication().invokeAndWait(() -> result.set(supplier.get()));
        return result.get();
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

    private void createSidebarSources() throws Exception {
        Path stubs = Path.of("src/test/testData/stubs").toAbsolutePath();
        try (var stream = Files.walk(stubs)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String relative = stubs.relativize(file).toString().replace('\\', '/');
                writeProjectFile("perf-module-00/src/main/java/" + relative, Files.readString(file));
            }
        }
        for (int module = 0; module < MODULE_COUNT; module++) {
            String root = String.format("perf-module-%02d/src/main/java/perf/m%d/", module, module);
            StringBuilder producerMethods = new StringBuilder();
            StringBuilder consumerMethods = new StringBuilder();
            for (int index = 0; index < 18; index++) {
                producerMethods.append("  void send").append(index)
                        .append("() { template.convertAndSend(\"rabbit-").append(module).append("-p-")
                        .append(index).append("\", new Object()); }\n");
            }
            producerMethods.append("  void send18() { String key = System.nanoTime() > 0 ? \"rabbit-")
                    .append(module).append("-p-18\" : \"deleted-").append(module)
                    .append("\"; template.convertAndSend(key, new Object()); }\n");
            for (int index = 0; index < 20; index++) {
                consumerMethods.append("  @org.springframework.amqp.rabbit.annotation.RabbitListener(queues=\"rabbit-")
                        .append(module).append("-c-").append(index).append("\")\n  void receive")
                        .append(index).append("(Object value) {}\n");
            }
            writeProjectFile(root + "SidebarProducer" + module + ".java",
                    "package perf.m" + module + "; class SidebarProducer" + module
                            + " { org.springframework.amqp.rabbit.core.RabbitTemplate template;\n"
                            + producerMethods + "}\n");
            writeProjectFile(root + "SidebarConsumer" + module + ".java",
                    "package perf.m" + module + "; class SidebarConsumer" + module + " {\n"
                            + consumerMethods + "}\n");
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

    private record SidebarPerformanceResult(
            int endpointCount,
            long firstSnapshotNanos,
            long firstTreeNanos,
            List<Long> samples,
            long p95) {
    }

    private record SidebarMutation(
            SidebarOperation operation,
            String relativePath,
            String moduleName,
            String baselineText,
            String changedText,
            String currentIndicator,
            String baselineIndicator) {

        private boolean matches(boolean applied, boolean currentPresent, boolean baselinePresent) {
            return switch (operation) {
                case ADD -> applied == currentPresent;
                case MODIFY -> applied ? currentPresent && !baselinePresent : !currentPresent && baselinePresent;
                case DELETE -> applied != baselinePresent;
            };
        }
    }

    private enum SidebarOperation {
        ADD(1),
        MODIFY(0),
        DELETE(-1);

        private final int delta;

        SidebarOperation(int delta) {
            this.delta = delta;
        }
    }
}
