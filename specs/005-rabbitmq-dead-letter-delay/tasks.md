# Tasks: RabbitMQ 死信与延迟队列拓扑

**Input**: Design documents from `/specs/005-rabbitmq-dead-letter-delay/`

**Prerequisites**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, `contracts/special-queue-topology.md`, `quickstart.md`

**Tests**: 本功能遵循 Constitution 的测试先行要求；parser、死信关系、TTL 分类、缓存保真、环路、性能、broker 隔离和兼容性均必须验证。

**Organization**: 任务按用户故事组织。生产代码只扩展现有 model/parser/cache/UI，不新增依赖、顶层生产类、关系服务或独立窗口。

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 固定当前脏工作区、受保护范围与构建配置，防止覆盖 feature 002–004 的既有修改。

- [X] T001 记录当前分支、`git status --short`、本 feature 允许文件和生产源文件 SHA-256 基线到 `specs/005-rabbitmq-dead-letter-delay/verification.md`
- [X] T002 核对 `.gitignore`、Java 17、Gradle Wrapper、现有依赖和禁止新增项并记录到 `specs/005-rabbitmq-dead-letter-delay/verification.md`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 固定普通 RabbitMQ、缓存和侧边栏当前行为，作为特殊队列增量修改的回归基线。

**⚠️ CRITICAL**: 完成本阶段后才能修改生产代码。

- [X] T003 运行现有 `RabbitMqEndpointParserTest`、`MqNavigationCacheTest` 和 `MqTopologyPanelTest` 基线并记录到 `specs/005-rabbitmq-dead-letter-delay/verification.md`
- [X] T004 核对 `src/main/java/cn/luckyh/mqx/model/MqDestination.java`、`src/main/java/cn/luckyh/mqx/parser/RabbitMqEndpointParser.java`、`src/main/java/cn/luckyh/mqx/navigation/MqNavigationService.java`、`src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java` 与 `specs/005-rabbitmq-dead-letter-delay/contracts/special-queue-topology.md` 的最小修改边界

**Checkpoint**: 普通 endpoint、缓存 v1 和现有拓扑树基线已固定。

---

## Phase 3: User Story 1 - 识别死信转发关系 (Priority: P1) 🎯 MVP

**Goal**: 从单参数或参数集合读取 DLX/路由键，并在现有 Queue 节点展示真实 Exchange、Binding 和目标 Queue 关系。

**Independent Test**: 只读样例包含单目标、多目标、缺失目标、跨模块、默认交换机和环路；所有静态关系与源码一致，动态值不虚构目标，各关系节点可导航。

### Tests for User Story 1

- [X] T005 [P] [US1] 扩展 `src/test/testData/stubs/org/springframework/amqp/core/QueueBuilder.java` 并在 `src/test/java/cn/luckyh/mqx/parser/RabbitMqEndpointParserTest.java` 添加 Map 与单参数各至少 10 个静态样例，以及缺失路由键、动态 DLX、无关参数和至少 5 个损坏特殊声明隔离样例
- [X] T006 [P] [US1] 新增 `src/test/java/cn/luckyh/mqx/ui/MqSpecialQueueTopologyPanelTest.java`，对完整单目标、多目标、缺失 Binding、缺失 Queue 和未指定路由键各至少 5 个样例，并覆盖跨模块、默认交换机、双击导航和环路一层展示
- [X] T007 [US1] 运行 US1 parser/UI 测试并将缺少特殊队列模型与关系展示的预期失败证据记录到 `specs/005-rabbitmq-dead-letter-delay/verification.md`

### Implementation for User Story 1

- [X] T008 [US1] 在 `src/main/java/cn/luckyh/mqx/model/MqDestination.java` 增加内部不可变 RabbitQueueSettings、死信来源分类和字段校验
- [X] T009 [US1] 在 `src/main/java/cn/luckyh/mqx/parser/RabbitMqEndpointParser.java` 实现 builder 链、`withArgument`、局部 Map 线性 `put`/`Map.of` 的 DLX 与 dead-letter key 静态解析和动态诊断
- [X] T010 [US1] 在 `src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java` 复用现有 endpoint 和 matcher 展示 Dead-letter Exchange/Binding/Queue 一层关系及诊断 tooltip
- [X] T011 [US1] 运行 US1 parser/UI focused tests 并记录单目标、多目标、缺失目标、导航和环路结果到 `specs/005-rabbitmq-dead-letter-delay/verification.md`

