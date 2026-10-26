package cn.luckyh.mqx.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.treeStructure.SimpleTree;
import cn.luckyh.mqx.model.MqDestination;
import cn.luckyh.mqx.model.MqEndpoint;

import javax.swing.JLabel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * RabbitMQ 特殊队列拓扑侧边栏测试.
 *
 * @author heng.wang
 * @since 2026-08-25
 */
public class MqSpecialQueueTopologyPanelTest extends BasePlatformTestCase {

    private PsiFile sourceFile;
    private Map<String, SmartPsiElementPointer<PsiElement>> sourcePointers;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        sourceFile = myFixture.configureByText("SpecialQueueSources.java", """
                class SpecialQueueSources {
                    void sourceQueue() {}
                    void secondSourceQueue() {}
                    void deadExchange() {}
                    void firstBinding() {}
                    void secondBinding() {}
                    void firstDeadQueue() {}
                    void secondDeadQueue() {}
                }
                """);
        sourcePointers = ReadAction.compute(() -> {
            Map<String, SmartPsiElementPointer<PsiElement>> pointers = new LinkedHashMap<>();
            for (PsiMethod method : ((com.intellij.psi.PsiJavaFile) sourceFile).getClasses()[0].getMethods()) {
                pointers.put(method.getName(), SmartPointerManager.getInstance(getProject())
                        .createSmartPsiElementPointer(method));
            }
            return pointers;
        });
    }

    @Override
    protected boolean runInDispatchThread() {
        return false;
    }

    public void testDelayQueueShowsCompleteDeadLetterRelationsAndNavigation() {
        MqEndpoint source = endpoint("source", MqEndpoint.Role.DECLARATION,
                MqDestination.rabbitQueue("source.queue", settings(
                        "dead.exchange", true, "dead.key", true, 300000L, List.of())),
                "source", "sourceQueue");
        MqEndpoint exchange = endpoint("exchange", MqEndpoint.Role.EXCHANGE_DECLARATION,
                MqDestination.rabbitConsumer("dead.exchange", "", List.of(), "direct"),
                "infrastructure", "deadExchange");
        MqEndpoint binding = endpoint("binding", MqEndpoint.Role.BINDING,
                MqDestination.rabbitConsumer("dead.exchange", "dead.queue", List.of("dead.key"), "direct"),
                "infrastructure", "firstBinding");
        MqEndpoint target = endpoint("target", MqEndpoint.Role.DECLARATION,
                MqDestination.rabbitQueue("dead.queue", null), "target", "firstDeadQueue");

        SimpleTree tree = createTree(List.of(source, exchange, binding, target));
        DefaultMutableTreeNode sourceNode = findNodeContaining(tree, "#sourceQueue", "type=delay");

        assertNotNull(sourceNode);
        assertTrue(renderedLabel(tree, sourceNode).contains("ttl=300000ms"));
        assertTrue(renderedLabel(tree, sourceNode).contains("dlx=dead.exchange"));
        DefaultMutableTreeNode exchangeRelations = findChild(tree, sourceNode, "Dead-letter exchanges (1)");
        DefaultMutableTreeNode bindingRelations = findChild(tree, sourceNode, "Dead-letter bindings (1)");
        assertNotNull(exchangeRelations);
        assertNotNull(bindingRelations);
        DefaultMutableTreeNode queueRelations = findChild(tree, sourceNode, "Dead-letter queues (1)");
        assertNotNull(queueRelations);
        DefaultMutableTreeNode exchangeNode = findDescendantContaining(tree, exchangeRelations, "#deadExchange");
        DefaultMutableTreeNode bindingNode = findDescendantContaining(tree, bindingRelations, "#firstBinding");
        DefaultMutableTreeNode targetNode = findDescendantContaining(tree, queueRelations, "#firstDeadQueue");
        assertNotNull(exchangeNode);
        assertNotNull(bindingNode);
        assertNotNull(targetNode);
        // 编辑器冷启动受宿主 UI 调度影响，导航耗时由独立的 P95 性能用例检查。
        for (int iteration = 0; iteration < 20; iteration++) {
            assertNavigatesTo(tree, sourceNode, sourceFile);
            assertNavigatesTo(tree, exchangeNode, sourceFile);
            assertNavigatesTo(tree, bindingNode, sourceFile);
            assertNavigatesTo(tree, targetNode, sourceFile);
        }
    }

    public void testMultipleTargetsMissingTargetAndDefaultExchangeStayAccurate() {
        MqEndpoint source = endpoint("source", MqEndpoint.Role.DECLARATION,
                MqDestination.rabbitQueue("source.queue", settings(
                        "dead.exchange", true, "dead.#", false, null, List.of())),
                "source", "sourceQueue");
        MqEndpoint exchange = endpoint("exchange", MqEndpoint.Role.EXCHANGE_DECLARATION,
                MqDestination.rabbitConsumer("dead.exchange", "", List.of(), "topic"),
                "infrastructure", "deadExchange");
        MqEndpoint firstBinding = endpoint("first-binding", MqEndpoint.Role.BINDING,
                MqDestination.rabbitConsumer("dead.exchange", "dead.one", List.of("dead.*"), "topic"),
                "infrastructure", "firstBinding");
        MqEndpoint secondBinding = endpoint("second-binding", MqEndpoint.Role.BINDING,
                MqDestination.rabbitConsumer("dead.exchange", "dead.two", List.of("dead.#"), "topic"),
                "infrastructure", "secondBinding");
        MqEndpoint firstTarget = endpoint("first-target", MqEndpoint.Role.DECLARATION,
                MqDestination.rabbitQueue("dead.one", null), "target-one", "firstDeadQueue");
        MqEndpoint secondTarget = endpoint("second-target", MqEndpoint.Role.DECLARATION,
                MqDestination.rabbitQueue("dead.two", null), "target-two", "secondDeadQueue");
        MqEndpoint missing = endpoint("missing", MqEndpoint.Role.DECLARATION,
                MqDestination.rabbitQueue("missing.source", settings(
                        "missing.exchange", true, "missing.key", false, null, List.of())),
                "source", "secondSourceQueue");

        SimpleTree tree = createTree(List.of(
                source, exchange, firstBinding, secondBinding, firstTarget, secondTarget, missing));

        DefaultMutableTreeNode sourceNode = findNodeContaining(tree, "#sourceQueue", "dead.#");
        assertNotNull(findChild(tree, sourceNode, "Dead-letter bindings (2)"));
        assertNotNull(findChild(tree, sourceNode, "Dead-letter queues (2)"));
        DefaultMutableTreeNode missingNode = findNodeContaining(tree, "#secondSourceQueue", "missing.exchange");
        assertNotNull(findChildStartingWith(tree, missingNode, "Dead-letter target unresolved"));

        MqEndpoint defaultSource = endpoint("default-source", MqEndpoint.Role.DECLARATION,
                MqDestination.rabbitQueue("default.source", settings(
                        "", true, "dead.one", false, null, List.of())),
                "source", "sourceQueue");
        tree = createTree(List.of(defaultSource, firstTarget));
        DefaultMutableTreeNode defaultNode = findNodeContaining(tree, "#sourceQueue", "dlx=<default>");
        assertNotNull(findChild(tree, defaultNode, "Dead-letter queues (1)"));
    }

    public void testFiveSamplesPerDeadLetterPathStateStayAccurate() {
        List<MqEndpoint> endpoints = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            String singleExchange = "matrix.single.exchange." + index;
            String singleKey = "matrix.single.key." + index;
            String singleTarget = "matrix.single.target." + index;
            endpoints.add(endpoint("matrix-single-source-" + index, MqEndpoint.Role.DECLARATION,
                    MqDestination.rabbitQueue("matrix.single.source." + index,
                            settings(singleExchange, true, singleKey, false, null, List.of())),
                    "source-" + index, "sourceQueue"));
            endpoints.add(endpoint("matrix-single-exchange-" + index, MqEndpoint.Role.EXCHANGE_DECLARATION,
                    MqDestination.rabbitConsumer(singleExchange, "", List.of(), "direct"),
                    "exchange-" + index, "deadExchange"));
            endpoints.add(endpoint("matrix-single-binding-" + index, MqEndpoint.Role.BINDING,
                    MqDestination.rabbitConsumer(singleExchange, singleTarget, List.of(singleKey), "direct"),
                    "binding-" + index, "firstBinding"));
            endpoints.add(endpoint("matrix-single-target-" + index, MqEndpoint.Role.DECLARATION,
                    MqDestination.rabbitQueue(singleTarget, null),
                    "target-" + index, "firstDeadQueue"));

            String multiExchange = "matrix.multi.exchange." + index;
            String multiKey = "matrix.multi.#";
            endpoints.add(endpoint("matrix-multi-source-" + index, MqEndpoint.Role.DECLARATION,
                    MqDestination.rabbitQueue("matrix.multi.source." + index,
                            settings(multiExchange, true, multiKey, false, null, List.of())),
                    "source-" + index, "sourceQueue"));
            endpoints.add(endpoint("matrix-multi-exchange-" + index, MqEndpoint.Role.EXCHANGE_DECLARATION,
                    MqDestination.rabbitConsumer(multiExchange, "", List.of(), "topic"),
                    "exchange-" + index, "deadExchange"));
            for (int targetIndex = 0; targetIndex < 2; targetIndex++) {
                String targetQueue = "matrix.multi.target." + index + "." + targetIndex;
                String bindingKey = targetIndex == 0 ? "matrix.multi.*" : "matrix.multi.#";
                endpoints.add(endpoint("matrix-multi-binding-" + index + "-" + targetIndex,
                        MqEndpoint.Role.BINDING,
                        MqDestination.rabbitConsumer(multiExchange, targetQueue, List.of(bindingKey), "topic"),
                        "binding-" + index, targetIndex == 0 ? "firstBinding" : "secondBinding"));
                endpoints.add(endpoint("matrix-multi-target-" + index + "-" + targetIndex,
                        MqEndpoint.Role.DECLARATION, MqDestination.rabbitQueue(targetQueue, null),
                        "target-" + index, targetIndex == 0 ? "firstDeadQueue" : "secondDeadQueue"));
            }

            String missingBindingExchange = "matrix.missing.binding.exchange." + index;
            endpoints.add(endpoint("matrix-missing-binding-source-" + index, MqEndpoint.Role.DECLARATION,
                    MqDestination.rabbitQueue("matrix.missing.binding.source." + index,
                            settings(missingBindingExchange, true, "missing.key", false, null, List.of())),
                    "source-" + index, "sourceQueue"));
            endpoints.add(endpoint("matrix-missing-binding-exchange-" + index,
                    MqEndpoint.Role.EXCHANGE_DECLARATION,
                    MqDestination.rabbitConsumer(missingBindingExchange, "", List.of(), "direct"),
                    "exchange-" + index, "deadExchange"));

            String missingQueueExchange = "matrix.missing.queue.exchange." + index;
            String missingQueueKey = "matrix.missing.queue.key." + index;
            endpoints.add(endpoint("matrix-missing-queue-source-" + index, MqEndpoint.Role.DECLARATION,
                    MqDestination.rabbitQueue("matrix.missing.queue.source." + index,
                            settings(missingQueueExchange, true, missingQueueKey, false, null, List.of())),
                    "source-" + index, "sourceQueue"));
            endpoints.add(endpoint("matrix-missing-queue-exchange-" + index,
                    MqEndpoint.Role.EXCHANGE_DECLARATION,
                    MqDestination.rabbitConsumer(missingQueueExchange, "", List.of(), "direct"),
                    "exchange-" + index, "deadExchange"));
            endpoints.add(endpoint("matrix-missing-queue-binding-" + index, MqEndpoint.Role.BINDING,
                    MqDestination.rabbitConsumer(missingQueueExchange, "undeclared.queue." + index,
                            List.of(missingQueueKey), "direct"),
                    "binding-" + index, "firstBinding"));

            String originalKeyExchange = "matrix.original.key.exchange." + index;
            String originalKeyTarget = "matrix.original.key.target." + index;
            endpoints.add(endpoint("matrix-original-key-source-" + index, MqEndpoint.Role.DECLARATION,
                    MqDestination.rabbitQueue("matrix.original.key.source." + index,
                            settings(originalKeyExchange, false, null, false, null, List.of())),
                    "source-" + index, "sourceQueue"));
            endpoints.add(endpoint("matrix-original-key-exchange-" + index,
                    MqEndpoint.Role.EXCHANGE_DECLARATION,
                    MqDestination.rabbitConsumer(originalKeyExchange, "", List.of(), "fanout"),
                    "exchange-" + index, "deadExchange"));
            endpoints.add(endpoint("matrix-original-key-binding-" + index, MqEndpoint.Role.BINDING,
                    MqDestination.rabbitConsumer(originalKeyExchange, originalKeyTarget, List.of(), "fanout"),
                    "binding-" + index, "firstBinding"));
            endpoints.add(endpoint("matrix-original-key-target-" + index, MqEndpoint.Role.DECLARATION,
                    MqDestination.rabbitQueue(originalKeyTarget, null),
                    "target-" + index, "firstDeadQueue"));
        }

        SimpleTree tree = createTree(endpoints);
        for (int index = 0; index < 5; index++) {
            DefaultMutableTreeNode single = findNodeContainingWithChildren(
                    tree, "matrix.single.source." + index);
            assertNotNull(findChild(tree, single, "Dead-letter bindings (1)"));
            assertNotNull(findChild(tree, single, "Dead-letter queues (1)"));

            DefaultMutableTreeNode multi = findNodeContainingWithChildren(
                    tree, "matrix.multi.source." + index);
            assertNotNull(findChild(tree, multi, "Dead-letter bindings (2)"));
            assertNotNull(findChild(tree, multi, "Dead-letter queues (2)"));

            DefaultMutableTreeNode missingBinding = findNodeContainingWithChildren(
                    tree, "matrix.missing.binding.source." + index);
            assertNotNull(findChild(tree, missingBinding, "Dead-letter exchanges (1)"));
            assertNull(findChild(tree, missingBinding, "Dead-letter bindings (1)"));
            assertNotNull(findChildStartingWith(tree, missingBinding, "Dead-letter target unresolved"));

            DefaultMutableTreeNode missingQueue = findNodeContainingWithChildren(
                    tree, "matrix.missing.queue.source." + index);
            assertNotNull(findChild(tree, missingQueue, "Dead-letter bindings (1)"));
            assertNull(findChild(tree, missingQueue, "Dead-letter queues (1)"));
            assertNotNull(findChildStartingWith(tree, missingQueue, "Dead-letter target unresolved"));

            DefaultMutableTreeNode originalKey = findNodeContainingWithChildren(
                    tree, "matrix.original.key.source." + index, "key=<original>");
            assertNotNull(findChild(tree, originalKey, "Dead-letter bindings (1)"));
            assertNotNull(findChild(tree, originalKey, "Dead-letter queues (1)"));
        }
    }

    public void testFanoutWithoutDedicatedRoutingKeyAndLoopOnlyShowOneRelationLevel() {
        MqEndpoint source = endpoint("source", MqEndpoint.Role.DECLARATION,
                MqDestination.rabbitQueue("source.queue", settings(
                        "dead.exchange", false, null, false, null, List.of())),
                "source", "sourceQueue");
        MqEndpoint exchange = endpoint("exchange", MqEndpoint.Role.EXCHANGE_DECLARATION,
                MqDestination.rabbitConsumer("dead.exchange", "", List.of(), "fanout"),
                "infrastructure", "deadExchange");
        MqEndpoint loopBinding = endpoint("loop-binding", MqEndpoint.Role.BINDING,
                MqDestination.rabbitConsumer("dead.exchange", "source.queue", List.of(), "fanout"),
                "infrastructure", "firstBinding");

        SimpleTree tree = createTree(List.of(source, exchange, loopBinding));
        DefaultMutableTreeNode sourceNode = findNodeContainingWithChildren(
                tree, "#sourceQueue", "key=<original>");
        DefaultMutableTreeNode targets = findChild(tree, sourceNode, "Dead-letter queues (1)");
        DefaultMutableTreeNode loopTarget = findDescendantContaining(tree, targets, "#sourceQueue");

        assertNotNull(loopTarget);
        assertEquals(0, loopTarget.getChildCount());
    }

    public void testTwentySpecialQueuesAreSearchableWithDynamicDiagnostics() {
        List<MqEndpoint> endpoints = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            List<String> diagnostics = index == 19
                    ? List.of("x-message-ttl is not statically resolved") : List.of();
            endpoints.add(endpoint("delay-" + index, MqEndpoint.Role.DECLARATION,
                    MqDestination.rabbitQueue("delay.queue." + index, settings(
                            "dead.exchange", false, null, true,
                            index == 19 ? null : (long) index, diagnostics)),
                    "module-" + index, "sourceQueue"));
        }

        long startedAt = System.nanoTime();
        MqTopologyPanel panel = createPanel(endpoints);
        SimpleTree tree = findTree(panel);
        onEdt(() -> {
            findComponent(panel, JBTextField.class).setText("delay.queue.19");
            return null;
        });
        waitFor("special queue search", () -> "RabbitMQ (1/20)".equals(rootLabel(tree)));

        DefaultMutableTreeNode dynamic = findNodeContaining(tree, "delay.queue.19", "ttl=?");
        assertNotNull(dynamic);
        assertTrue(tooltip(tree, dynamic).contains("x-message-ttl"));
        assertTrue((System.nanoTime() - startedAt) < 30_000_000_000L);
    }

    private MqDestination.RabbitQueueSettings settings(
            String deadLetterExchange,
            boolean routingKeyConfigured,
            String deadLetterRoutingKey,
            boolean ttlConfigured,
            Long ttlMillis,
            List<String> diagnostics) {
        return new MqDestination.RabbitQueueSettings(
                true, deadLetterExchange, routingKeyConfigured, deadLetterRoutingKey,
                ttlConfigured, ttlMillis, diagnostics);
    }

    private MqEndpoint endpoint(
            String endpointId,
            MqEndpoint.Role role,
            MqDestination destination,
            String moduleName,
            String sourceMethod) {
        return new MqEndpoint(endpointId, MqEndpoint.SystemType.RABBITMQ, role, destination,
                MqEndpoint.ResolutionState.STATIC, null, sourcePointers.get(sourceMethod), moduleName,
                sourceFile.getName(), "SpecialQueueSources", sourceMethod);
    }

    private MqTopologyPanel createPanel(List<MqEndpoint> endpoints) {
        Supplier<List<MqEndpoint>> endpointSupplier = () -> List.copyOf(endpoints);
        MqTopologyPanel panel = onEdt(() -> new MqTopologyPanel(getProject(), endpointSupplier));
        onEdt(() -> {
            panel.refresh();
            return null;
        });
        waitFor("special queue refresh", () -> !rootLabel(findTree(panel)).equals("RabbitMQ"));
        return panel;
    }

    private SimpleTree createTree(List<MqEndpoint> endpoints) {
        return findTree(createPanel(endpoints));
    }

    private String rootLabel(SimpleTree tree) {
        return onEdt(() -> renderedLabel(tree, (DefaultMutableTreeNode) tree.getModel().getRoot()));
    }

    private String tooltip(SimpleTree tree, DefaultMutableTreeNode node) {
        return onEdt(() -> {
            Component component = tree.getCellRenderer().getTreeCellRendererComponent(
                    tree, node, false, false, node.isLeaf(), 0, false);
            return ((JLabel) component).getToolTipText();
        });
    }

    private DefaultMutableTreeNode findNodeContaining(SimpleTree tree, String... fragments) {
        return onEdt(() -> {
            DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
            Enumeration<?> nodes = root.depthFirstEnumeration();
            while (nodes.hasMoreElements()) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
                String label = renderedLabel(tree, node);
                if (java.util.Arrays.stream(fragments).allMatch(label::contains)) {
                    return node;
                }
            }
            return null;
        });
    }

    private DefaultMutableTreeNode findNodeContainingWithChildren(SimpleTree tree, String... fragments) {
        return onEdt(() -> {
            DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
            Enumeration<?> nodes = root.depthFirstEnumeration();
            while (nodes.hasMoreElements()) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
                String label = renderedLabel(tree, node);
                if (node.getChildCount() > 0
                        && java.util.Arrays.stream(fragments).allMatch(label::contains)) {
                    return node;
                }
            }
            return null;
        });
    }

    private DefaultMutableTreeNode findDescendantContaining(
            SimpleTree tree, DefaultMutableTreeNode parent, String fragment) {
        return onEdt(() -> {
            Enumeration<?> nodes = parent.depthFirstEnumeration();
            while (nodes.hasMoreElements()) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
                if (renderedLabel(tree, node).contains(fragment)) {
                    return node;
                }
            }
            return null;
        });
    }

    private DefaultMutableTreeNode findChild(
            SimpleTree tree, DefaultMutableTreeNode parent, String expectedLabel) {
        return onEdt(() -> {
            for (int index = 0; index < parent.getChildCount(); index++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(index);
                if (expectedLabel.equals(renderedLabel(tree, child))) {
                    return child;
                }
            }
            return null;
        });
    }

    private DefaultMutableTreeNode findChildStartingWith(
            SimpleTree tree, DefaultMutableTreeNode parent, String prefix) {
        return onEdt(() -> {
            for (int index = 0; index < parent.getChildCount(); index++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(index);
                if (renderedLabel(tree, child).startsWith(prefix)) {
                    return child;
                }
            }
            return null;
        });
    }

    private void assertNavigatesTo(SimpleTree tree, DefaultMutableTreeNode endpointNode, PsiFile targetFile) {
        onEdt(() -> {
            FileEditorManager.getInstance(getProject()).closeFile(targetFile.getVirtualFile());
            TreePath path = new TreePath(endpointNode.getPath());
            tree.setSize(900, 700);
            tree.scrollPathToVisible(path);
            Rectangle bounds = tree.getPathBounds(path);
            assertNotNull(bounds);
            tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                    bounds.x + 4, bounds.y + Math.max(bounds.height / 2, 1), 2, false, MouseEvent.BUTTON1));
            return null;
        });
        waitFor("source navigation", () -> onEdt(() ->
                List.of(FileEditorManager.getInstance(getProject()).getSelectedFiles())
                        .contains(targetFile.getVirtualFile())));
    }

    private SimpleTree findTree(MqTopologyPanel panel) {
        return findComponent(panel, SimpleTree.class);
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
        java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        ApplicationManager.getApplication().invokeAndWait(() -> result.set(supplier.get()));
        return result.get();
    }

    private void waitFor(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                fail("Timed out waiting for " + description);
            }
            LockSupport.parkNanos(10_000_000L);
        }
    }
}
