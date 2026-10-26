# Tasks: RabbitMQ 刷新进度提示

**Input**: Design documents from `/specs/003-refresh-progress-indicator/`

**Prerequisites**: `plan.md`、`spec.md`、`research.md`、`data-model.md`、`contracts/sidebar-progress-ui.md`、`quickstart.md`

**Tests**: 本功能遵循 Constitution 的测试先行要求。每个新增行为先进入现有 `MqTopologyPanelTest`，记录真实 red 证据后再修改生产代码；已有行为直接通过时必须如实记录，不伪造失败。

**Organization**: 任务按三个用户故事组织。US1 先交付可独立验收的进度提示 MVP；US2 完成终态和连续刷新生命周期；US3 验证刷新期间已有浏览操作不受影响。

## Phase 1: Setup (Shared Evidence)

**Purpose**: 建立本 feature 的变更前证据、保护范围和结果记录位置。

- [X] T001 在 `specs/003-refresh-progress-indicator/verification.md` 创建 Pre-change、Focused UI、Heavy Regression、Full Test、Plugin Verifier、Sandbox、Usability、Source Safety、Broker Isolation 九个证据区；记录实现前 `git status --short`、`src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java` 与 `src/test/java/cn/luckyh/mqx/ui/MqTopologyPanelTest.java` 的 SHA-256，并列出 plan 中受保护的生产文件、测试文件和只读参考目录

---

## Phase 2: Foundational (Blocking Baseline)

**Purpose**: 确认现有侧边栏测试基线和可复用测试接缝，避免把历史失败归因于本功能。

**⚠️ CRITICAL**: 未完成本阶段前不得添加进度行为测试或修改生产代码。

- [X] T002 运行 `.\gradlew.bat test --tests "cn.luckyh.mqx.ui.MqTopologyPanelTest" --rerun-tasks`，在 `specs/003-refresh-progress-indicator/verification.md` 记录现有测试数量和结果，并确认 `src/test/java/cn/luckyh/mqx/ui/MqTopologyPanelTest.java` 已有受控 endpoint supplier、EDT 执行、状态组件查找和反向完成辅助可直接复用，不新增测试框架或生产接缝

**Checkpoint**: 现有 focused UI 基线通过，测试支架可直接覆盖进度状态。

---

## Phase 3: User Story 1 - 直观看到刷新仍在进行 (Priority: P1) 🎯 MVP

**Goal**: 项目可分析且刷新正在执行时，在现有文字旁显示活动的不确定进度条；最新成功结果完成后提示停止并隐藏。

**Independent Test**: 使用阻塞 supplier 启动一次持续中的刷新，在放行前确认“正在刷新 RabbitMQ 端点”、命名进度组件可见且为不确定模式；放行成功结果后确认进度组件隐藏且停止活动。

### Tests for User Story 1

- [X] T003 [US1] 在 `src/test/java/cn/luckyh/mqx/ui/MqTopologyPanelTest.java` 先添加可递归定位名为 `mqTopologyRefreshProgress` 的 `JProgressBar` 断言；增加项目可分析时刷新调用返回后、阻塞 supplier 放行前进度条可见且 `isIndeterminate=true`、不绘制百分比字符串、旧树仍可见，最新成功或失败结果后进度条隐藏且 `isIndeterminate=false` 的失败测试；同时加入等待索引隐藏、两个请求反向完成不提前关闭提示和固定窄宽度状态组件不重叠的首组失败断言；再执行 5 次预热并采集 20 次持续至少 500ms 的显示延迟样本，以 nearest-rank 断言 p95 不超过 300ms 并输出 n、p50、p95、max
- [X] T004 [US1] 运行 `.\gradlew.bat test --tests "cn.luckyh.mqx.ui.MqTopologyPanelTest" --rerun-tasks`，确认 T003 命中缺少进度组件或活动状态的真实 red 断言，并在 `specs/003-refresh-progress-indicator/verification.md` 记录命令、测试数量和失败详情，不得先修改生产代码或伪造 red

### Implementation for User Story 1