**Checkpoint**: 死信来源和完整静态目标关系可独立验收，不依赖 TTL 或缓存扩展。

---

## Phase 4: User Story 2 - 识别 TTL 延迟队列 (Priority: P1)

**Goal**: 区分仅 TTL、仅 DLX 和 TTL+DLX，并展示静态时长或动态/无效诊断。

**Independent Test**: 用户示例的静态 TTL+DLX 被识别为 delay；仅 TTL 不生成后续路径；TTL 为 0 保留；动态、负数和非数字 TTL 保留队列且不显示错误确定值。

### Tests for User Story 2

- [X] T012 [US2] 在 `src/test/java/cn/luckyh/mqx/parser/RabbitMqEndpointParserTest.java` 为 TTL+DLX、仅 TTL、仅 DLX 各添加至少 10 个分类样例，并为 TTL=0、动态/负数/非数字 TTL、分支 Map 和 Map.of 各添加至少 5 个降级样例
- [X] T013 [US2] 在 `src/test/java/cn/luckyh/mqx/ui/MqSpecialQueueTopologyPanelTest.java` 添加 delay/dead-letter/TTL 摘要、动态诊断和包含至少 20 个特殊队列且 30 秒内完成定位的搜索测试
- [X] T014 [US2] 运行新增 US2 测试并将 TTL 分类与摘要缺口的预期失败证据记录到 `specs/005-rabbitmq-dead-letter-delay/verification.md`

### Implementation for User Story 2

- [X] T015 [US2] 在 `src/main/java/cn/luckyh/mqx/parser/RabbitMqEndpointParser.java` 完成非负 TTL 常量求值、条件/循环降级和 Queue 分类所需配置状态
- [X] T016 [US2] 在 `src/main/java/cn/luckyh/mqx/model/MqDestination.java` 与 `src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java` 完成 delay/TTL 摘要、搜索文本和动态属性 tooltip 展示
- [X] T017 [US2] 运行 US2 parser/UI focused tests 并记录三类队列区分、TTL=0 和动态/无效诊断结果到 `specs/005-rabbitmq-dead-letter-delay/verification.md`

**Checkpoint**: 死信来源与 TTL 延迟队列均可独立识别和浏览。

---

## Phase 5: User Story 3 - 在现有侧边栏中追踪特殊队列 (Priority: P2)

**Goal**: 刷新和 cache v2 无损保存特殊队列设置，现有模块、搜索、筛选和导航行为保持一致。

**Independent Test**: cache v2 往返后全部特殊字段和源码位置一致；cache v1 与损坏设置整批回退；刷新后恢复最新设置；普通三种 MQ 结果无变化。

### Tests for User Story 3

- [X] T018 [US3] 在 `src/test/java/cn/luckyh/mqx/navigation/MqNavigationCacheTest.java` 添加 RabbitQueueSettings 全字段往返、cache v1 回退、无效 TTL/null diagnostic/标记值不一致回退，并对每类特殊队列执行至少 10 次刷新与 10 次重新打开恢复测试
- [X] T019 [US3] 运行新增缓存测试并将版本与字段保真缺口的预期失败证据记录到 `specs/005-rabbitmq-dead-letter-delay/verification.md`

### Implementation for User Story 3

- [X] T020 [US3] 在 `src/main/java/cn/luckyh/mqx/navigation/MqNavigationService.java` 将缓存升级为 v2，扁平保存、校验和整批恢复 RabbitQueueSettings
- [X] T021 [US3] 运行 `MqNavigationCacheTest`、`MqTopologyPanelTest` 和 `MqSpecialQueueTopologyPanelTest`，记录缓存往返、旧版本回退、刷新、搜索、筛选和导航结果到 `specs/005-rabbitmq-dead-letter-delay/verification.md`

