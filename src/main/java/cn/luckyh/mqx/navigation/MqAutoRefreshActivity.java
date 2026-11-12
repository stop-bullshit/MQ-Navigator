package cn.luckyh.mqx.navigation;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootEvent;
import com.intellij.openapi.roots.ModuleRootListener;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.update.MergingUpdateQueue;
import com.intellij.util.ui.update.Update;
import cn.luckyh.mqx.ui.MqToolWindowService;
import org.jetbrains.annotations.NotNull;

/**
 * MQ端点随项目索引和源码变更自动刷新。
 *
 * @author heng.wang
 * @since 2026-11-12
 */
public final class MqAutoRefreshActivity implements StartupActivity.DumbAware {

    private static final int REFRESH_DELAY_MILLIS = 500;

    @Override
    public void runActivity(@NotNull Project project) {
        // 自动刷新监听随项目服务释放，插件卸载后不再保留任务
        start(project, MqNavigationService.getInstance(project));
    }

    static void start(Project project, Disposable parentDisposable) {
        // 连续源码变更合并为一次后台端点扫描
        MergingUpdateQueue queue = new MergingUpdateQueue(
                "MQ endpoint refresh", REFRESH_DELAY_MILLIS, true, null, parentDisposable);
        queue.setRestartTimerOnAdd(true);
        Runnable scheduleRefresh = () -> queue.queue(Update.create("endpoints", () ->
                ReadAction.nonBlocking(() -> {
                            MqNavigationService service = MqNavigationService.getInstance(project);
                            // 旧持久化快照和当前端点统一从已提交源码重建
                            service.requestRefresh();
                            return service.currentEndpoints();
                        })
                        .withDocumentsCommitted(project)
                        .inSmartMode(project)
                        .coalesceBy(queue)
                        .expireWith(parentDisposable)
                        .finishOnUiThread(ModalityState.nonModal(), ignored -> {
                            // 当前拓扑和跨文件跳转图标同步更新
                            MqToolWindowService.getInstance(project).refresh();
                            DaemonCodeAnalyzer.getInstance(project).restart();
                        })
                        .submit(AppExecutorUtil.getAppExecutorService())));

        // 源码和模块依赖变更后重新定位消息端点
        MessageBusConnection connection = project.getMessageBus().connect(parentDisposable);
        connection.subscribe(PsiModificationTracker.TOPIC, scheduleRefresh::run);
        connection.subscribe(ModuleRootListener.TOPIC, new ModuleRootListener() {
            @Override
            public void rootsChanged(@NotNull ModuleRootEvent event) {
                scheduleRefresh.run();
            }
        });
        // 索引完成后替换启动期间的旧结果
        connection.subscribe(DumbService.DUMB_MODE, new DumbService.DumbModeListener() {
            @Override
            public void exitDumbMode() {
                scheduleRefresh.run();
            }
        });
        scheduleRefresh.run();
    }
}
