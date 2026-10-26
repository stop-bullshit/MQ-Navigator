# Research: 侧边栏模块浏览与刷新优化

## 1. 保留现有树模型和面板边界

**Decision**: 继续使用 `MqTopologyPanel` 内现有 `SimpleTree`、`DefaultMutableTreeNode` 和 `DefaultTreeModel`，不迁移到 参考插件 使用的 `AsyncTreeModel`、`StructureTreeModel` 或自定义节点层次。

**Rationale**: 当前面板已经消费项目级不可变 endpoint 快照并按模块生成树，问题来自 `rebuildTree()` 每次重建后统一展开模块，而不是树基础设施不足。直接修正展开状态应用逻辑改动最小，也避免复制参考项目的缓存和扫描结构。

**Alternatives considered**:

- 复制 参考插件 的异步树结构：拒绝；会引入新的模型、节点层次和生命周期复杂度，且没有性能失败证据。
- 新增独立 tree builder/service：拒绝；当前构树逻辑只被一个面板使用，没有现实复用需求。

## 2. 模块状态使用稳定模块名并仅保留在会话内

**Decision**: 在 `MqTopologyPanel` 中保存当前用户展开的模块名集合。展示标签中的数量不参与身份；空模块名继续归一为 `(project)`。首次出现或重命名后的模块默认折叠，删除模块从状态集合清理。

**Rationale**: 模块名在当前 Project 内稳定且唯一，能够跨刷新和树模型替换恢复状态；规格明确排除 IDE 重启后的持久化，因此无需 `PropertiesComponent` 或新设置模型。

**Alternatives considered**:

- 使用完整展示文本作为 key：拒绝；端点数量变化会导致状态错误丢失。
- 按 `TreePath` 保存：拒绝；模型替换后旧节点路径不再有效。
- 持久化用户偏好：拒绝；超出规格范围并增加迁移、清理和跨项目状态风险。

## 3. 筛选展开是临时视图，不覆盖用户选择

**Decision**: 文本搜索非空或类型筛选不是 All 时，程序化展开含匹配结果的模块和分类层；使用布尔保护阻止这些事件写入会话展开集合。清除全部条件后恢复用户原状态。

**Rationale**: 当前面板在搜索时可能展开所有行，且每次模型重建都会展开模块。把筛选展开与用户选择分离，既保证匹配内容立即可见，也满足清除条件后恢复浏览上下文。

**Alternatives considered**:

- 筛选时只显示模块名：拒绝；用户仍需额外展开才能看到匹配端点，不满足规格。
- 筛选时展开端点关系子树：拒绝；会在大量关系下产生过深树和额外视觉噪声。
- 清除筛选后保留临时状态：拒绝；会破坏用户刷新前的模块布局。

## 4. 刷新继续使用平台非阻塞 read action

**Decision**: 保留 `ReadAction.nonBlocking(...).inSmartMode(project).expireWith(project).finishOnUiThread(...)` 主链，项目扫描继续在后台 read action 执行，树替换和状态文本只在 EDT 更新。

