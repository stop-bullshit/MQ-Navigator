package cn.luckyh.mqx.navigation;

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.GutterName;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor;
import com.intellij.codeInsight.navigation.NavigationUtil;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.SmartPointerManager;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.ui.awt.RelativePoint;
import cn.luckyh.mqx.model.MqEndpoint;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.util.List;

public final class MqLineMarkerProvider extends LineMarkerProviderDescriptor {

    private static final Icon PRODUCER_ICON = IconLoader.getIcon(
            "/icons/gutter-producer.svg", MqLineMarkerProvider.class);
    private static final Icon CONSUMER_ICON = IconLoader.getIcon(
            "/icons/gutter-consumer.svg", MqLineMarkerProvider.class);

    @Override
    public LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        if (!(element instanceof PsiIdentifier) || DumbService.isDumb(element.getProject())) {
            return null;
        }
        MqNavigationService service = MqNavigationService.getInstance(element.getProject());
        var endpoint = service.parseAnchor(element);
        if (endpoint.isEmpty()) {
            return null;
        }

        MqEndpoint source = endpoint.get();
        // 逻辑变动: 队列声明保留在拓扑侧栏且不显示收发端跳转图标-20261023-1204-01
        if (source.role() == MqEndpoint.Role.DECLARATION) {
            return null;
        }
        Project project = element.getProject();
        var pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element);
        GutterIconNavigationHandler<PsiElement> handler = (event, ignored) -> {
            RelativePoint popupPoint = event == null ? null : new RelativePoint(event);
            // 逻辑变动: marker点击时等待文档提交并查询当前快照-20260817-1826-01
            ReadAction.nonBlocking(() -> service.findTargets(pointer.getElement()))
                    .withDocumentsCommitted(project)
                    .inSmartMode(project)
                    .expireWith(project)
                    .finishOnUiThread(ModalityState.defaultModalityState(),
                            outcome -> presentOutcome(project, outcome, popupPoint))
                    .submit(AppExecutorUtil.getAppExecutorService());
        };
        Icon icon = source.role() == MqEndpoint.Role.PRODUCER ? PRODUCER_ICON : CONSUMER_ICON;
        String tooltip = switch (source.role()) {
            case PRODUCER -> "Find MQ consumers";
            case CONSUMER -> "Find MQ producers";
            case DECLARATION -> "Find MQ queue usages";
            case BINDING -> "Open MQ binding";
            case EXCHANGE_DECLARATION -> "Open MQ exchange declaration";
        };
        return new LineMarkerInfo<>(element, element.getTextRange(), icon, ignored -> tooltip,
                handler, GutterIconRenderer.Alignment.RIGHT, () -> tooltip);
    }

    private void presentOutcome(
            Project project,
            MqNavigationService.NavigationOutcome outcome,
            RelativePoint popupPoint) {
        if (outcome.status() == MqNavigationService.NavigationStatus.ONE_MATCH) {
            boolean navigated = ReadAction.compute(() -> {
                PsiElement target = outcome.targets().get(0).sourcePointer().getElement();
                PsiElement navigationElement = target == null ? null : target.getNavigationElement();
                if (navigationElement instanceof Navigatable navigatable && navigatable.canNavigate()) {
                    navigatable.navigate(true);
                    return true;
                }
                return false;
            });
            if (navigated) {
                return;
            }
        }
        if (outcome.status() == MqNavigationService.NavigationStatus.MULTIPLE_MATCHES) {
            List<PsiElement> targets = ReadAction.compute(() -> outcome.targets().stream()
                    .map(endpoint -> endpoint.sourcePointer().getElement())
                    .filter(java.util.Objects::nonNull)
                    .toList());
            if (targets.isEmpty()) {
                notify(project, "MQ targets are no longer valid", NotificationType.WARNING);
                return;
            }
            MqEndpoint.Role targetRole = outcome.targets().get(0).role();
            boolean sameRole = outcome.targets().stream().allMatch(target -> target.role() == targetRole);
            String targetName = sameRole ? switch (targetRole) {
                case CONSUMER -> "consumer";
                case PRODUCER -> "producer";
                default -> "target";
            } : "target";
            var popup = NavigationUtil.getPsiElementPopup(
                    () -> targets,
                    new MqTargetRenderer(outcome.targets()),
                    "Choose MQ " + targetName + " (" + targets.size() + ")",
                    project);
            if (popupPoint != null) {
                popup.show(popupPoint);
            } else {
                popup.showInFocusCenter();
            }
            return;
        }
        notify(project, outcome.message() == null ? "MQ target was not found" : outcome.message(),
                outcome.status() == MqNavigationService.NavigationStatus.NO_MATCH
                        ? NotificationType.INFORMATION : NotificationType.WARNING);
    }

    private void notify(Project project, String message, NotificationType type) {
        NotificationGroupManager.getInstance()
                .getNotificationGroup("MQ Navigator")
                .createNotification(message, type)
                .notify(project);
    }

    @Override
    public @Nullable @GutterName String getName() {
        return "MQ Navigator";
    }
}
