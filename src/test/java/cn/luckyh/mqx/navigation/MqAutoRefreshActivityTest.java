package cn.luckyh.mqx.navigation;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * MQ端点自动刷新回归。
 *
 * @author heng.wang
 * @since 2026-11-12
 */
public class MqAutoRefreshActivityTest extends BasePlatformTestCase {

    @Override
    protected String getTestDataPath() {
        return Path.of("src/test/testData").toAbsolutePath().toString();
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.copyDirectoryToProject("stubs", "");
    }

    public void testStartupReplacesPersistedEmptySnapshotWithoutManualRefresh() {
        // 项目启动后空快照自动补齐当前源码中的发送端
        configureSource("current-orders");
        MqNavigationService service = MqNavigationService.getInstance(getProject());
        service.loadState(cacheState(List.of()));

        MqAutoRefreshActivity.start(getProject(), getTestRootDisposable());

        awaitTopic(service, "current-orders");
    }

    public void testStartupReplacesPersistedDestinationWithoutManualRefresh() {
        // 项目启动后旧目的地由当前源码重新解析
        PsiFile file = configureSource("current-orders");
        MqNavigationService.CachedEndpointState cached = new MqNavigationService.CachedEndpointState();
        cached.endpointId = "stale-producer";
        cached.system = "KAFKA";
        cached.role = "PRODUCER";
        cached.resolutionState = "STATIC";
        cached.topic = "old-orders";
        cached.moduleName = getModule().getName();
        cached.fileName = file.getName();
        cached.className = "AutoRefreshSource";
        cached.memberName = "publish";
        cached.sourceUrl = file.getVirtualFile().getUrl();
        cached.sourceOffset = file.getText().indexOf("send(");
        MqNavigationService service = MqNavigationService.getInstance(getProject());
        service.loadState(cacheState(List.of(cached)));

        MqAutoRefreshActivity.start(getProject(), getTestRootDisposable());

        awaitTopic(service, "current-orders");
        assertFalse("Old cached endpoint must be replaced", service.getState().endpoints.stream()
                .anyMatch(endpoint -> "stale-producer".equals(endpoint.endpointId)));
    }

    public void testCommittedJavaEditAutomaticallyUpdatesSnapshot() {
        // 发送目的地修改后自动更新快照，不靠查询触发重扫
        configureSource("original-orders");
        MqNavigationService service = MqNavigationService.getInstance(getProject());
        MqAutoRefreshActivity.start(getProject(), getTestRootDisposable());
        awaitTopic(service, "original-orders");

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            myFixture.getEditor().getDocument().setText(source("changed-orders"));
            PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        });

        awaitTopic(service, "changed-orders");
    }

    public void testStartupWaitsForIndexAndRefreshesAfterSmartMode() {
        // 索引完成后自动补齐启动期间保留的旧空快照
        configureSource("indexed-orders");
        MqNavigationService service = MqNavigationService.getInstance(getProject());
        MqNavigationService.CacheState stale = cacheState(List.of());
        service.loadState(stale);
        DumbModeTestUtils.EternalTaskShutdownToken token =
                DumbModeTestUtils.startEternalDumbModeTask(getProject());
        try {
            assertTrue(DumbService.isDumb(getProject()));
            MqAutoRefreshActivity.start(getProject(), getTestRootDisposable());
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            assertSame("Indexing must not publish an incomplete scan", stale, service.getState());
        } finally {
            DumbModeTestUtils.endEternalDumbModeTaskAndWaitForSmartMode(getProject(), token);
        }

        awaitTopic(service, "indexed-orders");
    }

    public void testSavedPropertiesEditAutomaticallyUpdatesPlaceholderDestination() {
        // 配置保存后自动更新直接Value字段引用的消息目的地
        PsiFile properties = myFixture.addFileToProject("src/main/resources/application.properties",
                "mq.topic=original-config-orders\n");
        myFixture.configureByText("AutoRefreshSource.java", """
                class AutoRefreshSource {
                    org.springframework.kafka.core.KafkaTemplate<String, Object> template;
                    @org.springframework.beans.factory.annotation.Value("${mq.topic}") String topic;
                    void publish() { template.send(this.topic, new Object()); }
                }
                """);
        MqNavigationService service = MqNavigationService.getInstance(getProject());
        MqAutoRefreshActivity.start(getProject(), getTestRootDisposable());
        awaitTopic(service, "original-config-orders");

        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            try {
                VfsUtil.saveText(properties.getVirtualFile(), "mq.topic=changed-config-orders\n");
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        });

        awaitTopic(service, "changed-config-orders");
    }

    public void testDisposedListenerDoesNotPublishLaterSourceChanges() {
        // 刷新监听释放后不再发布后续源码修改
        configureSource("before-disposal");
        MqNavigationService service = MqNavigationService.getInstance(getProject());
        Disposable listenerDisposable = Disposer.newDisposable("MQ auto-refresh test");
        try {
            MqAutoRefreshActivity.start(getProject(), listenerDisposable);
            awaitTopic(service, "before-disposal");
            Disposer.dispose(listenerDisposable);
            MqNavigationService.CacheState published = service.getState();

            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                myFixture.getEditor().getDocument().setText(source("after-disposal"));
                PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
            });

            // 经过多个合并周期仍保持释放前的结果
            long deadline = System.nanoTime() + 1_500_000_000L;
            PlatformTestUtil.waitWithEventsDispatching("disposed MQ refresh listeners",
                    () -> System.nanoTime() >= deadline, 5);
            assertSame("Disposed listener must not publish a new snapshot", published, service.getState());
            assertEquals("before-disposal", service.getState().endpoints.get(0).topic);
        } finally {
            if (!Disposer.isDisposed(listenerDisposable)) {
                Disposer.dispose(listenerDisposable);
            }
        }
    }

    private PsiFile configureSource(String topic) {
        return myFixture.configureByText("AutoRefreshSource.java", source(topic));
    }

    private String source(String topic) {
        return """
                class AutoRefreshSource {
                    org.springframework.kafka.core.KafkaTemplate<String, Object> template;
                    void publish() { template.send("%s", new Object()); }
                }
                """.formatted(topic);
    }

    private MqNavigationService.CacheState cacheState(
            List<MqNavigationService.CachedEndpointState> endpoints) {
        // 持久化快照保持当前格式，确保验证的是自动重扫而非版本失效
        MqNavigationService.CacheState state = new MqNavigationService.CacheState();
        state.version = 5;
        state.savedAt = System.currentTimeMillis();
        state.endpoints = new ArrayList<>(endpoints);
        return state;
    }

    private void awaitTopic(MqNavigationService service, String topic) {
        // 只观察已发布状态，避免currentEndpoints主动计算掩盖监听失效
        PlatformTestUtil.waitWithEventsDispatching("MQ snapshot for " + topic, () -> {
            List<MqNavigationService.CachedEndpointState> endpoints = service.getState().endpoints;
            return endpoints.size() == 1 && topic.equals(endpoints.get(0).topic);
        }, 20);
    }
}