- [X] T005 [US1] 在 `src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java` 使用 `JProgressBar` 将现有 `statusLabel` 包装到始终存在的底部状态行，并用按进度条平台首选尺寸固定的本地占位面板保证隐藏前后状态行与树区域高度不变；进度条命名为 `mqTopologyRefreshProgress`，初始隐藏且非不确定模式，不显示字符串；增加一个被开始和结束路径复用的私有进度状态方法，在项目可分析的最新刷新提交前激活，在最新成功回调调用 `replaceTree()` 前及最新失败反馈前停止并隐藏，等待索引时保持隐藏，且不修改 tree model、NBRA 或刷新请求序号规则
- [X] T006 [US1] 运行 `.\gradlew.bat test --tests "cn.luckyh.mqx.ui.MqTopologyPanelTest" --rerun-tasks`，确认 US1 新测试和全部既有侧边栏测试通过，并在 `specs/003-refresh-progress-indicator/verification.md` 记录 green 结果、测试数量、5 次预热、20 次样本的 n/p50/p95/max、p95 不超过 300ms 以及成功/失败均无活动提示残留的证据

**Checkpoint**: US1 可独立演示；正常刷新期间可见进度条，成功后无残留。

---

## Phase 4: User Story 2 - 在各种刷新结果下正确结束提示 (Priority: P2)

**Goal**: 失败、合法空结果、等待索引和连续反向完成时，进度提示只跟随最新请求并与现有最终反馈一致。

**Independent Test**: 使用受控成功、失败、dumb mode 和至少 10 组双请求反向完成样例，确认进度条在所有最终状态隐藏且停止，较早回调不提前关闭或重新激活最新提示。

### Tests for User Story 2

- [X] T007 [US2] 在 `src/test/java/cn/luckyh/mqx/ui/MqTopologyPanelTest.java` 对成功、合法空结果和失败各执行至少 5 次受控结果，对索引等待执行至少 5 次受控状态，断言对应状态文字出现时进度条状态正确且最终活动提示残留为 0；将现有反向完成测试扩展为至少 10 组，逐组断言较早回调不提前关闭最新活动进度，也不在最新请求结束后重新激活进度或覆盖树
- [X] T008 [US2] 运行 `.\gradlew.bat test --tests "cn.luckyh.mqx.ui.MqTopologyPanelTest" --rerun-tasks`，确认 US1/US2 全部通过，在 `specs/003-refresh-progress-indicator/verification.md` 分别记录四类状态各至少 5 次的正确率与残留数，以及 10 组反向完成的提前关闭、重新激活和旧树覆盖次数；若既有最新请求规则已直接通过，保留实际 green 证据，不得为制造 red 修改生产逻辑

**Checkpoint**: US1 和 US2 均可独立验证；全部刷新结果和连续请求不会留下或错误切换进度提示。

---

## Phase 5: User Story 3 - 刷新期间继续浏览已有结果 (Priority: P3)

**Goal**: 进度提示不遮挡或阻断旧树、模块操作、文本搜索和类型筛选，也不被这些操作提前清除。

**Independent Test**: 先展示已有模块，再阻塞下一次刷新；刷新期间执行模块展开/折叠、文本搜索和类型筛选，确认操作生效，状态文字和进度条保持刷新中，最终结果应用后原有状态规则仍正确。

### Tests for User Story 3

- [X] T009 [US3] 在 `src/test/java/cn/luckyh/mqx/ui/MqTopologyPanelTest.java` 添加刷新保持进行时展开/折叠已有模块、输入文本搜索并切换 endpoint 类型筛选的回归测试；将面板设为固定窄宽度并执行布局，记录进度出现前、出现中和消失后的树滚动区高度与状态组件边界；断言树仍可操作、其他模块状态不变、树区域高度不变、状态文字和进度条不重叠、进度条持续可见且不确定，放行最新结果后模块状态、数量、筛选、动态提示和双击导航沿用现有规则
- [X] T010 [US3] 运行 `.\gradlew.bat test --tests "cn.luckyh.mqx.ui.MqTopologyPanelTest" --rerun-tasks`，确认 US1/US2/US3 全部通过；在 `specs/003-refresh-progress-indicator/verification.md` 记录固定窄宽度、树区域高度和组件边界结果；如果 T009 暴露无法由已批准状态行设计满足的行为，停止收尾并先修正 `specs/003-refresh-progress-indicator/plan.md` 后重新执行 `$speckit-analyze`

