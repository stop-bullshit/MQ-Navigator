package cn.luckyh.mqx.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import org.jetbrains.annotations.NotNull;

/**
 * MQ Navigator 工具窗口工厂.
 *
 * @author luckyh
 * @since 0.2.0
 */
public final class MqToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        MqToolWindowService.getInstance(project).initToolWindow(toolWindow);
    }
}
