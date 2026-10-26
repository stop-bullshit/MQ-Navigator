# Research: RabbitMQ 刷新进度提示

## 1. 使用状态区内的 `JProgressBar`

**Decision**: 在 `MqTopologyPanel` 现有底部状态区加入标准 `JProgressBar`，与 `JBLabel` 并列展示。

**Rationale**: `com.intellij.ui.JBProgressBar` 已标记为 deprecated 并计划移除；标准 `JProgressBar` 在 IDEA 2023.3 与 2026.2.1 下保持兼容，能够满足不确定进度展示，且不需要新依赖或自绘逻辑。

**Alternatives considered**:

- `JBProgressBar`：外观符合平台，但已计划移除，因此不再采用。
- `AsyncProcessIcon`：是旋转图标，不符合用户希望看到进度条的表达。
- 自绘动画：拒绝；会增加主题、缩放、动画和兼容维护成本。

## 2. 不采用覆盖式加载面板

**Decision**: 不使用 `JBLoadingPanel`、`ProgressStripe` 或覆盖树内容的 loading decorator。

**Rationale**: 规格要求刷新期间继续浏览旧树并使用折叠、搜索和筛选。覆盖式加载层可能遮挡或阻止这些交互，而状态区内进度条不会改变树的可用性。

**Alternatives considered**:

- `JBLoadingPanel`：拒绝；生命周期需要额外 Disposable 绑定，并且加载覆盖与继续浏览要求冲突。
- `ProgressStripe`：拒绝；目标类位于其他功能包，且会将进度展示扩展到整个树区域。

## 3. 保留现有 NBRA，不迁移到后台任务进度体系

**Decision**: 保留 `ReadAction.nonBlocking(...).inSmartMode(project).expireWith(project).finishOnUiThread(...)`，仅在现有 UI 生命周期点切换进度条。

**Rationale**: 当前刷新已经满足索引、read action、项目关闭和 EDT 边界。参考插件 参考实现通过 `Task.Backgroundable` 和 `ProgressIndicator.setIndeterminate(true)` 表达扫描中，但复制该结构会重写现有执行链、取消行为和全局进度展示，远超增加侧边栏进度条的范围。

**Alternatives considered**:

- 将扫描改为 `Task.Backgroundable`：拒绝；没有执行链失败证据，还可能出现侧边栏与 IDE 全局进度的重复反馈。
- 给 NBRA 增加自定义线程或计时器：拒绝；平台执行器和现有回调已经足够。

## 4. 使用不确定状态，不显示百分比

**Decision**: 进度条只在“正在刷新 RabbitMQ 端点”状态下使用不确定模式；不显示百分比、剩余时间、扫描文件数或阶段。

**Rationale**: 现有 endpoint supplier 一次性返回最终快照，没有可靠总文件数或分阶段回调。百分比只能由推测产生，会误导用户。不确定进度条能准确表达“仍在处理”。

**Alternatives considered**:

- 按文件数计算百分比：拒绝；当前扫描入口不暴露稳定总数，索引结果和模块变化会改变分母。
- 伪造固定时长动画：拒绝；动画完成时间与实际刷新无关。
- 增加扫描阶段回调：拒绝；需要修改 `MqNavigationService` 和扫描器，超出需求。

## 5. 进度状态直接复用最新请求规则

**Decision**: 使用现有 `refreshing` 和 `refreshRequestId` 控制进度条；开始状态在 `refresh()` 的 EDT 主线设置，结束状态只在通过最新请求检查的成功或失败回调设置。

**Rationale**: 当前面板已经解决旧刷新结果覆盖新结果的问题。让进度条遵循同一检查可避免第二套状态机、原子变量或并发同步，也保证较早回调不能提前关闭最新提示。

**Alternatives considered**:

- 新增活动请求计数器：拒绝；产品语义只关心最新请求，计数会让旧请求阻止提示结束。
- 新增独立 refresh state 类：拒绝；状态仅服务一个面板且字段很少，没有复用或独立测试价值。
- 在 Action 中控制进度：拒绝；Action 不持有请求结果和项目关闭生命周期。

## 6. 等待索引时保留文字但不显示进度条

**Decision**: 如果刷新开始时项目处于 dumb mode，继续显示“等待项目索引完成”，进度条保持隐藏；项目可分析并直接进入现有“正在刷新”状态时才显示进度条。

**Rationale**: 用户要求是在“正在刷新 RabbitMQ 端点”状态旁增加进度条。等待索引不代表 endpoint 扫描已经开始，隐藏进度条能避免把索引等待误解为扫描进度，也无需为 NBRA 增加额外阶段通知。

**Alternatives considered**:

- 等待索引时也显示进度条：拒绝；会扩大用户描述并模糊等待与扫描两种文字状态。
- 在 smart mode 恢复瞬间新增 UI 回调再显示：拒绝；需要额外调度，快速扫描下只会产生闪烁。

## 7. 测试扩展现有 UI 类

**Decision**: 扩展现有 `MqTopologyPanelTest` 和受控 supplier，覆盖活动/结束、失败/空结果、等待索引、至少 10 组反向完成和刷新中交互；不新增测试类。

**Rationale**: 现有测试已经能够控制 supplier 的进入、放行和应用时机，并能递归查找命名 Swing 组件。直接扩展可复用项目 fixture 和 EDT 辅助，避免第二套 UI 测试支架。

**Alternatives considered**:

- 只做截图或人工验证：拒绝；无法稳定覆盖失败和反向完成。
- 新建 progress controller 单元测试：拒绝；会为测试推动生产抽象。
- 修改 heavy 测试类加入组件断言：拒绝；视觉生命周期属于 focused UI，heavy 只需要回归原 p95。

## Sources Reviewed

- `src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java`
- `src/main/java/cn/luckyh/mqx/action/RefreshMqIndexAction.java`
- `src/test/java/cn/luckyh/mqx/ui/MqTopologyPanelTest.java`
- `src/test/java/cn/luckyh/mqx/navigation/MqNavigationPerformanceTest.java`
- `build.gradle.kts`
- 本地 IDEA 2023.3 与 2026.2.1 中的标准 `JProgressBar`、`com.intellij.ui.components.JBLoadingPanel` 和 `com.intellij.util.ui.AsyncProcessIcon`
- 外部参考插件的导航树行为（只读参考，不包含其源码）

所有技术未知项已解决，无 `NEEDS CLARIFICATION`。
