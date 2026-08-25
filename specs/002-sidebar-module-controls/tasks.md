# Tasks: 侧边栏模块浏览与刷新优化

**Input**: Design documents from `/specs/002-sidebar-module-controls/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [sidebar-ui.md](contracts/sidebar-ui.md), [quickstart.md](quickstart.md)

**Tests**: Constitution 要求测试先行。每个用户故事必须先写能够暴露缺失行为的测试并记录 red 证据，再修改生产代码；功能、性能、安全、兼容性和人工验收结果分别记录。

**Organization**: 任务按用户故事组织。`MqTopologyPanel.java` 是三个故事共享的唯一生产热点文件，因此故事按 P1 → P2 → P3 顺序实施，避免并行冲突；不同测试文件和 `RefreshMqIndexAction.java` 可在明确依赖后并行。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可在不同文件上并行执行，且不依赖未完成任务
- **[Story]**: 对应 `spec.md` 中的 US1、US2、US3
- 每项任务均包含具体文件路径

## Phase 1: Setup (Shared Evidence)

**Purpose**: 建立实现前基线和分层验证记录，保护现有代码与用户项目只读边界。

- [X] T001 在 `specs/002-sidebar-module-controls/verification.md` 创建 Pre-change、Focused UI、Heavy Performance、Full Test、Plugin Verifier、Sandbox、Usability、Source Safety、Broker Isolation 九个证据区，记录实现前 `git status --short`、`src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java` 与 `src/main/java/cn/luckyh/mqx/action/RefreshMqIndexAction.java` 的 SHA-256，并列出 plan 中明确受保护的生产文件和外部参考目录

---

## Phase 2: Foundational (Blocking Test Infrastructure)

**Purpose**: 建立三个用户故事共用、但不包含业务断言的轻量 UI 测试支架。

**⚠️ CRITICAL**: T002 完成后才能编写各用户故事的 red 测试；不得在本阶段修改生产行为。

- [X] T002 在 `src/test/java/cn/luckyh/mqx/ui/MqTopologyPanelTest.java` 建立 `BasePlatformTestCase` 测试类，加入 EDT 执行与事件队列等待、递归定位 `SimpleTree`/状态组件、创建带有效智能指针的 RabbitMQ endpoint、按模块组织测试数据和可控制 endpoint supplier 的共用辅助代码；同时在 `src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java` 仅增加公共构造委托到 package-private `Supplier<List<MqEndpoint>>` 构造入口并保存 supplier。该入口只作为测试接缝并复用现有初始化流程，不得实现或改变任何刷新、构树、展开、筛选、状态或渲染行为

**Checkpoint**: UI 测试支架可编译；生产源码仅增加无行为变化的测试接缝，刷新、构树、展开、筛选、状态和渲染行为尚未改变。

---

## Phase 3: User Story 1 - 按模块浏览侧边栏内容 (Priority: P1) 🎯 MVP

**Goal**: 根节点保持展开，模块首次默认折叠；用户可独立展开或折叠模块，数量与现有分类保持正确。

**Independent Test**: 向面板提供至少 10 个模块及 `(project)` 分组的 RabbitMQ endpoint，验证根展开、模块折叠、数量正确；展开或折叠任一模块只改变该模块，Consumers、Producers、Topology 分类保持现有含义。

### Tests for User Story 1

> **NOTE: 先完成 T003 并确认测试因当前自动展开全部模块而失败，再执行 T004。**

- [X] T003 [US1] 在 `src/test/java/cn/luckyh/mqx/ui/MqTopologyPanelTest.java` 添加根节点默认展开、至少 10 个模块与 `(project)` 分组默认折叠、模块可见 endpoint 数量、单模块展开/折叠不影响其他模块、展开后现有 Consumers/Producers/Topology 分类仍正确的失败测试，并在 `specs/002-sidebar-module-controls/verification.md` 记录定向测试的预期 red 断言

### Implementation for User Story 1

- [X] T004 [US1] 在 `src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java` 使用不含数量的稳定模块名标识模块节点，移除每次 `rebuildTree()` 自动展开全部模块的循环，增加面板会话内 `expandedModuleNames`、树展开/折叠监听和程序化恢复保护，使根始终展开、新模块默认折叠、用户操作只更新目标模块，并在刷新树后清理已删除模块 key
- [X] T005 [US1] 运行 `.\gradlew.bat test --tests "cn.luckyh.mqx.ui.MqTopologyPanelTest" --rerun-tasks`，确认 US1 red 用例转绿且没有改变 endpoint 分类与双击监听，在 `specs/002-sidebar-module-controls/verification.md` 记录测试数量、命令和结果

**Checkpoint**: User Story 1 可作为独立 MVP 演示和验收，无需等待刷新反馈或筛选状态连续性。

---

## Phase 4: User Story 2 - 在侧边栏主动刷新项目端点 (Priority: P2)

**Goal**: 侧边栏提供可识别刷新入口；刷新保留模块状态，区分成功空结果与失败，连续刷新只应用最新结果，并满足真实规模 p95。

**Independent Test**: 使用受控 supplier 依次返回成功、空、失败和反向完成的两个结果，验证旧树保留、模块状态恢复、新模块折叠、删除模块消失、最终结果不回退；另在 50 个真实模块和 2,000 个 RabbitMQ endpoint 下验证 40 次用户可见刷新 p95 小于 3 秒。

### Tests for User Story 2

> **NOTE: T006 与 T007 可并行编写；T008 必须确认缺失行为为 red 后才能修改生产代码。**

- [X] T006 [P] [US2] 在 `src/test/java/cn/luckyh/mqx/ui/MqTopologyPanelTest.java` 添加刷新前后保留已存在模块状态、新模块默认折叠、删除/重命名模块清理、合法空结果替换旧树、失败保留旧树并显示反馈、失败后再次成功刷新会清除失败状态并展示最新结果、索引等待/刷新中状态、两个请求反向完成时仅最新结果生效、工具栏刷新 action 使用标准刷新图标和明确 tooltip 的失败测试
- [X] T007 [P] [US2] 在 `src/test/java/cn/luckyh/mqx/navigation/MqNavigationPerformanceTest.java` 增加独立侧边栏性能测试方法：复用同一 heavy fixture 创建 50 个真实模块，每模块生成 20 个 RabbitMQ producer 与 20 个 consumer，断言基线合计 2,000 个 RabbitMQ endpoint，单独记录首次扫描和首次树构建；预热后以基线副本轮换执行新增、修改、删除源码并提交 PSI 文档，采集 40 次用户可见刷新耗时。新增样本等待新增 endpoint 可见且模块与根节点数量增加 1；修改样本等待旧展示值消失、新展示值可见且数量不变；删除样本等待目标 endpoint 消失且模块与根节点数量减少 1。每轮从请求刷新开始，到对应操作的全部条件进入 EDT tree model 后停止计时，再以 nearest-rank 断言 p95 `< 3_000_000_000 ns`
- [X] T008 [US2] 运行 T006/T007 对应定向命令，在 `specs/002-sidebar-module-controls/verification.md` 记录 UI 缺失行为的 red 断言和 heavy 基线；如果性能测试在现有实现已通过，仍必须保留实际 n/p50/p95/max，不得伪造 red 或放宽阈值

### Implementation for User Story 2

- [X] T009 [P] [US2] 在 `src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java` 基于 T002 的 endpoint supplier 增加 EDT 内递增的刷新请求序号和轻量状态文本；保留现有 NBRA/smart-mode/project-expiry 主链，仅让最新成功回调替换树，最新失败回调保留旧树并反馈，项目关闭取消不更新 UI，合法空结果显示空状态
- [X] T010 [P] [US2] 在 `src/main/java/cn/luckyh/mqx/action/RefreshMqIndexAction.java` 将刷新 action 图标替换为 IntelliJ Platform 标准刷新图标，保留 `requestRefresh()` → `MqToolWindowService.refresh()` → daemon restart 的现有调用顺序、Action 文本和 Tools 菜单行为
- [X] T011 [US2] 运行 `.\gradlew.bat test --tests "cn.luckyh.mqx.ui.MqTopologyPanelTest" --rerun-tasks` 和 `.\gradlew.bat test --tests "cn.luckyh.mqx.navigation.MqNavigationPerformanceTest" --rerun-tasks --no-parallel`，确认 US1 回归、US2 功能与 50 模块/2,000 Rabbit endpoint 可见刷新 p95 全部通过，并在 `specs/002-sidebar-module-controls/verification.md` 分别记录 focused 与 heavy 证据

**Checkpoint**: User Stories 1 和 2 均可独立验证；没有搜索条件时刷新始终保持用户模块布局并只展示最新结果。

---

## Phase 5: User Story 3 - 在筛选与搜索后保持浏览上下文 (Priority: P3)

**Goal**: 搜索或类型筛选时临时展开匹配模块和分类，清除条件后恢复用户状态；无匹配反馈与双击导航保持正确。

**Independent Test**: 预先展开部分模块并折叠其他模块，分别使用文本搜索和 Consumers/Producers/Topology/Unresolved 筛选，验证匹配 endpoint 无需额外操作即可见、关系子树不被全部展开；清除条件后恢复原状态，无匹配有明确反馈，普通与筛选结果双击到同一源码位置。

### Tests for User Story 3

> **NOTE: T012 必须先失败，且不得通过重新启用“展开所有行”绕过分类层级和状态恢复断言。**

- [X] T012 [US3] 在 `src/test/java/cn/luckyh/mqx/ui/MqTopologyPanelTest.java` 添加文本搜索与 Consumers/Producers/Topology/Unresolved 类型筛选临时展开匹配模块和分类、关系子树保持折叠、筛选期间用户操作不覆盖会话状态、文本或类型筛选后模块可见 endpoint 数量与 RabbitMQ 根节点可见/总数同步更新、清除全部条件恢复完整数量和原模块状态、无匹配状态文本、普通/搜索/筛选 endpoint 双击导航到相同有效源码位置、动态 endpoint 在普通浏览/文本搜索/类型筛选/刷新前后均保留现有 `[dynamic]` 标识与 `resolutionMessage` tooltip 且无法静态解析时不得错误导航的失败测试，并在 `specs/002-sidebar-module-controls/verification.md` 记录 red 结果

### Implementation for User Story 3

- [X] T013 [US3] 在 `src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java` 以“搜索文本非空或类型不是 All”判定临时筛选状态，程序化展开所有匹配模块及其分类层但停止在 endpoint 节点，按当前可见树节点重新计算模块数量和 RabbitMQ 根节点可见/总数，筛选期间暂停写入 `expandedModuleNames`，清除条件后恢复完整数量和会话状态，并区分“当前条件无匹配结果”和“项目没有 RabbitMQ endpoint”状态文本
- [X] T014 [US3] 运行 `.\gradlew.bat test --tests "cn.luckyh.mqx.ui.MqTopologyPanelTest" --rerun-tasks`，确认 US1/US2/US3 全部通过、双击导航无错误目标、动态 endpoint 标识与诊断提示保持可用，并在 `specs/002-sidebar-module-controls/verification.md` 记录命令、测试数量和结果

**Checkpoint**: 三个用户故事全部可独立验收，后续只剩跨故事质量和交付门禁。

---

## Phase 6: Polish & Cross-Cutting Gates

**Purpose**: 证明最小实现、完整回归、兼容性、性能、只读和 broker 隔离，不扩大功能范围。

- [X] T015 检查 `src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java` 与 `src/main/java/cn/luckyh/mqx/action/RefreshMqIndexAction.java` 是否仍可提前返回、是否超过两层条件嵌套、是否出现只调用一次的一行方法、无必要类/接口/线程/锁/缓存/持久化或范围外改动，并将保留或简化结论写入 `specs/002-sidebar-module-controls/verification.md`
- [X] T016 [P] 仅在不改变已批准需求和设计边界的前提下，同步 `specs/002-sidebar-module-controls/plan.md`、`specs/002-sidebar-module-controls/data-model.md`、`specs/002-sidebar-module-controls/contracts/sidebar-ui.md` 和 `specs/002-sidebar-module-controls/quickstart.md` 中已验证的测试命令、验证结果引用、状态文本和受保护范围；若发现实现与 `spec.md`、`plan.md` 或 `tasks.md` 的行为边界不一致，立即停止收尾，先更新对应设计文档并重新执行 `$speckit-analyze`，禁止根据最终实现反向定义需求或设计
- [X] T017 运行 `.\gradlew.bat clean test`，确认全部 parser、matcher、导航、缓存、UI、broker 隔离和唯一 heavy 性能测试通过，并在 `specs/002-sidebar-module-controls/verification.md` 单独记录完整测试结果，不用 focused 结果替代
- [X] T018 运行 `.\gradlew.bat test --tests "*MqBrokerIsolationTest" --rerun-tasks`、`.\gradlew.bat dependencies --configuration runtimeClasspath` 及生产源码网络 API 搜索，在 `specs/002-sidebar-module-controls/verification.md` 记录 RabbitMQ/Kafka/RocketMQ 连接尝试均为 0、运行依赖无三种 MQ client、`src/main/java` 无直接网络 API
- [X] T019 运行 `.\gradlew.bat verifyPluginProjectConfiguration verifyPluginStructure` 和 `.\gradlew.bat verifyPlugin`，在 `specs/002-sidebar-module-controls/verification.md` 分开记录 IDEA 2023.3 与 2026.2.1 的结构/兼容结果；网络下载阻塞不得写成兼容通过或代码失败
- [X] T020 按 `specs/002-sidebar-module-controls/quickstart.md` 在 `build/sidebar-manual-project` 一次性副本完成 Scenario A-C 和 Scenario D 的导航步骤，并在每组沙箱操作前后比较受保护文件 SHA-256；Scenario D 的刷新失败与恢复使用 T006 自动化测试结果作为证据，不在生产插件中增加故障注入入口。在 `specs/002-sidebar-module-controls/verification.md` 分别记录模块折叠、刷新、筛选、双击导航、自动化失败恢复和用户项目非用户改动数 0，禁止直接打开或修改 `src/test/testData/manual-project`
- [X] T021 使用 50 个真实模块和 2,000 个 RabbitMQ endpoint 的只读验收项目，按 `specs/002-sidebar-module-controls/quickstart.md` 完成至少 10 名首次使用者的“5 秒内展开指定模块并定位分类”测试，并在 `specs/002-sidebar-module-controls/verification.md` 逐人记录耗时与结果；至少 9/10 成功才标记 SC-003 通过，未执行则明确标记未验证
- [X] T022 对照 `specs/002-sidebar-module-controls/spec.md` 的 FR-001–FR-015 和 SC-001–SC-007 逐项更新 `specs/002-sidebar-module-controls/verification.md`，重新运行 `git diff --check` 与 `git status --short`，确认只出现 plan 允许的源码、测试和本 feature 文档变更，并把构建、focused、heavy、Verifier、sandbox、usability、生产验证区分为已验证、未验证或环境阻塞

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，先建立可追踪证据与保护基线。
- **Foundational (Phase 2)**: 依赖 T001；只创建共享 UI 测试支架，阻塞所有用户故事测试。
- **US1 (Phase 3)**: 依赖 T002；是最小可交付版本，测试 T003 必须先于实现 T004。
- **US2 (Phase 4)**: 依赖 US1 的模块状态；T006/T007 可并行，T008 完成 red/baseline 门禁后，T009/T010 可并行。
- **US3 (Phase 5)**: 依赖 US1 的会话展开状态和 US2 的状态反馈区域；因继续修改同一面板，不与 US2 并行。
- **Polish (Phase 6)**: 依赖全部目标用户故事；T016 可与 T015 的代码审查并行，最终验证任务按 T017 → T022 顺序收敛证据。

### User Story Dependency Graph

```text
Setup → Foundational → US1 (MVP) → US2 → US3 → Polish
```

### Within Each User Story

- 先写行为测试并保留失败证据，再改生产代码。
- 测试辅助只隔离现有扫描调用，不创建新业务层。
- 同一文件任务串行；不同文件且无未完成依赖时才标记 `[P]`。
- focused 通过后才能进入下一用户故事，后续故事必须保留前序回归。
- 性能失败先记录 n/p50/p95/max 和环境，不得放宽门槛或直接增加索引、线程、锁、缓存。

### Parallel Opportunities

- **US2 tests**: T006 (`MqTopologyPanelTest.java`) 与 T007 (`MqNavigationPerformanceTest.java`) 可并行。
- **US2 implementation**: T009 (`MqTopologyPanel.java`) 与 T010 (`RefreshMqIndexAction.java`) 可在 T008 后并行。
- **Polish**: T016 的设计文档同步可与 T015 的生产代码简化检查并行，但两者完成后再执行完整验证。
- US1 与 US3 都集中修改 `MqTopologyPanel.java`，没有安全的故事内实现并行机会。

---

## Parallel Example: User Story 2

```text
# 第一批：并行写测试
Task T006: 在 src/test/java/cn/luckyh/mqx/ui/MqTopologyPanelTest.java 编写刷新状态与竞态测试
Task T007: 在 src/test/java/cn/luckyh/mqx/navigation/MqNavigationPerformanceTest.java 编写真实规模可见刷新测试

