package cn.luckyh.mqx.ui;

import com.intellij.ide.util.PsiNavigationSupport;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiElement;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.treeStructure.SimpleTree;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import cn.luckyh.mqx.action.RefreshMqIndexAction;
import cn.luckyh.mqx.match.MqEndpointMatcher;
import cn.luckyh.mqx.model.MqDestination;
import cn.luckyh.mqx.model.MqEndpoint;
import cn.luckyh.mqx.navigation.MqNavigationService;
import org.jetbrains.annotations.NotNull;

import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTree;
import javax.swing.event.DocumentEvent;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * RabbitMQ 拓扑树面板.
 *
 * @author luckyh
 * @since 0.2.0
 */
public final class MqTopologyPanel extends SimpleToolWindowPanel {

    private static final String FILTER_ALL = "All";
    private static final String FILTER_CONSUMERS = "Consumers";
    private static final String FILTER_PRODUCERS = "Producers";
    private static final String FILTER_TOPOLOGY = "Topology";
    private static final String FILTER_UNRESOLVED = "Unresolved";
    private static final Comparator<MqEndpoint> ENDPOINT_ORDER = Comparator
            .comparing(MqEndpoint::moduleName)
            .thenComparing(MqEndpoint::fileName)
            .thenComparing(MqEndpoint::className)
            .thenComparing(MqEndpoint::memberName)
            .thenComparing(MqEndpoint::endpointId);

    private final Project project;
    private final Supplier<List<MqEndpoint>> endpointSupplier;
    private final SimpleTree tree = new SimpleTree();
    private final JBTextField searchField = new JBTextField();
    private final JBLabel statusLabel = new JBLabel();
    private final JProgressBar refreshProgressBar = new JProgressBar();
    private final ComboBox<String> endpointFilter = new ComboBox<>(new String[]{
            FILTER_ALL, FILTER_CONSUMERS, FILTER_PRODUCERS, FILTER_TOPOLOGY, FILTER_UNRESOLVED
    });
    private final MqEndpointMatcher matcher = new MqEndpointMatcher();
    private final Set<String> expandedModuleNames = new LinkedHashSet<>();
    private List<MqEndpoint> allEndpoints = List.of();
    private boolean applyingTreeState;
    private boolean refreshing;
    private long refreshRequestId;

    public MqTopologyPanel(Project project) {
        this(project, () -> MqNavigationService.getInstance(project).currentEndpoints());
    }

