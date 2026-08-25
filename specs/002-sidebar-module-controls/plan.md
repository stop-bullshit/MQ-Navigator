# Implementation Plan: 侧边栏模块浏览与刷新优化

**Branch**: `002-sidebar-module-controls`（Spec Kit 逻辑分支；当前 Git checkout 仍为 `master`） | **Date**: 2026-08-24 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/002-sidebar-module-controls/spec.md`

## Summary

在现有 RabbitMQ 工具窗口中保留 `SimpleTree`、`DefaultTreeModel`、项目级扫描服务和刷新 Action，只调整侧边栏展示状态：首次加载时根节点展开、模块节点默认折叠；用户可独立控制模块状态；刷新后按稳定模块名恢复仍存在模块的状态；搜索或类型筛选期间仅临时展开匹配模块和分类，清除条件后恢复用户选择。刷新继续通过现有非阻塞 read action 等待 smart mode，面板用 EDT 内递增请求序号丢弃过期回调，并用轻量状态文本区分等待、成功空结果、无匹配和失败保留旧结果。刷新入口改用平台标准刷新图标，不新增生产类、接口、持久化、线程、锁、扫描器或缓存。

## Technical Context

**Language/Version**: Java 17；Gradle Kotlin DSL，Gradle Wrapper 9.x

**Primary Dependencies**: IntelliJ Platform Gradle Plugin `2.18.1`；IntelliJ IDEA Community `2023.3`；bundled Java plugin；现有 Swing/JetBrains UI、Action System、`ReadAction.nonBlocking`、`SimpleTree`、`DefaultTreeModel`；不新增依赖

**Storage**: 仅 `MqTopologyPanel` 生命周期内的模块展开名称集合、筛选临时状态和刷新请求序号；不持久化到磁盘或 IDE 设置

**Testing**: IntelliJ `TestFrameworkType.Plugin.Java`、JUnit 4.13.2、`BasePlatformTestCase` UI/fixture 测试；扩展现有唯一 heavy `MqNavigationPerformanceTest`；完整测试、Plugin Verifier 和 sandbox 人工验证分开记录

**Target Platform**: IntelliJ IDEA 2023.3+，最低 build `233`

**Project Type**: 单模块桌面 IDEA 插件

**Performance Goals**: 在 50 个真实模块、2,000 个 RabbitMQ 端点的验收规模中，95% 的用户可见刷新在 3 秒内完成；模块展开或折叠只需一次直接操作；刷新和清除筛选后的状态恢复准确率为 100%

**Constraints**: 项目扫描和索引访问不在 EDT；UI 模型和组件只在 EDT 更新；连续刷新采用最新请求优先；不增加自定义线程、锁、第二套缓存、PSI/文件/项目级监听器或持久化；仅允许在 `MqTopologyPanel` 生命周期内增加一个树展开/折叠监听器，用于维护模块会话状态并随面板释放；不改变 parser、matcher、导航规则和现有分类含义；不修改用户项目或连接 broker

**Scale/Scope**: 当前打开项目的 RabbitMQ 侧边栏；50 个真实模块、2,000 个 RabbitMQ 端点；只支持单模块独立展开/折叠，不支持全部展开/全部折叠和重启后恢复

## Constitution Check

*GATE: Phase 0 前和 Phase 1 后均已检查。*

| Principle | Pre-Research | Post-Design | Evidence |
|-----------|--------------|-------------|----------|
| I. 最小范围与直接实现 | PASS | PASS | 只修改 `MqTopologyPanel`、`RefreshMqIndexAction` 和相关测试；不新增生产类、接口、设计模式或中间层 |
| II. 平台优先与证据驱动的复杂度 | PASS | PASS | 复用现有 `SimpleTree`、ActionToolbar、标准刷新图标和 NBRA；不复制 InterfaceX 的 AsyncTreeModel、缓存或扫描结构 |
| III. PSI、索引与并发安全 | PASS | PASS | 扫描继续通过 NBRA 在 smart mode 执行，项目关闭时失效；请求序号的递增和比较都在 EDT，不使用线程、锁或原子状态 |
| IV. 用户项目只读与运行时隔离 | PASS | PASS | 功能仅更新插件 UI；`interfacex` 保持只读参考；现有 broker 隔离测试和用户项目哈希检查继续作为门禁 |
| V. 测试先行与量化证据 | PASS | PASS | 新 UI 测试先覆盖折叠、筛选、刷新和过期回调；唯一 heavy 性能类扩展 50 模块/2,000 Rabbit 端点的可见刷新 p95；构建、测试、Verifier、sandbox 分开报告 |
| VI. 可追踪的增量交付 | PASS | PASS | 三个用户故事分别映射到 UI 测试和人工场景，跨故事安全要求映射到现有 broker 与源码只读验证 |

无 Constitution 例外，无未解决的 `NEEDS CLARIFICATION`，`Complexity Tracking` 不适用。

## Project Structure

### Documentation (this feature)

```text
specs/002-sidebar-module-controls/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── verification.md              # 实现阶段按证据类型记录实际结果
├── contracts/
│   └── sidebar-ui.md
├── checklists/
│   └── requirements.md
└── tasks.md                     # 后续 /speckit-tasks 生成，本阶段不创建
```

### Source Code (repository root)

```text
src/main/java/cn/luckyh/mqx/
├── action/
│   └── RefreshMqIndexAction.java        # 修改：标准刷新图标，保留现有失效与刷新入口
├── navigation/
│   └── MqNavigationService.java         # 保护：继续提供唯一项目快照，不修改
└── ui/
    ├── MqTopologyPanel.java             # 修改：模块状态、筛选临时展开、刷新序号和反馈
    └── MqToolWindowService.java         # 保护：继续转发面板刷新，不修改

