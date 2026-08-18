package cn.luckyh.mqx.action;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.IconLoader;
import cn.luckyh.mqx.navigation.MqNavigationService;
import cn.luckyh.mqx.ui.MqToolWindowService;
import org.jetbrains.annotations.NotNull;

public final class RefreshMqIndexAction extends AnAction {

    public RefreshMqIndexAction() {
        // 逻辑变动: 工具栏使用仓库中可加载的RabbitMQ SVG图标-20260818-2003-01
        super("Refresh RabbitMQ topology", "Rescan MQ endpoints",
                IconLoader.getIcon("/icons/rabbitmq-seeklogo.svg", RefreshMqIndexAction.class));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        if (project == null || project.isDisposed()) {
            return;
        }

        // 逻辑变动: 用户主动失效当前项目MQ快照并重启代码分析-20260817-1826-01
        MqNavigationService.getInstance(project).requestRefresh();
        // 逻辑变动: 主动扫描后同步刷新RabbitMQ拓扑侧边栏-20260818-1700-01
        MqToolWindowService.getInstance(project).refresh();
        DaemonCodeAnalyzer.getInstance(project).restart();
    }
}
