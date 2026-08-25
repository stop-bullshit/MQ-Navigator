package cn.luckyh.mqx.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.impl.FakePsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.icons.AllIcons;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.treeStructure.SimpleTree;
import cn.luckyh.mqx.action.RefreshMqIndexAction;
import cn.luckyh.mqx.model.MqDestination;
import cn.luckyh.mqx.model.MqEndpoint;

import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;

/**
 * RabbitMQ 拓扑侧边栏行为测试.
 *
 * @author heng.wang
 * @since 2026-08-24
 */
public class MqTopologyPanelTest extends BasePlatformTestCase {

    private PsiElement sourceElement;
    private SmartPsiElementPointer<PsiElement> sourcePointer;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        sourceElement = myFixture.configureByText(
                "EndpointSource.java", "class EndpointSource { void source() {} }");
        sourcePointer = ReadAction.compute(() -> SmartPointerManager.getInstance(getProject())
                .createSmartPsiElementPointer(sourceElement));
    }

    @Override
    protected boolean runInDispatchThread() {
        return false;
    }

    public void testRootExpandedAndModulesDefaultCollapsed() throws Exception {
        ControlledEndpointSupplier endpointSupplier = new ControlledEndpointSupplier();
        endpointSupplier.succeed(moduleEndpoints(10, true));
        MqTopologyPanel panel = createPanel(endpointSupplier);

        refresh(panel, endpointSupplier);

        SimpleTree tree = onEdt(() -> findTree(panel));
        DefaultMutableTreeNode root = onEdt(() -> (DefaultMutableTreeNode) tree.getModel().getRoot());
        assertTrue(onEdt(() -> tree.isExpanded(new TreePath(root.getPath()))));
        assertEquals(11, root.getChildCount());
        for (int index = 0; index < root.getChildCount(); index++) {
            DefaultMutableTreeNode moduleNode = (DefaultMutableTreeNode) root.getChildAt(index);
            assertFalse(nodeLabel(tree, moduleNode),
                    onEdt(() -> tree.isExpanded(new TreePath(moduleNode.getPath()))));
        }
        assertNotNull(findNode(tree, "module-00 (3)"));
        assertNotNull(findNode(tree, "(project) (1)"));
    }

    public void testExpandingOneModuleKeepsOtherModuleStateAndCategories() throws Exception {
        ControlledEndpointSupplier endpointSupplier = new ControlledEndpointSupplier();
        endpointSupplier.succeed(moduleEndpoints(10, true));
        MqTopologyPanel panel = createPanel(endpointSupplier);
        refresh(panel, endpointSupplier);
        SimpleTree tree = onEdt(() -> findTree(panel));
        DefaultMutableTreeNode firstModule = findNode(tree, "module-00 (3)");
        DefaultMutableTreeNode secondModule = findNode(tree, "module-01 (3)");

        onEdt(() -> {
            tree.collapsePath(new TreePath(firstModule.getPath()));
            tree.collapsePath(new TreePath(secondModule.getPath()));
            tree.expandPath(new TreePath(firstModule.getPath()));
            return null;
        });

        assertTrue(onEdt(() -> tree.isExpanded(new TreePath(firstModule.getPath()))));
        assertFalse(onEdt(() -> tree.isExpanded(new TreePath(secondModule.getPath()))));
        assertEquals(List.of("Consumers (1)", "Producers (1)", "Topology (1)"),
                childLabels(tree, firstModule));
        DefaultMutableTreeNode topology = findChild(tree, firstModule, "Topology (1)");
        assertEquals(List.of("Queues (1)"), childLabels(tree, topology));
    }

    public void testRefreshKeepsExistingModuleStateAndUpdatesModules() throws Exception {
        ControlledEndpointSupplier endpointSupplier = new ControlledEndpointSupplier();
        endpointSupplier.succeed(List.of(
                endpoint("alpha-producer", "alpha", MqEndpoint.Role.PRODUCER, "alpha"),
                endpoint("beta-producer", "beta", MqEndpoint.Role.PRODUCER, "beta")));
        MqTopologyPanel panel = createPanel(endpointSupplier);
        refresh(panel, endpointSupplier);
        SimpleTree tree = onEdt(() -> findTree(panel));
        DefaultMutableTreeNode alpha = findNode(tree, "alpha (1)");
        onEdt(() -> {
            tree.expandPath(new TreePath(alpha.getPath()));
            return null;
        });

        endpointSupplier.succeed(List.of(
                endpoint("alpha-producer", "alpha", MqEndpoint.Role.PRODUCER, "alpha-updated"),
                endpoint("gamma-producer", "gamma", MqEndpoint.Role.PRODUCER, "gamma")));
        refresh(panel, endpointSupplier);

        DefaultMutableTreeNode refreshedAlpha = findNode(tree, "alpha (1)");
        DefaultMutableTreeNode gamma = findNode(tree, "gamma (1)");
        assertNull(findNode(tree, "beta (1)"));
        assertTrue(onEdt(() -> tree.isExpanded(new TreePath(refreshedAlpha.getPath()))));
        assertFalse(onEdt(() -> tree.isExpanded(new TreePath(gamma.getPath()))));
    }

    public void testEmptyFailureAndRecoveryHaveDistinctResults() throws Exception {
        ControlledEndpointSupplier endpointSupplier = new ControlledEndpointSupplier();
        endpointSupplier.succeed(List.of(
                endpoint("alpha-producer", "alpha", MqEndpoint.Role.PRODUCER, "alpha")));
        MqTopologyPanel panel = createPanel(endpointSupplier);
        refresh(panel, endpointSupplier);
        SimpleTree tree = onEdt(() -> findTree(panel));

        endpointSupplier.fail(new IllegalStateException("controlled failure"));
        onEdt(() -> {
            panel.refresh();
            return null;
        });
        endpointSupplier.awaitInvocation();
        waitFor("refresh failure feedback", () ->
                "刷新失败，已保留上次结果".equals(statusText(panel)));
        assertNotNull(findNode(tree, "alpha (1)"));

        endpointSupplier.succeed(List.of());
        refresh(panel, endpointSupplier);
        assertEquals(0, onEdt(() ->
                ((DefaultMutableTreeNode) tree.getModel().getRoot()).getChildCount()).intValue());
        assertEquals("未发现 RabbitMQ 端点", statusText(panel));

        endpointSupplier.succeed(List.of(
                endpoint("gamma-producer", "gamma", MqEndpoint.Role.PRODUCER, "gamma")));
        refresh(panel, endpointSupplier);
        assertEquals("", statusText(panel));
        assertNotNull(findNode(tree, "gamma (1)"));
    }

    public void testRefreshShowsIndexAndProgressState() throws Exception {
        ControlledEndpointSupplier endpointSupplier = new ControlledEndpointSupplier();
        endpointSupplier.succeed(List.of(
                endpoint("alpha-producer", "alpha", MqEndpoint.Role.PRODUCER, "alpha")));
        MqTopologyPanel panel = createPanel(endpointSupplier);
        DumbModeTestUtils.EternalTaskShutdownToken token =
                DumbModeTestUtils.startEternalDumbModeTask(getProject());
        try {
            onEdt(() -> {
                panel.refresh();
                return null;
            });
            assertTrue(DumbService.isDumb(getProject()));
            assertEquals("等待项目索引完成", statusText(panel));
            assertFalse(onEdt(() -> refreshProgressBar(panel).isVisible()));
            assertFalse(onEdt(() -> refreshProgressBar(panel).isIndeterminate()));
        } finally {
            DumbModeTestUtils.endEternalDumbModeTaskAndWaitForSmartMode(getProject(), token);
        }
        endpointSupplier.awaitApplied();
        waitFor("refresh completion", () -> statusText(panel).isEmpty());
        assertFalse(onEdt(() -> refreshProgressBar(panel).isVisible()));
    }

    public void testProgressStateMatchesRepeatedOutcomes() throws Exception {
        ControlledEndpointSupplier endpointSupplier = new ControlledEndpointSupplier();
        MqTopologyPanel panel = createPanel(endpointSupplier);

        for (int index = 0; index < 5; index++) {
            endpointSupplier.succeed(List.of(endpoint(
                    "success-" + index, "success", MqEndpoint.Role.PRODUCER, "key-" + index)));
            onEdt(() -> {
                panel.refresh();
                assertProgressActive(panel);
                return null;
            });
            endpointSupplier.awaitApplied();
            waitFor("successful terminal state " + index, () -> statusText(panel).isEmpty());
            assertProgressInactive(panel);
        }

        for (int index = 0; index < 5; index++) {
            endpointSupplier.succeed(List.of());
            onEdt(() -> {
                panel.refresh();
                assertProgressActive(panel);
                return null;
            });
            endpointSupplier.awaitApplied();
            waitFor("empty terminal state " + index, () ->
                    "未发现 RabbitMQ 端点".equals(statusText(panel)));
            assertProgressInactive(panel);
        }

        for (int index = 0; index < 5; index++) {
            endpointSupplier.fail(new IllegalStateException("controlled failure " + index));
            onEdt(() -> {
                panel.refresh();
                assertProgressActive(panel);
                return null;
            });
            endpointSupplier.awaitInvocation();
            waitFor("failure terminal state " + index, () ->
                    "刷新失败，已保留上次结果".equals(statusText(panel)));
            assertProgressInactive(panel);
        }

        for (int index = 0; index < 5; index++) {
            endpointSupplier.succeed(List.of(endpoint(
                    "index-" + index, "index", MqEndpoint.Role.PRODUCER, "key-" + index)));
            DumbModeTestUtils.EternalTaskShutdownToken token =
                    DumbModeTestUtils.startEternalDumbModeTask(getProject());
            try {
                onEdt(() -> {
                    panel.refresh();
                    assertEquals("等待项目索引完成", statusText(panel));
                    assertProgressInactive(panel);
                    return null;
                });
            } finally {
                DumbModeTestUtils.endEternalDumbModeTaskAndWaitForSmartMode(getProject(), token);
            }
            endpointSupplier.awaitApplied();
            waitFor("indexed refresh completion " + index, () -> statusText(panel).isEmpty());
            assertProgressInactive(panel);
        }
        System.out.println("refresh-progress-states success=5 empty=5 failure=5 index-wait=5 residue=0");
    }

    public void testLatestRefreshResultWinsWhenRequestsFinishOutOfOrder() throws Exception {
        int groupCount = 10;
        List<List<MqEndpoint>> endpointResults = new ArrayList<>();
        for (int group = 0; group < groupCount; group++) {
            endpointResults.add(List.of(endpoint(
                    "old-before-" + group, "old-before-" + group,
                    MqEndpoint.Role.PRODUCER, "old-before-" + group)));
            endpointResults.add(List.of(endpoint(
                    "old-after-" + group, "old-after-" + group,
                    MqEndpoint.Role.PRODUCER, "old-after-" + group)));
            endpointResults.add(List.of(endpoint(
                    "latest-" + group, "latest-" + group,
                    MqEndpoint.Role.PRODUCER, "latest-" + group)));
        }
        BlockingEndpointSupplier endpointSupplier = new BlockingEndpointSupplier(endpointResults);
        MqTopologyPanel panel = onEdt(() -> new MqTopologyPanel(getProject(), endpointSupplier));
        SimpleTree tree = onEdt(() -> findTree(panel));

        for (int group = 0; group < groupCount; group++) {
            int firstIndex = group * 3;
            for (int requestOffset = 0; requestOffset < 3; requestOffset++) {
                onEdt(() -> {
                    panel.refresh();
                    return null;
                });
                endpointSupplier.awaitEntered(firstIndex + requestOffset);
            }

            endpointSupplier.release(firstIndex);
            endpointSupplier.awaitApplied(firstIndex);
            onEdt(() -> null);
            assertProgressActive(panel);

            endpointSupplier.release(firstIndex + 2);
            endpointSupplier.awaitApplied(firstIndex + 2);
            waitFor("latest refresh completion group " + group, () ->
                    !onEdt(() -> refreshProgressBar(panel).isVisible()));
            assertProgressInactive(panel);
            assertNotNull(findNode(tree, "latest-" + group + " (1)"));

            endpointSupplier.release(firstIndex + 1);
            endpointSupplier.awaitApplied(firstIndex + 1);
            onEdt(() -> null);
            assertProgressInactive(panel);
            assertNotNull(findNode(tree, "latest-" + group + " (1)"));
            assertNull(findNode(tree, "old-before-" + group + " (1)"));
            assertNull(findNode(tree, "old-after-" + group + " (1)"));
        }
        System.out.println("refresh-request-order groups=10 premature-close=0 reactivated=0 stale-overwrite=0");
    }

    public void testRefreshProgressLifecycleKeepsTreeAndLayoutStable() throws Exception {
        BlockingEndpointSupplier endpointSupplier = new BlockingEndpointSupplier(List.of(
                List.of(endpoint("alpha-producer", "alpha", MqEndpoint.Role.PRODUCER, "alpha")),
                List.of(endpoint("beta-producer", "beta", MqEndpoint.Role.PRODUCER, "beta"))));
        MqTopologyPanel panel = onEdt(() -> new MqTopologyPanel(getProject(), endpointSupplier));
        onEdt(() -> {
            panel.refresh();
            return null;
        });
        endpointSupplier.awaitEntered(0);
        endpointSupplier.release(0);
        endpointSupplier.awaitApplied(0);
        waitFor("initial refresh completion", () -> statusText(panel).isEmpty());

        SimpleTree tree = onEdt(() -> findTree(panel));
        assertNotNull(findNode(tree, "alpha (1)"));
        int initialTreeHeight = onEdt(() -> {
            panel.setSize(420, 560);
            layoutRecursively(panel);
            return findComponent(panel, JScrollPane.class).getHeight();
        });

        onEdt(() -> {
            panel.refresh();
            layoutRecursively(panel);
            JProgressBar progressBar = refreshProgressBar(panel);
            JBLabel statusLabel = findNamedComponent(panel, JBLabel.class, "mqTopologyStatus");
            Rectangle statusBounds = SwingUtilities.convertRectangle(
                    statusLabel.getParent(), statusLabel.getBounds(), panel);
            Rectangle progressBounds = SwingUtilities.convertRectangle(
                    progressBar.getParent(), progressBar.getBounds(), panel);
            assertTrue(progressBar.isVisible());
            assertTrue(progressBar.isIndeterminate());
            assertFalse(progressBar.isStringPainted());
            assertFalse(statusBounds.intersects(progressBounds));
            assertEquals(initialTreeHeight, findComponent(panel, JScrollPane.class).getHeight());
            return null;
        });
        endpointSupplier.awaitEntered(1);
        assertNotNull(findNode(tree, "alpha (1)"));
        endpointSupplier.release(1);
        endpointSupplier.awaitApplied(1);
        waitFor("successful refresh completion", () ->
                !onEdt(() -> refreshProgressBar(panel).isVisible()));
        assertFalse(onEdt(() -> refreshProgressBar(panel).isIndeterminate()));
        assertNotNull(findNode(tree, "beta (1)"));
        assertEquals(initialTreeHeight, onEdt(() -> {
            layoutRecursively(panel);
            return findComponent(panel, JScrollPane.class).getHeight();
        }).intValue());

        ControlledEndpointSupplier failureSupplier = new ControlledEndpointSupplier();
        failureSupplier.fail(new IllegalStateException("controlled progress failure"));
        MqTopologyPanel failurePanel = createPanel(failureSupplier);
        onEdt(() -> {
            failurePanel.refresh();
            return null;
        });
        failureSupplier.awaitInvocation();
        waitFor("failed refresh completion", () ->
                "刷新失败，已保留上次结果".equals(statusText(failurePanel)));
        assertFalse(onEdt(() -> refreshProgressBar(failurePanel).isVisible()));
        assertFalse(onEdt(() -> refreshProgressBar(failurePanel).isIndeterminate()));
    }

    public void testRefreshProgressAllowsBrowsingInNarrowPanel() throws Exception {
        PsiFile targetFile = myFixture.addFileToProject(
                "ProgressNavigationTarget.java", "class ProgressNavigationTarget { void publish() {} }");
        PsiMethod targetMethod = ReadAction.compute(() -> PsiTreeUtil.findChildOfType(targetFile, PsiMethod.class));
        assertNotNull(targetMethod);
        SmartPsiElementPointer<PsiElement> targetPointer = ReadAction.compute(() ->
                SmartPointerManager.getInstance(getProject()).createSmartPsiElementPointer(targetMethod));
        MqEndpoint dynamic = new MqEndpoint(
                "dynamic-progress", MqEndpoint.SystemType.RABBITMQ, MqEndpoint.Role.PRODUCER,
                MqDestination.rabbitProducer("events", "dynamic-progress"), MqEndpoint.ResolutionState.DYNAMIC,
                "runtime destination", targetPointer, "gamma", "ProgressNavigationTarget.java",
                "ProgressNavigationTarget", "publish");
        BlockingEndpointSupplier endpointSupplier = new BlockingEndpointSupplier(List.of(
                List.of(
                        endpoint("alpha-producer", "alpha", MqEndpoint.Role.PRODUCER, "alpha"),
                        endpoint("alpha-consumer", "alpha", MqEndpoint.Role.CONSUMER, "alpha-queue"),
                        endpoint("beta-producer", "beta", MqEndpoint.Role.PRODUCER, "beta")),
                List.of(
                        endpoint("alpha-latest", "alpha", MqEndpoint.Role.PRODUCER, "alpha-latest"),
                        endpoint("beta-latest", "beta", MqEndpoint.Role.PRODUCER, "beta-latest"),
                        dynamic)));
        MqTopologyPanel panel = onEdt(() -> new MqTopologyPanel(getProject(), endpointSupplier));
        onEdt(() -> {
            panel.refresh();
            return null;
        });
        endpointSupplier.awaitEntered(0);
        endpointSupplier.release(0);
        endpointSupplier.awaitApplied(0);
        waitFor("browsing baseline refresh", () -> statusText(panel).isEmpty());

        SimpleTree tree = onEdt(() -> findTree(panel));
        DefaultMutableTreeNode alpha = findNode(tree, "alpha (2)");
        DefaultMutableTreeNode beta = findNode(tree, "beta (1)");
        onEdt(() -> {
            tree.expandPath(new TreePath(alpha.getPath()));
            tree.collapsePath(new TreePath(beta.getPath()));
            panel.setSize(360, 560);
            layoutRecursively(panel);
            return null;
        });
        int beforeHeight = onEdt(() -> findComponent(panel, JScrollPane.class).getHeight());

        onEdt(() -> {
            panel.refresh();
            return null;
        });
        endpointSupplier.awaitEntered(1);
        onEdt(() -> {
            tree.collapsePath(new TreePath(alpha.getPath()));
            tree.expandPath(new TreePath(alpha.getPath()));
            assertProgressActive(panel);
            return null;
        });
        assertFalse(onEdt(() -> tree.isExpanded(new TreePath(beta.getPath()))));

        setSearch(panel, "alpha");
        assertEquals("RabbitMQ (2/3)", rootLabel(tree));
        assertProgressActive(panel);
        setFilter(panel, "Producers");
        assertEquals("RabbitMQ (1/3)", rootLabel(tree));
        assertProgressActive(panel);
        setSearch(panel, "");
        assertEquals("RabbitMQ (2/3)", rootLabel(tree));
        setFilter(panel, "All");
        DefaultMutableTreeNode restoredAlpha = findNode(tree, "alpha (2)");
        DefaultMutableTreeNode restoredBeta = findNode(tree, "beta (1)");
        assertTrue(onEdt(() -> tree.isExpanded(new TreePath(restoredAlpha.getPath()))));
        assertFalse(onEdt(() -> tree.isExpanded(new TreePath(restoredBeta.getPath()))));
        assertProgressActive(panel);
        setFilter(panel, "Unresolved");
        assertEquals("RabbitMQ (0/3)", rootLabel(tree));
        assertEquals("正在刷新 RabbitMQ 端点", statusText(panel));

        List<Rectangle> activeBounds = onEdt(() -> {
            layoutRecursively(panel);
            JBLabel statusLabel = findNamedComponent(panel, JBLabel.class, "mqTopologyStatus");
            JProgressBar progressBar = refreshProgressBar(panel);
            Rectangle statusBounds = SwingUtilities.convertRectangle(
                    statusLabel.getParent(), statusLabel.getBounds(), panel);
            Rectangle progressBounds = SwingUtilities.convertRectangle(
                    progressBar.getParent(), progressBar.getBounds(), panel);
            assertFalse(statusBounds.intersects(progressBounds));
            assertProgressActive(panel);
            return List.of(statusBounds, progressBounds);
        });
        int duringHeight = onEdt(() -> findComponent(panel, JScrollPane.class).getHeight());
        assertEquals(beforeHeight, duringHeight);

        endpointSupplier.release(1);
        endpointSupplier.awaitApplied(1);
        waitFor("browsing latest refresh", () ->
                !onEdt(() -> refreshProgressBar(panel).isVisible()));
        assertProgressInactive(panel);
        assertEquals("RabbitMQ (1/3)", rootLabel(tree));
        String dynamicLabel =
                "ProgressNavigationTarget#publish — exchange=events, key=dynamic-progress [dynamic]";
        DefaultMutableTreeNode dynamicNode = findNode(tree, dynamicLabel);
        assertNotNull(dynamicNode);
        assertEquals("runtime destination", tooltip(tree, dynamicNode));

        int afterHeight = onEdt(() -> {
            layoutRecursively(panel);
            return findComponent(panel, JScrollPane.class).getHeight();
        });
        assertEquals(beforeHeight, afterHeight);
        setFilter(panel, "All");
        assertEquals("RabbitMQ (3)", rootLabel(tree));
        DefaultMutableTreeNode latestAlpha = findNode(tree, "alpha (1)");
        DefaultMutableTreeNode latestBeta = findNode(tree, "beta (1)");
        assertTrue(onEdt(() -> tree.isExpanded(new TreePath(latestAlpha.getPath()))));
        assertFalse(onEdt(() -> tree.isExpanded(new TreePath(latestBeta.getPath()))));
        assertNavigatesTo(tree, dynamicLabel, targetFile);
        System.out.printf("refresh-layout width=360 tree-before=%d tree-during=%d tree-after=%d "
                        + "status=%s progress=%s%n",
                beforeHeight, duringHeight, afterHeight, activeBounds.get(0), activeBounds.get(1));
    }

    public void testRefreshProgressAppearsWithinLatencyBudget() throws Exception {
        int warmupCount = 5;
        int sampleCount = 20;
        List<List<MqEndpoint>> endpointResults = new ArrayList<>();
        for (int index = 0; index < warmupCount + sampleCount; index++) {
            endpointResults.add(List.of(endpoint(
                    "latency-" + index, "latency", MqEndpoint.Role.PRODUCER, "key-" + index)));
        }
        BlockingEndpointSupplier endpointSupplier = new BlockingEndpointSupplier(endpointResults);
        MqTopologyPanel panel = onEdt(() -> new MqTopologyPanel(getProject(), endpointSupplier));
        List<Long> latencyMillis = new ArrayList<>();

        for (int index = 0; index < warmupCount + sampleCount; index++) {
            long startedAt = System.nanoTime();
            long visibleAt = onEdt(() -> {
                panel.refresh();
                JProgressBar progressBar = refreshProgressBar(panel);
                assertTrue(progressBar.isVisible());
                assertTrue(progressBar.isIndeterminate());
                return System.nanoTime();
            });
            endpointSupplier.awaitEntered(index);
            long latency = TimeUnit.NANOSECONDS.toMillis(visibleAt - startedAt);
            if (index >= warmupCount) {
                latencyMillis.add(latency);
            }
            long heldNanos = System.nanoTime() - startedAt;
            LockSupport.parkNanos(Math.max(0L, TimeUnit.MILLISECONDS.toNanos(500) - heldNanos));
            endpointSupplier.release(index);
            endpointSupplier.awaitApplied(index);
            waitFor("latency refresh completion " + index, () ->
                    !onEdt(() -> refreshProgressBar(panel).isVisible()));
        }

        Collections.sort(latencyMillis);
        long p50 = percentile(latencyMillis, 50);
        long p95 = percentile(latencyMillis, 95);
        long max = latencyMillis.get(latencyMillis.size() - 1);
        System.out.printf("refresh-progress-latency n=%d p50=%dms p95=%dms max=%dms%n",
                latencyMillis.size(), p50, p95, max);
        assertTrue("Refresh progress p95 was " + p95 + "ms", p95 <= 300L);
    }

    public void testRefreshActionUsesPlatformIconAndTooltip() {
        RefreshMqIndexAction action = new RefreshMqIndexAction();

        assertSame(AllIcons.Actions.Refresh, action.getTemplatePresentation().getIcon());
        assertEquals("Refresh RabbitMQ topology", action.getTemplatePresentation().getText());
        assertEquals("Rescan MQ endpoints", action.getTemplatePresentation().getDescription());
    }

    public void testSearchAndTypeFiltersTemporarilyExpandCategoriesAndRestoreState() throws Exception {
        ControlledEndpointSupplier endpointSupplier = new ControlledEndpointSupplier();
        endpointSupplier.succeed(List.of(
                endpoint("alpha-producer", "alpha", MqEndpoint.Role.PRODUCER, "shared"),
                new MqEndpoint("alpha-consumer", MqEndpoint.SystemType.RABBITMQ, MqEndpoint.Role.CONSUMER,
                        MqDestination.rabbitConsumer("events", "alpha-queue", List.of("shared"), "direct"),
                        MqEndpoint.ResolutionState.STATIC, null, sourcePointer, "alpha",
                        "EndpointSource.java", "EndpointSource", "alpha-consumer"),
                endpoint("beta-producer", "beta", MqEndpoint.Role.PRODUCER, "beta-key")));
        MqTopologyPanel panel = createPanel(endpointSupplier);
        refresh(panel, endpointSupplier);
        SimpleTree tree = onEdt(() -> findTree(panel));
        DefaultMutableTreeNode alpha = findNode(tree, "alpha (2)");
        onEdt(() -> {
            tree.expandPath(new TreePath(alpha.getPath()));
            return null;
        });

        setSearch(panel, "shared");
        DefaultMutableTreeNode filteredAlpha = findNode(tree, "alpha (2)");
        DefaultMutableTreeNode consumers = findChild(tree, filteredAlpha, "Consumers (1)");
        DefaultMutableTreeNode producers = findChild(tree, filteredAlpha, "Producers (1)");
        DefaultMutableTreeNode producer = findNode(
                tree, "EndpointSource#alpha-producer — exchange=events, key=shared");
        assertEquals("RabbitMQ (2/3)", rootLabel(tree));
        assertTrue(onEdt(() -> tree.isExpanded(new TreePath(filteredAlpha.getPath()))));
        assertTrue(onEdt(() -> tree.isExpanded(new TreePath(consumers.getPath()))));
        assertTrue(onEdt(() -> tree.isExpanded(new TreePath(producers.getPath()))));
        assertFalse(onEdt(() -> tree.isExpanded(new TreePath(producer.getPath()))));
        onEdt(() -> {
            tree.collapsePath(new TreePath(filteredAlpha.getPath()));
            return null;
        });

        setSearch(panel, "");
        DefaultMutableTreeNode restoredAlpha = findNode(tree, "alpha (2)");
        DefaultMutableTreeNode restoredBeta = findNode(tree, "beta (1)");
        assertEquals("RabbitMQ (3)", rootLabel(tree));
        assertTrue(onEdt(() -> tree.isExpanded(new TreePath(restoredAlpha.getPath()))));
        assertFalse(onEdt(() -> tree.isExpanded(new TreePath(restoredBeta.getPath()))));

        setFilter(panel, "Producers");
        DefaultMutableTreeNode producerAlpha = findNode(tree, "alpha (1)");
        DefaultMutableTreeNode producerBeta = findNode(tree, "beta (1)");
        assertEquals("RabbitMQ (2/3)", rootLabel(tree));
        assertTrue(onEdt(() -> tree.isExpanded(new TreePath(producerAlpha.getPath()))));
        assertTrue(onEdt(() -> tree.isExpanded(new TreePath(producerBeta.getPath()))));
        setFilter(panel, "All");
        assertTrue(onEdt(() -> tree.isExpanded(new TreePath(findNode(tree, "alpha (2)").getPath()))));
        assertFalse(onEdt(() -> tree.isExpanded(new TreePath(findNode(tree, "beta (1)").getPath()))));
    }

    public void testAllTypeFiltersNoMatchAndDynamicFeedback() throws Exception {
        ControlledEndpointSupplier endpointSupplier = new ControlledEndpointSupplier();
        MqEndpoint dynamic = new MqEndpoint(
                "dynamic-producer", MqEndpoint.SystemType.RABBITMQ, MqEndpoint.Role.PRODUCER,
                MqDestination.rabbitProducer("events", "dynamic"), MqEndpoint.ResolutionState.DYNAMIC,
                "destination comes from runtime", sourcePointer, "types",
                "EndpointSource.java", "EndpointSource", "dynamic-producer");
        endpointSupplier.succeed(List.of(
                endpoint("static-producer", "types", MqEndpoint.Role.PRODUCER, "static"),
                dynamic,
                endpoint("consumer", "types", MqEndpoint.Role.CONSUMER, "queue"),
                endpoint("declaration", "types", MqEndpoint.Role.DECLARATION, "declared")));
        MqTopologyPanel panel = createPanel(endpointSupplier);
        refresh(panel, endpointSupplier);
        SimpleTree tree = onEdt(() -> findTree(panel));

        for (FilterExpectation expectation : List.of(
                new FilterExpectation("Consumers", "types (1)", "Consumers (1)", null),
                new FilterExpectation("Producers", "types (2)", "Producers (2)", null),
                new FilterExpectation("Topology", "types (1)", "Topology (1)", "Queues (1)"),
                new FilterExpectation("Unresolved", "types (1)", "Producers (1)", null))) {
            setFilter(panel, expectation.filter());
            DefaultMutableTreeNode module = findNode(tree, expectation.moduleLabel());
            DefaultMutableTreeNode category = findChild(tree, module, expectation.categoryLabel());
            assertTrue(expectation.filter(), onEdt(() -> tree.isExpanded(new TreePath(module.getPath()))));
            assertTrue(expectation.filter(), onEdt(() -> tree.isExpanded(new TreePath(category.getPath()))));
            if (expectation.childCategoryLabel() != null) {
                DefaultMutableTreeNode childCategory = findChild(tree, category, expectation.childCategoryLabel());
                assertTrue(onEdt(() -> tree.isExpanded(new TreePath(childCategory.getPath()))));
            }
        }

        DefaultMutableTreeNode dynamicNode = findNode(
                tree, "EndpointSource#dynamic-producer — exchange=events, key=dynamic [dynamic]");
        assertEquals("destination comes from runtime", tooltip(tree, dynamicNode));
        endpointSupplier.succeed(List.of(
                endpoint("static-producer", "types", MqEndpoint.Role.PRODUCER, "static"),
                dynamic,
                endpoint("consumer", "types", MqEndpoint.Role.CONSUMER, "queue"),
                endpoint("declaration", "types", MqEndpoint.Role.DECLARATION, "declared")));
        refresh(panel, endpointSupplier);
        assertNotNull(findNode(tree,
                "EndpointSource#dynamic-producer — exchange=events, key=dynamic [dynamic]"));

        setFilter(panel, "All");
        setSearch(panel, "missing-value");
        assertEquals("RabbitMQ (0/4)", rootLabel(tree));
        assertEquals("当前条件无匹配结果", statusText(panel));
        setSearch(panel, "");
        assertEquals("", statusText(panel));
    }

    public void testDynamicEndpointNavigatesToSameSourceInNormalSearchAndFilterViews() throws Exception {
        PsiFile targetFile = myFixture.addFileToProject(
                "NavigationTarget.java", "class NavigationTarget { void publish() {} }");
        PsiMethod targetMethod = ReadAction.compute(() -> PsiTreeUtil.findChildOfType(targetFile, PsiMethod.class));
        assertNotNull(targetMethod);
        SmartPsiElementPointer<PsiElement> targetPointer = ReadAction.compute(() ->
                SmartPointerManager.getInstance(getProject()).createSmartPsiElementPointer(targetMethod));
        MqEndpoint dynamic = new MqEndpoint(
                "dynamic-target", MqEndpoint.SystemType.RABBITMQ, MqEndpoint.Role.PRODUCER,
                MqDestination.rabbitProducer("events", "navigation-key"), MqEndpoint.ResolutionState.DYNAMIC,
                "runtime destination", targetPointer, "navigation",
                "NavigationTarget.java", "NavigationTarget", "publish");
        ControlledEndpointSupplier endpointSupplier = new ControlledEndpointSupplier();
        endpointSupplier.succeed(List.of(dynamic));
        MqTopologyPanel panel = createPanel(endpointSupplier);
        refresh(panel, endpointSupplier);
        SimpleTree tree = onEdt(() -> findTree(panel));
        String label = "NavigationTarget#publish — exchange=events, key=navigation-key [dynamic]";

        for (int iteration = 0; iteration < 20; iteration++) {
            long startedAt = System.nanoTime();
            assertNavigatesTo(tree, label, targetFile);
            assertTrue("ordinary navigation ns=" + (System.nanoTime() - startedAt),
                    System.nanoTime() - startedAt < 1_000_000_000L);
        }
        setSearch(panel, "navigation-key");
        assertNavigatesTo(tree, label, targetFile);
        setSearch(panel, "");
        setFilter(panel, "Unresolved");
        assertNavigatesTo(tree, label, targetFile);
    }

    public void testDeletedNavigationTargetsDoNotOpenWrongSourceTenTimes() throws Exception {
        PsiFile stableFile = myFixture.addFileToProject(
                "StableNavigationTarget.java", "class StableNavigationTarget { void stable() {} }");
        onEdt(() -> {
            FileEditorManager.getInstance(getProject()).openFile(stableFile.getVirtualFile(), true);
            return null;
        });

        List<MqEndpoint> staleEndpoints = new ArrayList<>();
        List<com.intellij.openapi.vfs.VirtualFile> staleFiles = new ArrayList<>();
        for (int iteration = 0; iteration < 10; iteration++) {
            PsiFile staleFile = myFixture.addFileToProject(
                    "StaleNavigationTarget" + iteration + ".java",
                    "class StaleNavigationTarget" + iteration + " { void stale" + iteration + "() {} }");
            PsiMethod staleMethod = ReadAction.compute(() -> PsiTreeUtil.findChildOfType(staleFile, PsiMethod.class));
            assertNotNull(staleMethod);
            SmartPsiElementPointer<PsiElement> stalePointer = ReadAction.compute(() ->
                    SmartPointerManager.getInstance(getProject()).createSmartPsiElementPointer(staleMethod));
            staleEndpoints.add(new MqEndpoint(
                    "stale-" + iteration, MqEndpoint.SystemType.RABBITMQ, MqEndpoint.Role.PRODUCER,
                    MqDestination.rabbitProducer("events", "stale-" + iteration),
                    MqEndpoint.ResolutionState.STATIC, null, stalePointer, "stale",
                    staleFile.getName(), "StaleNavigationTarget" + iteration, "stale" + iteration));
            staleFiles.add(staleFile.getVirtualFile());
        }
        ControlledEndpointSupplier staleSupplier = new ControlledEndpointSupplier();
        staleSupplier.succeed(staleEndpoints);
        MqTopologyPanel stalePanel = createPanel(staleSupplier);
        refresh(stalePanel, staleSupplier);
        SimpleTree staleTree = onEdt(() -> findTree(stalePanel));
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            for (com.intellij.openapi.vfs.VirtualFile staleFile : staleFiles) {
                try {
                    staleFile.delete(this);
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException(exception);
                }
            }
        });
        for (int iteration = 0; iteration < 10; iteration++) {
            DefaultMutableTreeNode staleNode = findNode(staleTree,
                    "StaleNavigationTarget" + iteration + "#stale" + iteration
                            + " — exchange=events, key=stale-" + iteration);
            assertNotNull(staleNode);
            doubleClick(staleTree, staleNode);
            assertEquals(stableFile.getVirtualFile(),
                    FileEditorManager.getInstance(getProject()).getSelectedFiles()[0]);
        }
        LockSupport.parkNanos(200_000_000L);
        onEdt(() -> {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            return null;
        });
        assertEquals(stableFile.getVirtualFile(),
                FileEditorManager.getInstance(getProject()).getSelectedFiles()[0]);
    }

    public void testNonNavigableAndCategoryNodesDoNotOpenWrongSourceTenTimes() throws Exception {
        PsiFile stableFile = myFixture.addFileToProject(
                "StableNonNavigableTarget.java", "class StableNonNavigableTarget { void stable() {} }");
        onEdt(() -> {
            FileEditorManager.getInstance(getProject()).openFile(stableFile.getVirtualFile(), true);
            return null;
        });
        PsiElement nonNavigableElement = new FakePsiElement() {
            @Override
            public PsiElement getParent() {
                return null;
            }

            @Override
            public String getName() {
                return "NonNavigableTarget";
            }

            @Override
            public PsiElement copy() {
                return this;
            }

            @Override
            public PsiFile getContainingFile() {
                return null;
            }

            @Override
            public Project getProject() {
                return MqTopologyPanelTest.this.getProject();
            }

            @Override
            public boolean isValid() {
                return true;
            }

            @Override
            public boolean canNavigate() {
                return false;
            }
        };
        SmartPsiElementPointer<PsiElement> nonNavigablePointer = new SmartPsiElementPointer<>() {
            @Override
            public PsiElement getElement() {
                return nonNavigableElement;
            }

            @Override
            public PsiFile getContainingFile() {
                return null;
            }

            @Override
            public Project getProject() {
                return MqTopologyPanelTest.this.getProject();
            }

            @Override
            public com.intellij.openapi.vfs.VirtualFile getVirtualFile() {
                return null;
            }

            @Override
            public com.intellij.openapi.util.Segment getRange() {
                return null;
            }

            @Override
            public com.intellij.openapi.util.Segment getPsiRange() {
                return null;
            }
        };
        MqEndpoint nonNavigableEndpoint = new MqEndpoint(
                "non-navigable", MqEndpoint.SystemType.RABBITMQ, MqEndpoint.Role.PRODUCER,
                MqDestination.rabbitProducer("events", "non-navigable"), MqEndpoint.ResolutionState.STATIC,
                null, nonNavigablePointer, "non-navigable", "NonNavigableTarget.java",
                "NonNavigableTarget", "source");
        ControlledEndpointSupplier nonNavigableSupplier = new ControlledEndpointSupplier();
        nonNavigableSupplier.succeed(List.of(nonNavigableEndpoint));
        MqTopologyPanel nonNavigablePanel = createPanel(nonNavigableSupplier);
        refresh(nonNavigablePanel, nonNavigableSupplier);
        SimpleTree nonNavigableTree = onEdt(() -> findTree(nonNavigablePanel));
        DefaultMutableTreeNode nonNavigableNode = findNode(
                nonNavigableTree, "NonNavigableTarget#source — exchange=events, key=non-navigable");
        DefaultMutableTreeNode moduleNode = findNode(nonNavigableTree, "non-navigable (1)");
        assertNotNull(nonNavigableNode);
        assertNotNull(moduleNode);
        for (int iteration = 0; iteration < 10; iteration++) {
            doubleClick(nonNavigableTree, nonNavigableNode);
            doubleClick(nonNavigableTree, moduleNode);
            assertEquals(stableFile.getVirtualFile(),
                    FileEditorManager.getInstance(getProject()).getSelectedFiles()[0]);
        }
        LockSupport.parkNanos(200_000_000L);
        onEdt(() -> {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            return null;
        });
        assertEquals(stableFile.getVirtualFile(),
                FileEditorManager.getInstance(getProject()).getSelectedFiles()[0]);
    }

    @SuppressWarnings("deprecation")
    public void testDisposedProjectIgnoresDoubleClicksTenTimes() throws Exception {
        Path projectDirectory = Files.createTempDirectory("mqx-disposed-navigation");
        Project disposedProject = ProjectManagerEx.getInstanceEx().newProject(
                "MqDisposedNavigationProject", projectDirectory.toString(), true, false);
        assertNotNull(disposedProject);
        try {
            MqTopologyPanel disposedPanel = onEdt(() -> new MqTopologyPanel(disposedProject, List::of));
            SimpleTree disposedTree = onEdt(() -> findTree(disposedPanel));
            DefaultMutableTreeNode disposedNode = onEdt(() ->
                    (DefaultMutableTreeNode) disposedTree.getModel().getRoot());
            PlatformTestUtil.forceCloseProjectWithoutSaving(disposedProject);
            assertTrue(disposedProject.isDisposed());
            for (int iteration = 0; iteration < 10; iteration++) {
                doubleClick(disposedTree, disposedNode);
            }
        } finally {
            if (!disposedProject.isDisposed()) {
                PlatformTestUtil.forceCloseProjectWithoutSaving(disposedProject);
            }
            com.intellij.openapi.util.io.FileUtil.delete(projectDirectory.toFile());
        }
    }

    private MqTopologyPanel createPanel(ControlledEndpointSupplier endpointSupplier) {
        return onEdt(() -> new MqTopologyPanel(getProject(), endpointSupplier));
    }

    private MqEndpoint endpoint(
            String endpointId,
            String moduleName,
            MqEndpoint.Role role,
            String destinationName) {
        MqDestination destination = role == MqEndpoint.Role.PRODUCER
                ? MqDestination.rabbitProducer("events", destinationName)
                : MqDestination.rabbitConsumer("events", destinationName, List.of(), "topic");
        return new MqEndpoint(
                endpointId,
                MqEndpoint.SystemType.RABBITMQ,
                role,
                destination,
                MqEndpoint.ResolutionState.STATIC,
                null,
                sourcePointer,
                moduleName,
                "EndpointSource.java",
                "EndpointSource",
                endpointId);
    }

    private void setSearch(MqTopologyPanel panel, String query) {
        onEdt(() -> {
            findComponent(panel, JBTextField.class).setText(query);
            return null;
        });
    }

    private void setFilter(MqTopologyPanel panel, String filter) {
        onEdt(() -> {
            findComponent(panel, ComboBox.class).setSelectedItem(filter);
            return null;
        });
    }

    private String rootLabel(SimpleTree tree) {
        return onEdt(() -> renderedLabel(
                tree, (DefaultMutableTreeNode) tree.getModel().getRoot()));
    }

    private String tooltip(SimpleTree tree, DefaultMutableTreeNode node) {
        return onEdt(() -> {
            Component component = tree.getCellRenderer().getTreeCellRendererComponent(
                    tree, node, false, false, node.isLeaf(), 0, false);
            return ((JLabel) component).getToolTipText();
        });
    }

    private void assertNavigatesTo(SimpleTree tree, String endpointLabel, PsiFile targetFile) {
        long startedAt = System.nanoTime();
        DefaultMutableTreeNode endpointNode = findNode(tree, endpointLabel);
        assertNotNull(endpointNode);
        onEdt(() -> {
            FileEditorManager.getInstance(getProject()).closeFile(targetFile.getVirtualFile());
            return null;
        });
        doubleClick(tree, endpointNode);
        waitFor("source navigation", () -> onEdt(() ->
                List.of(FileEditorManager.getInstance(getProject()).getSelectedFiles())
                        .contains(targetFile.getVirtualFile())));
        assertTrue("navigation ns=" + (System.nanoTime() - startedAt),
                System.nanoTime() - startedAt < 1_000_000_000L);
    }

    private void doubleClick(SimpleTree tree, DefaultMutableTreeNode endpointNode) {
        onEdt(() -> {
            TreePath path = new TreePath(endpointNode.getPath());
            tree.setSize(900, 700);
            tree.scrollPathToVisible(path);
            Rectangle bounds = tree.getPathBounds(path);
            assertNotNull(bounds);
            MouseEvent event = new MouseEvent(tree, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                    bounds.x + 4, bounds.y + Math.max(bounds.height / 2, 1), 2, false, MouseEvent.BUTTON1);
            tree.dispatchEvent(event);
            return null;
        });
    }

    private List<MqEndpoint> moduleEndpoints(int moduleCount, boolean includeProjectGroup) {
        List<MqEndpoint> endpoints = new ArrayList<>();
        for (int index = 0; index < moduleCount; index++) {
            String moduleName = String.format("module-%02d", index);
            endpoints.add(endpoint("consumer-" + index, moduleName,
                    MqEndpoint.Role.CONSUMER, "queue-" + index));
            endpoints.add(endpoint("producer-" + index, moduleName,
                    MqEndpoint.Role.PRODUCER, "key-" + index));
            endpoints.add(endpoint("declaration-" + index, moduleName,
                    MqEndpoint.Role.DECLARATION, "declared-queue-" + index));
        }
        if (includeProjectGroup) {
            endpoints.add(endpoint("project-producer", "",
                    MqEndpoint.Role.PRODUCER, "project-key"));
        }
        return endpoints;
    }

    private void refresh(MqTopologyPanel panel, ControlledEndpointSupplier endpointSupplier)
            throws InterruptedException {
        onEdt(() -> {
            panel.refresh();
            return null;
        });
        endpointSupplier.awaitApplied();
        waitFor("refresh completion", () -> {
            String status = statusText(panel);
            return !"正在刷新 RabbitMQ 端点".equals(status) && !"等待项目索引完成".equals(status);
        });
    }

    private String statusText(MqTopologyPanel panel) {
        return onEdt(() -> {
            JBLabel statusLabel = findNamedComponent(panel, JBLabel.class, "mqTopologyStatus");
            return statusLabel == null ? null : statusLabel.getText();
        });
    }

    private JProgressBar refreshProgressBar(MqTopologyPanel panel) {
        JProgressBar progressBar = findNamedComponent(
                panel, JProgressBar.class, "mqTopologyRefreshProgress");
        assertNotNull("Refresh progress bar was not found", progressBar);
        return progressBar;
    }

    private void assertProgressActive(MqTopologyPanel panel) {
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            onEdt(() -> {
                assertProgressActive(panel);
                return null;
            });
            return;
        }
        JProgressBar progressBar = refreshProgressBar(panel);
        assertTrue(progressBar.isVisible());
        assertTrue(progressBar.isIndeterminate());
    }

    private void assertProgressInactive(MqTopologyPanel panel) {
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            onEdt(() -> {
                assertProgressInactive(panel);
                return null;
            });
            return;
        }
        JProgressBar progressBar = refreshProgressBar(panel);
        assertFalse(progressBar.isVisible());
        assertFalse(progressBar.isIndeterminate());
    }

    private long percentile(List<Long> sortedValues, int percentile) {
        int index = (int) Math.ceil(sortedValues.size() * percentile / 100.0) - 1;
        return sortedValues.get(Math.max(index, 0));
    }

    private void layoutRecursively(Container container) {
        container.doLayout();
        for (Component component : container.getComponents()) {
            if (component instanceof Container childContainer) {
                layoutRecursively(childContainer);
            }
        }
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

    private SimpleTree findTree(MqTopologyPanel panel) {
        return findComponent(panel, SimpleTree.class);
    }

    private DefaultMutableTreeNode findNode(SimpleTree tree, String expectedLabel) {
        return onEdt(() -> {
            DefaultMutableTreeNode root = (DefaultMutableTreeNode) tree.getModel().getRoot();
            Enumeration<?> nodes = root.depthFirstEnumeration();
            while (nodes.hasMoreElements()) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
                if (expectedLabel.equals(renderedLabel(tree, node))) {
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

    private List<String> childLabels(SimpleTree tree, DefaultMutableTreeNode parent) {
        return onEdt(() -> {
            List<String> labels = new ArrayList<>();
            for (int index = 0; index < parent.getChildCount(); index++) {
                labels.add(renderedLabel(tree, (DefaultMutableTreeNode) parent.getChildAt(index)));
            }
            return labels;
        });
    }

    private String nodeLabel(SimpleTree tree, DefaultMutableTreeNode node) {
        return onEdt(() -> renderedLabel(tree, node));
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

    private <T extends Component> T findNamedComponent(
            Container container, Class<T> componentType, String componentName) {
        for (Component component : container.getComponents()) {
            if (componentType.isInstance(component) && componentName.equals(component.getName())) {
                return componentType.cast(component);
            }
            if (component instanceof Container childContainer) {
                T child = findNamedComponent(childContainer, componentType, componentName);
                if (child != null) {
                    return child;
                }
            }
        }
        return null;
    }

    private <T> T onEdt(Supplier<T> supplier) {
        AtomicReference<T> result = new AtomicReference<>();
        ApplicationManager.getApplication().invokeAndWait(() -> result.set(supplier.get()));
        return result.get();
    }

    private static final class ControlledEndpointSupplier implements Supplier<List<MqEndpoint>> {

        private volatile List<MqEndpoint> endpoints = List.of();
        private volatile RuntimeException failure;
        private volatile CountDownLatch invocation = new CountDownLatch(1);
        private volatile CountDownLatch applied = new CountDownLatch(1);

        private void succeed(List<MqEndpoint> newEndpoints) {
            endpoints = List.copyOf(newEndpoints);
            failure = null;
            invocation = new CountDownLatch(1);
            applied = new CountDownLatch(1);
        }

        private void fail(RuntimeException newFailure) {
            failure = newFailure;
            invocation = new CountDownLatch(1);
            applied = new CountDownLatch(1);
        }

        private void awaitInvocation() throws InterruptedException {
            assertTrue("Endpoint supplier was not invoked", invocation.await(10, TimeUnit.SECONDS));
        }

        private void awaitApplied() throws InterruptedException {
            awaitInvocation();
            assertTrue("Endpoint results were not applied", applied.await(10, TimeUnit.SECONDS));
        }

        @Override
        public List<MqEndpoint> get() {
            invocation.countDown();
            if (failure != null) {
                throw failure;
            }
            return new ArrayList<>(endpoints) {
                @Override
                public Stream<MqEndpoint> stream() {
                    applied.countDown();
                    return super.stream();
                }
            };
        }
    }

    private static final class BlockingEndpointSupplier implements Supplier<List<MqEndpoint>> {

        private final List<PendingEndpointResult> results;
        private final AtomicInteger invocationIndex = new AtomicInteger();

        private BlockingEndpointSupplier(List<List<MqEndpoint>> endpointResults) {
            results = endpointResults.stream().map(PendingEndpointResult::new).toList();
        }

        private void awaitEntered(int index) throws InterruptedException {
            assertTrue("Refresh request " + index + " did not start",
                    results.get(index).entered.await(10, TimeUnit.SECONDS));
        }

        private void release(int index) {
            results.get(index).release.countDown();
        }

        private void awaitApplied(int index) throws InterruptedException {
            assertTrue("Refresh request " + index + " was not applied",
                    results.get(index).applied.await(10, TimeUnit.SECONDS));
        }

        @Override
        public List<MqEndpoint> get() {
            PendingEndpointResult result = results.get(invocationIndex.getAndIncrement());
            result.entered.countDown();
            try {
                if (!result.release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Refresh request was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Refresh request interrupted", exception);
            }
            return result.applyingEndpoints();
        }
    }

    private static final class PendingEndpointResult {

        private final List<MqEndpoint> endpoints;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch applied = new CountDownLatch(1);

        private PendingEndpointResult(List<MqEndpoint> endpoints) {
            this.endpoints = List.copyOf(endpoints);
        }

        private List<MqEndpoint> applyingEndpoints() {
            return new ArrayList<>(endpoints) {
                @Override
                public Stream<MqEndpoint> stream() {
                    applied.countDown();
                    return super.stream();
                }
            };
        }
    }

    private record FilterExpectation(
            String filter,
            String moduleLabel,
            String categoryLabel,
            String childCategoryLabel) {
    }
}