src/test/java/cn/luckyh/mqx/
├── ui/
│   └── MqTopologyPanelTest.java         # 新增：轻量 UI 状态与连续刷新测试
├── navigation/
│   └── MqNavigationPerformanceTest.java # 修改：同一 heavy 类增加侧边栏可见刷新门槛
└── navigation/
    └── MqBrokerIsolationTest.java       # 保护：复用，不修改
```

**Structure Decision**: 保持现有单模块插件结构。所有生产行为都留在已有面板和刷新 Action 内；只新增一个测试类。为确定性测试，`MqTopologyPanel` 可增加一个包可见构造入口接收标准 `Supplier<List<MqEndpoint>>`，公共构造仍直接使用现有 `MqNavigationService`。该入口只隔离现有外部扫描调用，不形成新的业务层或框架。

## Design Flow

```text
工具窗口打开或用户点击刷新
    → 现有 RefreshMqIndexAction 失效项目 CachedValue
    → MqTopologyPanel 在 EDT 递增 refreshRequestId 并显示等待状态
    → ReadAction.nonBlocking(currentEndpoints)
         .inSmartMode(project)
         .expireWith(project)
    → finishOnUiThread
    → 请求号不是最新：丢弃，不改变树
    → 请求号仍是最新：替换 RabbitMQ endpoint 列表并重建树
    → 正常浏览：根展开，仅恢复 expandedModuleNames 中仍存在的模块
    → 搜索/类型筛选：临时展开匹配模块和其分类，不写回用户状态
    → 更新数量、空状态或成功状态