**Checkpoint**: 三个用户故事全部可独立验收，新增进度提示不破坏现有侧边栏交互。

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 完成最小实现审查、真实规模回归、兼容性、安全和人工证据。

- [X] T011 检查 `src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java` 与 `src/test/java/cn/luckyh/mqx/ui/MqTopologyPanelTest.java` 是否仍可提前返回、是否超过两层条件嵌套、是否新增只调用一次的一行方法、无必要类/接口/线程/计时器/锁/缓存/持久化或范围外改动；确认私有进度状态方法确实被多个分支复用，现有 `expireWith(project)` 与 disposed guard 未改变且项目关闭后不访问 UI，并将保留或简化结论写入 `specs/003-refresh-progress-indicator/verification.md`
- [X] T012 [P] 仅在不改变已批准需求和设计边界的前提下，同步 `specs/003-refresh-progress-indicator/plan.md`、`specs/003-refresh-progress-indicator/data-model.md`、`specs/003-refresh-progress-indicator/contracts/sidebar-progress-ui.md` 和 `specs/003-refresh-progress-indicator/quickstart.md` 中实际组件名、精确状态矩阵和验证结果引用；若实现与 `spec.md` 或 `plan.md` 存在范围偏移，停止实现并重新执行 `$speckit-analyze`，不得根据代码反向定义需求
- [X] T013 运行 `.\gradlew.bat test --tests "cn.luckyh.mqx.navigation.MqNavigationPerformanceTest" --rerun-tasks --no-parallel`，在 `specs/003-refresh-progress-indicator/verification.md` 记录现有 50 模块、2,000 RabbitMQ endpoint、40 次用户可见刷新样本和 nearest-rank p95，确认严格小于 3 秒；不得修改 `src/test/java/cn/luckyh/mqx/navigation/MqNavigationPerformanceTest.java` 或放宽阈值
- [X] T014 运行 `.\gradlew.bat clean test`，确认全部 parser、matcher、导航、缓存、UI、broker 隔离和唯一 heavy 性能测试通过，并在 `specs/003-refresh-progress-indicator/verification.md` 单独记录完整测试数量、结果和耗时，不得用 focused 结果替代
- [X] T015 运行 `.\gradlew.bat test --tests "*MqBrokerIsolationTest" --rerun-tasks`、`.\gradlew.bat dependencies --configuration runtimeClasspath` 及 `src/main/java` 网络 API 搜索，在 `specs/003-refresh-progress-indicator/verification.md` 记录 RabbitMQ/Kafka/RocketMQ 连接尝试均为 0、运行依赖无三种 MQ client、生产源码无直接网络 API
- [X] T016 运行 `.\gradlew.bat verifyPluginProjectConfiguration verifyPluginStructure` 和 `.\gradlew.bat verifyPlugin`，在 `specs/003-refresh-progress-indicator/verification.md` 分开记录 IDEA 2023.3 与 2026.2.1 的结构和兼容结果；网络下载阻塞不得写成兼容通过或代码失败
- [X] T017 按 `specs/003-refresh-progress-indicator/quickstart.md` 在 `build/sidebar-progress-manual-project` 一次性副本完成 Scenario A-C，并在操作前后比较受保护文件 SHA-256；在 `specs/003-refresh-progress-indicator/verification.md` 分别记录进度可见/结束、固定窄宽度下树区域高度与组件边界、刷新中浏览、等待索引自动化、失败恢复自动化、连续刷新和用户项目非用户改动数，快速刷新无法稳定目视或无可审计 GUI 操作时必须标记未验证，禁止直接打开或修改 `src/test/testData/manual-project`
- [X] T018 使用刷新持续超过 500ms 的相同只读验收项目，按 `specs/003-refresh-progress-indicator/quickstart.md` 完成至少 10 名首次使用者的“无需说明判断刷新仍在进行”测试，并在 `specs/003-refresh-progress-indicator/verification.md` 逐人记录耗时与结果；至少 9/10 在 1 秒内正确判断才标记 SC-005 通过，未执行则明确标记未验证
- [X] T019 对照 `specs/003-refresh-progress-indicator/spec.md` 的 FR-001–FR-011 和 SC-001–SC-006 逐项更新 `specs/003-refresh-progress-indicator/verification.md`，重新运行 `git diff --check` 与 `git status --short`，确认只出现 `src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java`、`src/test/java/cn/luckyh/mqx/ui/MqTopologyPanelTest.java` 和本 feature 文档变更，并把 focused、heavy、完整测试、Verifier、sandbox、usability 与生产验证分别标记为已验证、未验证或环境阻塞

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，可立即开始。
- **Foundational (Phase 2)**: 依赖 T001；完成前不得添加测试或修改生产代码。
- **User Story 1 (Phase 3)**: 依赖 T002；按 T003 → T006 严格执行测试先行，是 MVP。
- **User Story 2 (Phase 4)**: 依赖 US1 的进度组件和状态方法；按 T007 → T008 执行。
- **User Story 3 (Phase 5)**: 依赖 US1 的可见进度组件；建议在 US2 后执行，按 T009 → T010 验证兼容性。
- **Polish (Phase 6)**: 依赖三个目标故事；T011 与 T012 可并行，最终门禁按 T013 → T019 收敛证据。

