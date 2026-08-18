package cn.luckyh.mqx.ui;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiElement;
import com.intellij.util.OpenSourceUtil;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.ScrollPaneFactory;
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

import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.event.DocumentEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

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
    private final SimpleTree tree = new SimpleTree();
    private final JBTextField searchField = new JBTextField();
    private final JComboBox<String> endpointFilter = new JComboBox<>(new String[]{
            FILTER_ALL, FILTER_CONSUMERS, FILTER_PRODUCERS, FILTER_TOPOLOGY, FILTER_UNRESOLVED
    });
    private final MqEndpointMatcher matcher = new MqEndpointMatcher();
    private List<MqEndpoint> allEndpoints = List.of();

    public MqTopologyPanel(Project project) {
        super(true, true);
        this.project = project;
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
        setContent(content);
    }

    public void refresh() {
        if (project.isDisposed()) {
            return;
        }
        ReadAction.nonBlocking(() -> MqNavigationService.getInstance(project).currentEndpoints())
                .inSmartMode(project)
                .expireWith(project)
                .finishOnUiThread(ModalityState.defaultModalityState(), this::replaceTree)
                .submit(AppExecutorUtil.getAppExecutorService());
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
        allEndpoints = endpoints.stream()
                .filter(endpoint -> endpoint.system() == MqEndpoint.SystemType.RABBITMQ)
                .sorted(ENDPOINT_ORDER)
                .toList();
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
        tree.setModel(new DefaultTreeModel(root));
        tree.setCellRenderer(new TopologyCellRenderer());
        tree.expandRow(0);
        for (int index = 0; index < root.getChildCount(); index++) {
            DefaultMutableTreeNode moduleNode = (DefaultMutableTreeNode) root.getChildAt(index);
            tree.expandPath(new TreePath(moduleNode.getPath()));
        }
        if (!query.isEmpty() && visibleEndpoints.size() <= 200) {
            for (int row = 0; row < tree.getRowCount(); row++) {
                tree.expandRow(row);
            }
        }
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
                    new TopologyItem(entry.getKey() + " (" + moduleEndpoints.size() + ")", null));
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
        ReadAction.run(() -> {
            PsiElement element = item.endpoint().sourcePointer().getElement();
            if (element != null && element.isValid()) {
                PsiElement navigationElement = element.getNavigationElement();
                if (navigationElement instanceof Navigatable navigatable
                        && navigationElement.isValid()
                        && navigatable.canNavigate()) {
                    OpenSourceUtil.navigate(navigatable);
                }
            }
        });
    }

    private record TopologyItem(String label, MqEndpoint endpoint) {
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
                } else if (item.endpoint().resolutionMessage() != null) {
                    setToolTipText(item.endpoint().resolutionMessage());
                } else {
                    setToolTipText(item.endpoint().destination().summary());
                }
            }
            return this;
        }
    }
}