```

### Module state handling

1. 模块身份使用未拼接数量的稳定模块名；空模块名继续归一为现有 `(project)` 分组。
2. 面板监听用户对模块节点的展开与折叠，只在未处于程序化状态恢复、且搜索和类型筛选都未启用时更新 `expandedModuleNames`。
3. `rebuildTree()` 设置模型和恢复路径期间使用面板内布尔保护，避免程序化展开触发监听器后污染用户状态。
4. 正常浏览只展开根节点和用户已展开模块；新模块不在集合中，因此默认折叠；被删除模块在重建后从集合中清理。
5. 搜索或类型筛选启用时，展开所有含匹配结果的模块及其 Consumers、Producers、Topology/Queues/Bindings/Exchanges 分类，但不自动展开端点关系子树。
6. 清除全部条件后按 `expandedModuleNames` 恢复，不把筛选期间的临时操作当作长期会话选择。

### Refresh and feedback handling

1. `RefreshMqIndexAction` 继续调用 `requestRefresh()`、面板刷新和 daemon restart，只把自定义 RabbitMQ 图标替换为平台标准刷新图标，提升可识别性。
2. 面板在提交 NBRA 前更新轻量状态文本：smart mode 未就绪时显示等待索引，否则显示正在刷新；旧树保持可用。
3. 成功回调只在请求号仍为最新时应用。合法空结果替换旧树并显示“未发现 RabbitMQ 端点”；筛选空结果显示“当前条件无匹配结果”。
4. 失败回调只在请求号仍为最新时显示失败状态，不调用 `replaceTree`，因此旧的 `allEndpoints` 和树模型不丢失。
5. 请求号只在 EDT 递增和比较；后台任务只携带捕获值，避免引入同步、锁、原子变量或自定义执行器。

## New Classes and Abstractions

| Type | Operation | Responsibility | Necessity |
|------|-----------|----------------|-----------|
| `MqTopologyPanelTest` | 新增测试类 | 验证模块默认折叠、独立状态、筛选临时展开、刷新恢复、空/失败反馈和过期结果丢弃 | 现有测试没有覆盖工具窗口，三个用户故事都需要先失败的独立证据 |

**Production additions**: 不新增生产类、接口、设计模式、Manager、Handler、Processor、Context 或中间层。面板仅新增必要字段、监听处理和一个使用标准 `Supplier` 的包可见测试构造入口；复杂规则保留为少量具有明确业务含义的方法，不拆分一行转发方法。

## Implementation Boundaries

### Files to create or modify

- `src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java`: 移除每次重建自动展开全部模块的行为；增加稳定模块身份、会话展开集合、筛选临时展开、最新请求保护和状态反馈。
- `src/main/java/cn/luckyh/mqx/action/RefreshMqIndexAction.java`: 使用平台标准刷新图标；保留现有刷新顺序和 Tools 菜单行为。
- `src/test/java/cn/luckyh/mqx/ui/MqTopologyPanelTest.java`: 新增轻量 UI 行为测试，使用受控 endpoint supplier 和真实 Swing 树操作。
- `src/test/java/cn/luckyh/mqx/navigation/MqNavigationPerformanceTest.java`: 在同一 50 模块 heavy fixture 中新增独立 RabbitMQ 侧边栏数据场景和用户可见刷新 p95；不创建第二个 heavy 测试类。
- `specs/002-sidebar-module-controls/*`: 保持规格、计划、研究、数据模型、交互约定和验证指南同步。

### Explicitly protected scope

- 不修改 `MqNavigationService`、各 MQ parser、`MqEndpointMatcher`、`MqToolWindowService`、`plugin.xml`、依赖和插件版本。
- 不修改 Consumers、Producers、Topology、Queues、Bindings、Exchanges 的分组和匹配语义。
- 不新增 Kafka/RocketMQ 工具窗口、全部展开/全部折叠、展开状态持久化或设置页。
- 不删除当前可能被其他 UI 使用的图标资源，不做无关资源清理。
- 不修改 `D:\github\interfacex` 或 `D:\github\feignx-plugin`。
- 不连接 broker，不写入用户项目，不执行项目业务代码。

## Risks and Controls

- **程序化展开污染用户状态**: 设置模型和恢复路径时暂停状态监听；筛选状态不写回会话集合。
- **模块标签包含数量导致状态丢失**: 状态键使用原始模块名，不使用展示标签；模块重命名按新增处理。
- **连续刷新旧结果覆盖新结果**: EDT 请求序号只允许最新回调更新 UI；受控 supplier 测试反向完成两个请求验证。
- **刷新失败误显示空树**: 仅成功回调替换 `allEndpoints`；失败保留旧模型并显示状态。
- **筛选展开过深导致树膨胀**: 只展开模块和分类层，不自动展开 endpoint 的关系子树。
- **UI 测试异步不稳定**: 使用平台事件队列等待和受控 supplier，不使用 `Thread.sleep` 轮询生产行为。
- **50 模块性能测试扩大运行时间**: 继续集中在唯一 heavy 类中，独立记录 fixture、首次扫描、刷新 p50/p95/max；失败先保留数据，不引入复杂优化。

## Verification Design

### Test-first order

1. 在 `MqTopologyPanelTest` 先写默认折叠、单模块展开不影响其他模块、刷新保留状态、新增模块折叠和删除模块清理的失败测试。
2. 增加搜索和类型筛选临时展开、清除后恢复、无匹配反馈及双击导航回归测试。
3. 使用两个受控刷新结果按相反顺序完成，先证明旧回调会覆盖新结果，再实现最新请求保护。
4. 增加失败刷新保留旧树和合法空刷新替换为空状态的区别测试。
5. 在上述测试失败后修改 `MqTopologyPanel` 和刷新图标；不得先写生产逻辑再补测试。

唯一例外是 Foundational 阶段可先增加 package-private `Supplier<List<MqEndpoint>>` 构造入口作为测试接缝。该入口必须复用现有初始化流程，不得改变生产行为、状态模型、刷新逻辑或渲染结果；所有用户行为实现仍必须在对应失败测试之后编写。

### Focused and regression gates

- 定向 UI：`./gradlew.bat test --tests "cn.luckyh.mqx.ui.MqTopologyPanelTest" --rerun-tasks`
- 定向 heavy：`./gradlew.bat test --tests "cn.luckyh.mqx.navigation.MqNavigationPerformanceTest" --rerun-tasks --no-parallel`
- 完整测试：`./gradlew.bat clean test`
- 配置与结构：`./gradlew.bat verifyPluginProjectConfiguration verifyPluginStructure`
- 二进制兼容：`./gradlew.bat verifyPlugin`
- sandbox：`./gradlew.bat runIde`
- 已验证结果统一记录在 `verification.md`：定向 UI 为 10/10，通过；heavy 为 2/2，通过，50 模块/2,000 RabbitMQ endpoint 的 40 次可见刷新 p95 为 360,057,100ns。

### Performance and usability gates

- 同一 heavy 类的侧边栏场景创建 50 个真实 IntelliJ Java 模块，每模块 20 个 RabbitMQ producer 和 20 个 consumer，合计 2,000 个 RabbitMQ 端点。
- 首次扫描和首次树构建单独记录；预热后执行 40 次源码修改、提交、请求刷新和树可见结果确认。
- 计时从用户刷新入口触发对应调用开始，到最新模块数量和目标端点已在 EDT 模型中可见为止；p95 使用 nearest-rank，严格小于 3 秒。
- 至少 10 名首次使用者在同等 50 模块/2,000 端点验收项目中，从任务提示开始到展开指定模块并定位指定分类停止；至少 9 人在 5 秒内完成。

### Safety and reporting gates

- 复用 `MqBrokerIsolationTest` 和运行依赖检查，三种 broker 连接尝试均为 0。
- 对 sandbox 验收副本在刷新、折叠、搜索和导航前后比较受保护文件 SHA-256，非用户改动为 0。
- 分开记录 focused UI、heavy performance、完整测试、Verifier、sandbox、用户试用和生产项目验证；计划项或未运行项不得报告为已通过。

## Traceability

| Scope | Requirements | Success Criteria | Primary Evidence |
|-------|--------------|------------------|------------------|
| US1 模块浏览 | FR-001–FR-005 | SC-001, SC-003 | `MqTopologyPanelTest`、50 模块人工可用性测试 |
| US2 主动刷新 | FR-006–FR-011 | SC-002, SC-004, SC-006 | `MqTopologyPanelTest`、`MqNavigationPerformanceTest` |
| US3 搜索与筛选连续性 | FR-012–FR-014 | SC-004, SC-005 | `MqTopologyPanelTest`、sandbox 双击导航回归 |
| 只读与运行时隔离 | FR-015 | SC-007 | `MqBrokerIsolationTest`、依赖检查、SHA-256 基线 |

## Complexity Tracking

无 Constitution 违规，不需要复杂度例外。