### User Story Dependencies

- **US1 (P1)**: Foundational 完成后即可实施，形成独立可演示 MVP。
- **US2 (P2)**: 复用 US1 的进度组件，增加失败和连续请求生命周期；不得重写 US1 的状态行。
- **US3 (P3)**: 复用 US1 的进度组件和既有 `refreshing` guard；不要求新增生产行为，重点验证不回归。

### Within Each User Story

- 先写测试并运行，保存真实 red 或已有 green 证据。
- 测试失败后才修改对应生产行为。
- 同一 `MqTopologyPanelTest.java` 或 `MqTopologyPanel.java` 上的任务必须串行。
- 每个故事完成后运行 focused UI 全量回归，再进入下一优先级。

### Parallel Opportunities

- 三个用户故事共享同一生产文件和测试文件，故事内部不存在安全代码并行机会。
- 全部故事完成后，T013 的代码简化审查可与 T014 的设计文档同步并行。
- Gradle 测试、instrumentation 和 Plugin Verifier 不并行执行，避免共享构建目录竞争。

---

## Parallel Examples

### User Story 1

US1 的 T003–T006 同时修改或验证同一测试/生产文件，必须按 `T003 → T004 → T005 → T006` 串行执行，无安全并行任务。

### User Story 2

US2 复用 US1 与现有最新请求实现，T007 修改测试后由 T008 统一验证，必须按 `T007 → T008` 串行执行。

### User Story 3

US3 仅增加交互和布局回归测试，必须按 `T009 → T010` 串行执行；可在 US1 完成后由独立工作区执行，但当前单工作区建议在 US2 后运行。

### Cross-Cutting

```text
Task T011: 审查 MqTopologyPanel.java 与 MqTopologyPanelTest.java 的最小性
Task T012: 同步 specs/003-refresh-progress-indicator 下的设计与验证文档
```

上述两项修改不同文件，可在三个故事完成后并行。

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. 完成 T001–T002，保存基线。
2. 完成 T003–T004，获得真实 red。
3. 完成 T005，加入最小进度组件以及成功、失败生命周期。
4. 完成 T006，独立验证 US1。
5. 停止并检查 MVP；成功和失败终态均不得残留活动提示，不提前实现连续请求或额外交互范围。

### Incremental Delivery

1. Setup + Foundational → 基线和保护范围明确。
2. US1 → 正常刷新显示和结束进度提示。
3. US2 → 失败、空结果、等待索引和连续刷新生命周期完整。
4. US3 → 刷新期间浏览、搜索和筛选无回归。
5. Polish → heavy、完整测试、兼容性、安全、sandbox 和可用性证据收敛。

### Single-Agent Strategy

单人或单代理按 T001 → T019 顺序执行最安全，因为三个故事共享 `MqTopologyPanel.java` 与 `MqTopologyPanelTest.java`。不得为了并行创建重复测试类、进度控制器或中间层。

---

## Notes

- `[P]` 仅表示不同文件且无未完成依赖的安全并行任务。
- `[US1]`、`[US2]`、`[US3]` 与 `spec.md` 的用户故事一一对应。
- 每项任务包含准确文件路径；运行命令的结果统一写入 `verification.md`。
- 不提交代码或创建分支，除非用户明确要求。
- 不修改 `<reference-project>`；该目录仅为只读参考。
- 未执行的 sandbox、首次使用者或生产验证必须标记未验证。
