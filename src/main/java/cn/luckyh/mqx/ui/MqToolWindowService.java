package cn.luckyh.mqx.ui;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;

/**
 * MQ Navigator 工具窗口生命周期服务.
 *
 * @author luckyh
 * @since 0.2.0
 */
@Service(Service.Level.PROJECT)
public final class MqToolWindowService {

    private final Project project;
    private MqTopologyPanel panel;

    public MqToolWindowService(Project project) {
        this.project = project;
    }

    public static MqToolWindowService getInstance(Project project) {
        return project.getService(MqToolWindowService.class);
    }

    public void initToolWindow(ToolWindow toolWindow) {
        if (panel != null) {
            panel.refresh();
            return;
        }
        panel = new MqTopologyPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);
        panel.refresh();
    }

    public void refresh() {
        if (panel != null) {
            panel.refresh();
        }
    }
}