**Checkpoint**: 三条用户故事全部可用，特殊队列与现有侧边栏生命周期一致。

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 完成回归、性能、隔离、兼容、源码安全和需求追踪门禁。

- [X] T022 [P] 运行 `MqNavigationServiceTest`、`MqEndpointMatcherTest` 和全部 parser tests 并记录到 `specs/005-rabbitmq-dead-letter-delay/verification.md`
- [X] T023 [P] 运行 `MqNavigationPerformanceTest`、`MqBrokerIsolationTest`、runtimeClasspath 与网络 API 扫描并记录性能和 broker 零连接到 `specs/005-rabbitmq-dead-letter-delay/verification.md`
- [X] T024 运行 `clean test --no-parallel` 完整测试并记录到 `specs/005-rabbitmq-dead-letter-delay/verification.md`
- [X] T025 运行 `verifyPluginProjectConfiguration`、`verifyPluginStructure` 和 `verifyPlugin`，记录 IDEA 2023.3/2026.2.1 兼容结果到 `specs/005-rabbitmq-dead-letter-delay/verification.md`
- [X] T026 检查四个生产文件无多余接口、类、嵌套、线程、锁、运行时 MQ 调用或范围外重构，并运行 `git diff --check` 与目标文件 diff
- [X] T027 核对 FR-001–FR-019、SC-001–SC-008 与测试证据，完成 `specs/005-rabbitmq-dead-letter-delay/verification.md` 并确认既有未提交工作未被覆盖

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，先固定脏工作区和哈希基线。
- **Foundational (Phase 2)**: 依赖 Setup，阻塞生产修改。
- **US1 (Phase 3)**: 依赖 Foundational，提供死信关系 MVP。
- **US2 (Phase 4)**: 复用 US1 的队列设置值，但通过 TTL 分类场景独立验收。
- **US3 (Phase 5)**: 依赖 US1/US2 的最终设置字段，完成缓存与侧边栏生命周期。
- **Polish (Phase 6)**: 依赖全部故事完成。

### User Story Dependencies

- **US1 (P1)**: 首个可交付增量，无其他故事依赖。
- **US2 (P1)**: 使用 US1 建立的 RabbitQueueSettings，但不依赖死信目标 UI 才能验证 TTL 分类。
- **US3 (P2)**: 依赖最终数据字段进行缓存，但可通过缓存恢复测试独立验收。

### Within Each User Story

- 测试必须先编写并确认失败，再修改对应生产文件。
- 同一文件任务顺序执行；不同测试文件可并行准备。
- parser 先产生完整设置，UI 和 cache 再消费同一不可变值。
- 每条故事完成后运行 focused tests，再进入下一阶段。

### Parallel Opportunities

- T005 与 T006 修改不同测试文件，可并行准备。
- 实现完成后 T022 与 T023 使用不同验证门禁，可在资源允许时并行。
- 生产文件存在数据依赖，T008 → T009 → T010 和 T020 保持顺序，避免并行冲突。

## Parallel Example: User Story 1

```text
Task T005: parser/stub tests for QueueBuilder special arguments
Task T006: sidebar tests for dead-letter relationships and navigation
```

## Implementation Strategy

### MVP First (User Story 1 Only)

1. 固定工作区和普通拓扑基线。
2. 先写 DLX parser 与关系 UI 测试并获得 red。
3. 增加一个内部队列设置值，扩展现有 parser 和树关系。
4. 验证单目标、多目标、缺失目标、导航和环路后再加入 TTL。

### Incremental Delivery

1. Setup + Foundational → 固定保护范围。
2. US1 → 死信来源与目标关系。
3. US2 → TTL 延迟分类与诊断。
4. US3 → cache v2、刷新和现有侧边栏一致性。
5. Polish → 完整回归、性能、隔离和双版本兼容。

## Notes

- `[P]` 只用于不同文件或独立验证，不并行修改同一源文件。
- 不新增依赖、endpoint 角色、顶层生产类、关系服务、线程、锁、监听器或独立 UI。
- 不支持 `x-delayed-message`、消息重试、重放或 broker 管理功能。
- 所有任务完成后标记为 `[X]`，`verification.md` 必须区分 verified、unverified 和 environment blocked。