**Rationale**: JetBrains 官方线程模型要求耗时 PSI/索引读取离开 EDT，项目级索引访问的 NBRA 应等待 smart mode，并建议用生命周期条件使任务随项目失效。当前实现已经符合主方向，本功能无需线程、锁或新的执行器。[JetBrains Threading Model](https://plugins.jetbrains.com/docs/intellij/threading-model.html)

**Alternatives considered**:

- 在刷新 Action 中同步扫描：拒绝；会冻结 UI 并违反 Constitution。
- 创建自定义线程池或串行队列：拒绝；平台 NBRA 已处理取消与重试，没有证据需要第二套调度。
- 捕获 `IndexNotReadyException` 后重试：拒绝；smart mode 是明确的平台路径。

## 5. 连续刷新采用 EDT 最新请求序号

**Decision**: 每次 `refresh()` 在 EDT 递增面板请求序号，后台任务捕获该值；成功或失败回到 EDT 后，仅请求号仍等于当前值时才更新树或状态。

**Rationale**: 规格只要求旧结果不能覆盖新结果。请求序号的写入和比较都发生在单一 EDT，可用最小状态解决，不需要锁、`AtomicLong`、取消管理器或额外队列。

**Alternatives considered**:

- 保存并主动取消前一个 promise：暂不采用；过期结果丢弃已经满足可见正确性，取消状态会扩大生命周期处理。
- 锁或原子变量：拒绝；UI 决策均在 EDT，不存在必要性。
- 禁用刷新按钮直到完成：拒绝；会改变连续点击的既定验收场景。

## 6. 使用标准刷新图标和现有 Action System

**Decision**: 保留 `RefreshMqIndexAction` 和现有侧边栏 `ActionToolbar`，将自定义 RabbitMQ 图标替换为平台标准刷新图标，并保留清晰的 action text 与 tooltip。

**Rationale**: 参考项目使用标准刷新图标；JetBrains 指南建议把高频动作放入 tool window 工具栏，Action System 是菜单和工具栏的标准入口。标准图标比消息系统 Logo 更容易表达“刷新”。[Tool Window UI Guidelines](https://plugins.jetbrains.com/docs/intellij/tool-window.html) [Action System](https://plugins.jetbrains.com/docs/intellij/action-system.html)

**Alternatives considered**:

- 在面板中新增普通 Swing 按钮：拒绝；会绕开已有 Action，并使 Tools 菜单与侧边栏行为分叉。
- 新增全部展开/全部折叠 toolbar actions：拒绝；规格明确排除。

## 7. 状态反馈使用面板内轻量文本

**Decision**: 在现有面板增加一个轻量状态文本区域，覆盖等待索引、正在刷新、合法空结果、筛选无结果和刷新失败保留旧结果；非空成功后清除临时状态。

**Rationale**: 根节点始终可见时，树自身 empty text 不足以稳定表达所有状态；一个面板字段即可完成反馈，不需要通知服务、弹窗或新的状态类。失败回调不替换树，从数据边界上保证旧结果保留。

**Alternatives considered**:

- 每次刷新发 balloon notification：拒绝；高频操作会造成干扰。
- 只写日志：拒绝；用户无法从侧边栏确认刷新状态。
- 失败时清空树：拒绝；会把技术失败伪装成业务空结果。

## 8. 测试保持一个新增 UI 类和一个现有 heavy 类

**Decision**: 新增 `MqTopologyPanelTest`，通过包可见 `Supplier<List<MqEndpoint>>` 构造入口控制异步结果；继续扩展唯一的 `MqNavigationPerformanceTest`，在独立测试方法中生成 50 个真实模块和 2,000 个 RabbitMQ 端点，测量用户可见刷新 p95。

**Rationale**: 轻量测试需要稳定复现请求反向完成、失败和空结果；标准 `Supplier` 隔离现有扫描调用即可，无需新接口。真实规模性能继续集中在一个 heavy fixture，符合项目现有测试治理。

**Alternatives considered**:

- 通过反射修改面板私有状态：拒绝；脆弱且不能清晰表达生产回调边界。
- 新建第二个 heavy 测试类：拒绝；重复 50 模块 fixture 和运行成本。
- 新增生产 state/controller 类以方便测试：拒绝；当前状态仅属于一个面板，没有独立业务层必要性。

## Sources Reviewed

- `src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java`
- `src/main/java/cn/luckyh/mqx/action/RefreshMqIndexAction.java`
- `src/main/java/cn/luckyh/mqx/navigation/MqNavigationService.java`
- `src/test/java/cn/luckyh/mqx/navigation/MqNavigationPerformanceTest.java`
- 外部参考插件的导航树行为（只读参考，不包含其源码）
- 外部参考插件的刷新动作行为（只读参考，不包含其源码）
- [JetBrains Threading Model](https://plugins.jetbrains.com/docs/intellij/threading-model.html)
- [JetBrains Tool Window UI Guidelines](https://plugins.jetbrains.com/docs/intellij/tool-window.html)
- [JetBrains Action System](https://plugins.jetbrains.com/docs/intellij/action-system.html)

所有技术未知项已解决，无 `NEEDS CLARIFICATION`。