    MqTopologyPanel(Project project, Supplier<List<MqEndpoint>> endpointSupplier) {
        super(true, true);
        this.project = project;
        this.endpointSupplier = endpointSupplier;
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        // 逻辑变动: 双击端点时禁止JTree先切换非叶子节点展开状态-20260818-1830-05
        tree.setToggleClickCount(0);
        tree.setCellRenderer(new TopologyCellRenderer());
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && event.getButton() == MouseEvent.BUTTON1) {
                    openSelectedSource(event);
                }
            }
        });
        tree.addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent event) {
                updateExpandedModuleState(event, true);
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent event) {
                updateExpandedModuleState(event, false);
            }
        });

        searchField.getEmptyText().setText("Filter module, class, queue, exchange or routing key");
        searchField.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent event) {
                rebuildTree();
            }
        });
        endpointFilter.addActionListener(event -> rebuildTree());

        DefaultMutableTreeNode root = new DefaultMutableTreeNode(new TopologyItem("RabbitMQ", null));
        tree.setModel(new DefaultTreeModel(root));
        setToolbar(createToolbar().getComponent());

        JPanel filterPanel = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        filterPanel.setBorder(JBUI.Borders.empty(4));
        filterPanel.add(searchField, BorderLayout.CENTER);
        filterPanel.add(endpointFilter, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout());
        content.add(filterPanel, BorderLayout.NORTH);
        content.add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER);
        statusLabel.setName("mqTopologyStatus");
        refreshProgressBar.setName("mqTopologyRefreshProgress");
        refreshProgressBar.setStringPainted(false);
        setRefreshProgress(false);
        Dimension progressSize = refreshProgressBar.getPreferredSize();
        Dimension progressSlotSize = new Dimension(
                progressSize.width,
                Math.max(progressSize.height, statusLabel.getFontMetrics(statusLabel.getFont()).getHeight()));
        JPanel progressSlot = new JPanel(new BorderLayout());
        progressSlot.setOpaque(false);
        progressSlot.setPreferredSize(progressSlotSize);
        progressSlot.setMinimumSize(progressSlotSize);
        progressSlot.add(refreshProgressBar, BorderLayout.CENTER);
        JPanel statusPanel = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        statusPanel.setBorder(JBUI.Borders.empty(2, 4, 4, 4));
        statusPanel.add(statusLabel, BorderLayout.CENTER);
        statusPanel.add(progressSlot, BorderLayout.EAST);
        content.add(statusPanel, BorderLayout.SOUTH);
        setContent(content);
    }

    public void refresh() {
        if (project.isDisposed()) {
            return;
        }
        long requestId = ++refreshRequestId;
        refreshing = true;
        boolean waitingForIndex = DumbService.isDumb(project);
        statusLabel.setText(waitingForIndex ? "等待项目索引完成" : "正在刷新 RabbitMQ 端点");
        setRefreshProgress(!waitingForIndex);
        ReadAction.nonBlocking(() -> {
                    try {
                        List<MqEndpoint> endpoints = endpointSupplier.get().stream()
                                .filter(endpoint -> endpoint.system() == MqEndpoint.SystemType.RABBITMQ)
                                .sorted(ENDPOINT_ORDER)
                                .toList();
                        return new RefreshResult(endpoints, null);
                    } catch (RuntimeException exception) {
                        return new RefreshResult(List.of(), exception);
                    }
                })
                .inSmartMode(project)
                .expireWith(project)
                .finishOnUiThread(ModalityState.defaultModalityState(), result -> {
                    if (project.isDisposed() || requestId != refreshRequestId) {
                        return;
                    }
                    if (result.failure() != null) {
                        refreshing = false;
                        setRefreshProgress(false);
                        statusLabel.setText("刷新失败，已保留上次结果");
                        return;
                    }
                    refreshing = false;
                    setRefreshProgress(false);
                    replaceTree(result.endpoints());
                })
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    private void setRefreshProgress(boolean active) {
        refreshProgressBar.setIndeterminate(active);
        refreshProgressBar.setVisible(active);
    }

    private ActionToolbar createToolbar() {
        DefaultActionGroup group = new DefaultActionGroup();
        group.add(new RefreshMqIndexAction());
        ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar("MQ Navigator Toolbar", group, true);
        toolbar.setTargetComponent(tree);
        return toolbar;
    }

    private void replaceTree(List<MqEndpoint> endpoints) {
        if (project.isDisposed()) {
            return;
        }
        allEndpoints = endpoints;
        rebuildTree();
    }

    private void rebuildTree() {
        if (project.isDisposed()) {
            return;
        }
        String query = searchField.getText().trim().toLowerCase(Locale.ROOT);
        String selectedFilter = Objects.toString(endpointFilter.getSelectedItem(), FILTER_ALL);
        List<MqEndpoint> visibleEndpoints = allEndpoints.stream()
                .filter(endpoint -> matchesSelectedFilter(endpoint, selectedFilter))
                .filter(endpoint -> matchesSearch(endpoint, query))
                .toList();

        String count = visibleEndpoints.size() == allEndpoints.size()
                ? String.valueOf(allEndpoints.size())
                : visibleEndpoints.size() + "/" + allEndpoints.size();
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(
                new TopologyItem("RabbitMQ (" + count + ")", null));
        addModuleNodes(root, visibleEndpoints);
        boolean filterActive = isFilterActive();
        applyingTreeState = true;
        try {
            tree.setModel(new DefaultTreeModel(root));
            tree.setCellRenderer(new TopologyCellRenderer());
            tree.expandRow(0);
            Set<String> currentModuleNames = new LinkedHashSet<>();
            for (int index = 0; index < root.getChildCount(); index++) {
                DefaultMutableTreeNode moduleNode = (DefaultMutableTreeNode) root.getChildAt(index);
                TopologyItem item = (TopologyItem) moduleNode.getUserObject();
                currentModuleNames.add(item.moduleName());
                if (!filterActive && expandedModuleNames.contains(item.moduleName())) {
                    tree.expandPath(new TreePath(moduleNode.getPath()));
                }
                if (filterActive) {
                    expandFilterCategories(moduleNode);
                }
            }
            if (!filterActive) {
                expandedModuleNames.retainAll(currentModuleNames);
            }
        } finally {
            applyingTreeState = false;
        }
        if (refreshing) {
            return;
        }
        if (allEndpoints.isEmpty()) {
            statusLabel.setText("未发现 RabbitMQ 端点");
            return;
        }
        if (filterActive && visibleEndpoints.isEmpty()) {
            statusLabel.setText("当前条件无匹配结果");
            return;
        }
        statusLabel.setText("");
    }

    private void expandFilterCategories(DefaultMutableTreeNode node) {
        TopologyItem item = (TopologyItem) node.getUserObject();
        if (item.endpoint() != null) {
            return;
        }
        tree.expandPath(new TreePath(node.getPath()));
        for (int index = 0; index < node.getChildCount(); index++) {
            expandFilterCategories((DefaultMutableTreeNode) node.getChildAt(index));
        }
    }

    private void updateExpandedModuleState(TreeExpansionEvent event, boolean expanded) {
        if (applyingTreeState || isFilterActive()) {
            return;
        }
        Object selected = event.getPath().getLastPathComponent();
        if (!(selected instanceof DefaultMutableTreeNode node)
                || !(node.getUserObject() instanceof TopologyItem item)
                || item.moduleName() == null) {
            return;
        }
        if (expanded) {
            expandedModuleNames.add(item.moduleName());
        } else {
            expandedModuleNames.remove(item.moduleName());
        }
    }

    private boolean isFilterActive() {
        return !searchField.getText().trim().isEmpty()
                || !FILTER_ALL.equals(Objects.toString(endpointFilter.getSelectedItem(), FILTER_ALL));
    }

    private boolean matchesSelectedFilter(MqEndpoint endpoint, String selectedFilter) {
        return switch (selectedFilter) {
            case FILTER_CONSUMERS -> endpoint.role() == MqEndpoint.Role.CONSUMER;
            case FILTER_PRODUCERS -> endpoint.role() == MqEndpoint.Role.PRODUCER;
            case FILTER_TOPOLOGY -> endpoint.role() == MqEndpoint.Role.DECLARATION
                    || endpoint.role() == MqEndpoint.Role.BINDING
                    || endpoint.role() == MqEndpoint.Role.EXCHANGE_DECLARATION;
            case FILTER_UNRESOLVED -> !endpoint.isResolved();
            default -> true;
        };
    }

    private boolean matchesSearch(MqEndpoint endpoint, String query) {
        if (query.isEmpty()) {
            return true;
        }
        String searchable = String.join(" ",
                endpoint.moduleName(),
                endpoint.fileName(),
                endpoint.className(),
                endpoint.memberName(),
                endpoint.role().name(),
                endpoint.destination().summary(),
                Objects.toString(endpoint.resolutionMessage(), ""));
        return searchable.toLowerCase(Locale.ROOT).contains(query);
    }

    private void addModuleNodes(DefaultMutableTreeNode root, List<MqEndpoint> endpoints) {
        // 逻辑变动: 侧边栏按模块和收发端分类并支持文本与类型筛选-20260818-1830-02
        Map<String, List<MqEndpoint>> endpointsByModule = new TreeMap<>();
        for (MqEndpoint endpoint : endpoints) {
            String moduleName = endpoint.moduleName().isBlank() ? "(project)" : endpoint.moduleName();
            endpointsByModule.computeIfAbsent(moduleName, ignored -> new ArrayList<>()).add(endpoint);
        }

        for (Map.Entry<String, List<MqEndpoint>> entry : endpointsByModule.entrySet()) {
            List<MqEndpoint> moduleEndpoints = entry.getValue();
            DefaultMutableTreeNode moduleNode = new DefaultMutableTreeNode(
                    new TopologyItem(entry.getKey() + " (" + moduleEndpoints.size() + ")", null, entry.getKey()));
            root.add(moduleNode);

            addEndpointCategory(moduleNode, "Consumers", moduleEndpoints.stream()
                    .filter(endpoint -> endpoint.role() == MqEndpoint.Role.CONSUMER)
                    .toList());
            addEndpointCategory(moduleNode, "Producers", moduleEndpoints.stream()
                    .filter(endpoint -> endpoint.role() == MqEndpoint.Role.PRODUCER)
                    .toList());

            List<MqEndpoint> topologyEndpoints = moduleEndpoints.stream()
                    .filter(endpoint -> endpoint.role() == MqEndpoint.Role.DECLARATION
                            || endpoint.role() == MqEndpoint.Role.BINDING
                            || endpoint.role() == MqEndpoint.Role.EXCHANGE_DECLARATION)
                    .toList();
            if (!topologyEndpoints.isEmpty()) {
                DefaultMutableTreeNode topologyNode = new DefaultMutableTreeNode(
                        new TopologyItem("Topology (" + topologyEndpoints.size() + ")", null));
                moduleNode.add(topologyNode);
                addEndpointCategory(topologyNode, "Queues", topologyEndpoints.stream()
                        .filter(endpoint -> endpoint.role() == MqEndpoint.Role.DECLARATION)
                        .toList());
                addEndpointCategory(topologyNode, "Bindings", topologyEndpoints.stream()
                        .filter(endpoint -> endpoint.role() == MqEndpoint.Role.BINDING)
                        .toList());
                addEndpointCategory(topologyNode, "Exchanges", topologyEndpoints.stream()
                        .filter(endpoint -> endpoint.role() == MqEndpoint.Role.EXCHANGE_DECLARATION)
                        .toList());
            }
        }
    }

    private void addEndpointCategory(
            DefaultMutableTreeNode parent,
            String category,
            List<MqEndpoint> endpoints) {
        if (endpoints.isEmpty()) {
            return;
        }
        DefaultMutableTreeNode categoryNode = new DefaultMutableTreeNode(
                new TopologyItem(category + " (" + endpoints.size() + ")", null));
        parent.add(categoryNode);
        for (MqEndpoint endpoint : endpoints) {
            DefaultMutableTreeNode endpointNode = new DefaultMutableTreeNode(
                    new TopologyItem(sourceLabel(endpoint, false), endpoint));
            categoryNode.add(endpointNode);
            addEndpointRelations(endpointNode, endpoint);
        }
    }

    private void addEndpointRelations(DefaultMutableTreeNode endpointNode, MqEndpoint endpoint) {
        if (endpoint.role() == MqEndpoint.Role.DECLARATION) {
            addDeadLetterRelations(endpointNode, endpoint);
            return;
        }
        if (endpoint.role() == MqEndpoint.Role.CONSUMER && endpoint.isResolved()) {
            addRelationCategory(endpointNode, "Bindings", allEndpoints.stream()
                    .filter(candidate -> candidate.role() == MqEndpoint.Role.BINDING)
                    .filter(candidate -> candidate.isResolved() && sameTopology(candidate, endpoint))
                    .toList());
            addRelationCategory(endpointNode, "Related producers", allEndpoints.stream()
                    .filter(candidate -> candidate.role() == MqEndpoint.Role.PRODUCER)
                    .filter(MqEndpoint::isResolved)
                    .filter(candidate -> matcher.match(
                            MqEndpoint.SystemType.RABBITMQ,
                            candidate.destination(),
                            endpoint.destination()) == MqEndpointMatcher.MatchKind.EXACT)
                    .toList());
            return;
        }
        if (endpoint.role() == MqEndpoint.Role.PRODUCER && endpoint.isResolved()) {
            addRelationCategory(endpointNode, "Related consumers", allEndpoints.stream()
                    .filter(candidate -> candidate.role() == MqEndpoint.Role.CONSUMER)
                    .filter(MqEndpoint::isResolved)
                    .filter(candidate -> matcher.match(
                            MqEndpoint.SystemType.RABBITMQ,
                            endpoint.destination(),
                            candidate.destination()) == MqEndpointMatcher.MatchKind.EXACT)
                    .toList());
        }
    }

    private void addDeadLetterRelations(DefaultMutableTreeNode endpointNode, MqEndpoint endpoint) {
        MqDestination.RabbitQueueSettings settings = endpoint.destination().rabbitQueueSettings();
        if (settings == null || !settings.deadLetterExchangeConfigured()) {
            return;
        }
        if (settings.deadLetterExchange() == null) {
            addUnresolvedDeadLetterTarget(endpointNode, "dead-letter exchange is not statically resolved");
            return;
        }

        String deadLetterExchange = settings.deadLetterExchange();
        List<MqEndpoint> exchanges = new ArrayList<>();
        for (MqEndpoint candidate : allEndpoints) {
            if (candidate.role() == MqEndpoint.Role.EXCHANGE_DECLARATION
                    && candidate.isResolved()
                    && Objects.equals(deadLetterExchange, candidate.destination().exchange())) {
                exchanges.add(candidate);
            }
        }
        addRelationCategory(endpointNode, "Dead-letter exchanges", exchanges);

        List<MqEndpoint> bindings = new ArrayList<>();
        if (!deadLetterExchange.isEmpty()) {
            for (MqEndpoint candidate : allEndpoints) {
                if (candidate.role() != MqEndpoint.Role.BINDING || !candidate.isResolved()
                        || !Objects.equals(deadLetterExchange, candidate.destination().exchange())) {
                    continue;
                }
                if (!settings.deadLetterRoutingKeyConfigured()) {
                    if ("fanout".equals(candidate.destination().exchangeType())) {
                        bindings.add(candidate);
                    }
                    continue;
                }
                if (settings.deadLetterRoutingKey() != null
                        && matcher.match(
                        MqEndpoint.SystemType.RABBITMQ,
                        MqDestination.rabbitProducer(deadLetterExchange, settings.deadLetterRoutingKey()),
                        candidate.destination()) == MqEndpointMatcher.MatchKind.EXACT) {
                    bindings.add(candidate);
                }
            }
        }
        addRelationCategory(endpointNode, "Dead-letter bindings", bindings);

        Set<String> targetQueueNames = new LinkedHashSet<>();
        for (MqEndpoint binding : bindings) {
            if (binding.destination().queue() != null && !binding.destination().queue().isEmpty()) {
                targetQueueNames.add(binding.destination().queue());
            }
        }
        if (deadLetterExchange.isEmpty()
                && settings.deadLetterRoutingKeyConfigured()
                && settings.deadLetterRoutingKey() != null) {
            targetQueueNames.add(settings.deadLetterRoutingKey());
        }

        Map<String, MqEndpoint> targetQueues = new LinkedHashMap<>();
        for (MqEndpoint candidate : allEndpoints) {
            if (candidate.role() != MqEndpoint.Role.DECLARATION || !candidate.isResolved()
                    || !targetQueueNames.contains(candidate.destination().queue())) {
                continue;
            }
            String sourceKey = candidate.moduleName() + '\n'
                    + candidate.fileName() + '\n'
                    + candidate.className() + '\n'
                    + candidate.memberName() + '\n'
                    + candidate.destination().queue();
            targetQueues.putIfAbsent(sourceKey, candidate);
        }
        addRelationCategory(endpointNode, "Dead-letter queues", new ArrayList<>(targetQueues.values()));
        if (targetQueues.isEmpty()) {
            String reason;
            if (settings.deadLetterRoutingKeyConfigured() && settings.deadLetterRoutingKey() == null) {
                reason = "dead-letter routing key is not statically resolved";
            } else if (!settings.deadLetterRoutingKeyConfigured()) {
                reason = "original routing key does not determine a target in the current project";
            } else {
                reason = "no matching binding or queue was found in the current project";
            }
            addUnresolvedDeadLetterTarget(endpointNode, reason);
        }
    }

    private void addUnresolvedDeadLetterTarget(DefaultMutableTreeNode endpointNode, String reason) {
        endpointNode.add(new DefaultMutableTreeNode(
                new TopologyItem("Dead-letter target unresolved — " + reason, null)));
    }

    private void addRelationCategory(
            DefaultMutableTreeNode parent,
            String category,
            List<MqEndpoint> endpoints) {
        if (endpoints.isEmpty()) {
            return;
        }
        DefaultMutableTreeNode categoryNode = new DefaultMutableTreeNode(
                new TopologyItem(category + " (" + endpoints.size() + ")", null));
        parent.add(categoryNode);
        for (MqEndpoint endpoint : endpoints) {
            categoryNode.add(new DefaultMutableTreeNode(
                    new TopologyItem(sourceLabel(endpoint, true), endpoint)));
        }
    }

    private boolean sameTopology(MqEndpoint first, MqEndpoint second) {
        MqDestination left = first.destination();
        MqDestination right = second.destination();
        return Objects.equals(left.exchange(), right.exchange())
                && Objects.equals(left.queue(), right.queue())
                && Objects.equals(left.exchangeType(), right.exchangeType())
                && Objects.equals(left.bindingKeys(), right.bindingKeys());
    }

    private String sourceLabel(MqEndpoint endpoint, boolean includeModule) {
        String owner = endpoint.className().isBlank() ? endpoint.fileName() : endpoint.className();
        String member = endpoint.memberName().isBlank() ? "" : "#" + endpoint.memberName();
        String module = includeModule && !endpoint.moduleName().isBlank()
                ? endpoint.moduleName() + "/" : "";
        String state = endpoint.isResolved() ? "" : " [dynamic]";
        return module + owner + member + " — " + endpoint.destination().summary() + state;
    }

    private void openSelectedSource(MouseEvent event) {
        if (project.isDisposed()) {
            return;
        }
        // 逻辑变动: 直接使用双击坐标解析目标节点，避免首次双击读取旧选中项-20260818-1830-05
        TreePath path = tree.getPathForLocation(event.getX(), event.getY());
        if (path == null) {
            return;
        }
        tree.setSelectionPath(path);
        Object selected = path.getLastPathComponent();
        if (!(selected instanceof DefaultMutableTreeNode node)
                || !(node.getUserObject() instanceof TopologyItem item)
                || item.endpoint() == null) {
            return;
        }
        // 逻辑变动: 使用IDE源码导航入口一次定位到侧边栏端点源码-20260818-1830-05
        ReadAction.<Navigatable>nonBlocking(() -> {
                    try {
                        PsiElement element = item.endpoint().sourcePointer().getElement();
                        if (element == null || !element.isValid()) {
                            return null;
                        }
                        PsiElement navigationElement = element.getNavigationElement();
                        PsiNavigationSupport navigationSupport = PsiNavigationSupport.getInstance();
                        if (!navigationElement.isValid()
                                || !navigationSupport.canNavigate(navigationElement)) {
                            return null;
                        }
                        return navigationSupport.getDescriptor(navigationElement);
                    } catch (com.intellij.psi.PsiInvalidElementAccessException exception) {
                        return null;
                    }
                })
                .expireWith(project)
                .finishOnUiThread(ModalityState.defaultModalityState(), navigatable -> {
                    if (!project.isDisposed() && navigatable != null && navigatable.canNavigate()) {
                        navigatable.navigate(true);
                    }
                })
                .submit(AppExecutorUtil.getAppExecutorService());
    }

    private record TopologyItem(String label, MqEndpoint endpoint, String moduleName) {

        private TopologyItem(String label, MqEndpoint endpoint) {
            this(label, endpoint, null);
        }
    }

    private record RefreshResult(List<MqEndpoint> endpoints, RuntimeException failure) {
    }

    private static final class TopologyCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public java.awt.Component getTreeCellRendererComponent(
                JTree tree,
                Object value,
                boolean selected,
                boolean expanded,
                boolean leaf,
                int row,
                boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            if (value instanceof DefaultMutableTreeNode node
                    && node.getUserObject() instanceof TopologyItem item) {
                setText(item.label());
                if (item.endpoint() == null) {
                    setToolTipText(item.label());
                } else {
                    List<String> messages = new ArrayList<>();
                    if (item.endpoint().resolutionMessage() != null) {
                        messages.add(item.endpoint().resolutionMessage());
                    }
                    MqDestination.RabbitQueueSettings settings =
                            item.endpoint().destination().rabbitQueueSettings();
                    if (settings != null) {
                        messages.addAll(settings.diagnostics());
                    }
                    setToolTipText(messages.isEmpty()
                            ? item.endpoint().destination().summary() : String.join("; ", messages));
                }
            }
            return this;
        }
    }
}