# T008 完成 red/baseline 后，第二批并行实现
Task T009: 在 src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java 实现最新请求和刷新反馈
Task T010: 在 src/main/java/cn/luckyh/mqx/action/RefreshMqIndexAction.java 使用标准刷新图标
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. 完成 T001–T002，建立证据和 UI 测试支架。
2. 完成 T003 并确认当前自动展开行为导致 red。
3. 完成 T004–T005，只交付模块默认折叠和独立展开/折叠。
4. **STOP AND VALIDATE**: 在至少 10 个模块的测试数据上独立演示 US1，不等待刷新状态或筛选恢复。

### Incremental Delivery

1. Setup + Foundational → 测试基础可用且生产行为未变。
2. US1 → 模块浏览 MVP，focused 测试通过。
3. US2 → 可靠刷新、反馈和真实规模 p95，US1 回归通过。
4. US3 → 搜索/筛选状态连续性，US1/US2 回归通过。
5. Polish → 完整测试、兼容性、sandbox、可用性和只读证据收敛。

### Recommended Execution Mode

- 单人或单代理按 T001 → T022 顺序执行最安全，因为三个故事共享 `MqTopologyPanel.java`。
- 仅使用已标记 `[P]` 的两个批次并行；并行任务不得同时编辑同一文件或共同追加 `verification.md`。
- 每个故事 checkpoint 都可以停止并交付独立、可测试的增量。

---

## Notes

- 所有 22 项任务均使用 `- [ ] Tnnn [P?] [US?]` 格式和具体路径。
- `[P]` 只表示文件和依赖允许并行，不表示可以跳过前置 red 门禁。
- 不新增生产类、接口、Manager、Handler、Processor、Context、线程、锁、索引、缓存或持久化。
- 不修改 `MqNavigationService`、parser、matcher、`MqToolWindowService`、`plugin.xml`、依赖、插件版本、`D:\github\interfacex` 或 `D:\github\feignx-plugin`。
- 执行 `$speckit-analyze` 并关闭 HIGH/CRITICAL 不一致后，才能进入 `$speckit-implement`。
