# Tasks: MQ 端点持久缓存

**Input**: Design documents from `/specs/004-persistent-endpoint-cache/`

**Prerequisites**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, `contracts/endpoint-cache-behavior.md`, `quickstart.md`

**Tests**: 本功能遵循 Constitution 的测试先行要求；缓存生命周期、字段保真、项目隔离、损坏回退和量化性能均必须自动验证。

**Organization**: 任务按用户故事组织；生产代码只修改现有 `MqNavigationService`，测试集中在新的缓存测试类，避免引入额外缓存层或无关重构。

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 固定工作区基线和保护范围，确保不覆盖 feature 002/003 的既有修改。

- [X] T001 记录当前分支、`git status --short`、feature 002/003 受保护文件和源文件 SHA-256 基线到 `specs/004-persistent-endpoint-cache/verification.md`
- [X] T002 核对 Java/Gradle 现有忽略规则、运行时依赖和禁止新增项并记录到 `specs/004-persistent-endpoint-cache/verification.md`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 固定现有导航行为与缓存实现边界，作为所有故事的回归基线。

**⚠️ CRITICAL**: 完成本阶段后才能修改生产代码。

- [X] T003 运行现有 `MqNavigationServiceTest` 基线并记录结果到 `specs/004-persistent-endpoint-cache/verification.md`
- [X] T004 核对 `src/main/java/cn/luckyh/mqx/navigation/MqNavigationService.java` 的 CachedValue、PSI/根失效、手动刷新和 parser 容错链路与 `contracts/endpoint-cache-behavior.md` 一致

**Checkpoint**: 现有行为和最小修改边界已固定。

---

## Phase 3: User Story 1 - 重新打开项目时快速恢复端点 (Priority: P1) 🎯 MVP

**Goal**: 首次读取优先从 IDE 项目级系统缓存恢复完整或合法空快照，保持字段、排序和源码导航一致。

**Independent Test**: 新服务实例加载 2,000 个有效缓存 endpoint 后无需 parser 扫描即可读取完整列表，5 次预热后 20 次恢复 nearest-rank p95 不超过 500ms；合法空缓存保持为空。

### Tests for User Story 1

- [X] T005 [US1] 先在 `src/test/java/cn/luckyh/mqx/navigation/MqNavigationCacheTest.java` 添加缓存缺失、有效非空、合法空、不可修改/排序/去重、源码指针、RabbitMQ/Kafka/RocketMQ 各角色至少 5 个全字段样例和 2,000 endpoint p95 测试
- [X] T006 [US1] 运行 `MqNavigationCacheTest` 并将缺少持久恢复能力的预期失败证据记录到 `specs/004-persistent-endpoint-cache/verification.md`

### Implementation for User Story 1

- [X] T007 [US1] 在 `src/main/java/cn/luckyh/mqx/navigation/MqNavigationService.java` 实现平台项目级 CacheState/CachedEndpointState、首次整批恢复和成功完整扫描后的状态替换
- [X] T008 [US1] 运行 `MqNavigationCacheTest` 的 US1 场景并记录字段一致率、导航恢复和 2,000 endpoint p95 到 `specs/004-persistent-endpoint-cache/verification.md`

**Checkpoint**: 有效缓存和合法空缓存可独立恢复，US1 可单独验收。

---

## Phase 4: User Story 2 - 刷新后更新缓存 (Priority: P1)

**Goal**: 手动刷新及会话内 PSI/项目根失效绕过旧持久缓存，完整扫描后整体替换最近成功状态。

**Independent Test**: 先恢复旧目的地，再修改测试源码并触发手动刷新或 PSI 失效；当前实例和下一服务实例都只返回最新结果，不完整扫描不覆盖旧状态。

### Tests for User Story 2

- [X] T009 [US2] 在 `src/test/java/cn/luckyh/mqx/navigation/MqNavigationCacheTest.java` 添加手动刷新绕过、合法空刷新、会话内 PSI 失效、下一实例恢复和不完整扫描保留旧状态测试
- [X] T010 [US2] 运行新增 US2 测试并将刷新更新缺口的预期失败证据记录到 `specs/004-persistent-endpoint-cache/verification.md`

### Implementation for User Story 2

- [X] T011 [US2] 在 `src/main/java/cn/luckyh/mqx/navigation/MqNavigationService.java` 实现手动刷新禁用再次恢复、会话失效后重新扫描及仅完整扫描写入持久状态
- [X] T012 [US2] 连续验证成功刷新、合法空刷新和会话内源码变化各至少 5 次并记录到 `specs/004-persistent-endpoint-cache/verification.md`

**Checkpoint**: 缓存生命周期与现有刷新和失效入口协同，US1 与 US2 均可独立验收。

---

## Phase 5: User Story 3 - 无效缓存安全降级 (Priority: P2)

**Goal**: 版本、字段、源码位置或项目边界无效时放弃整份缓存并回退现有扫描，不展示部分或跨项目结果。

**Independent Test**: 版本不匹配、必要字段损坏、源码文件删除、偏移越界和项目外源码各重复至少 5 次，均完整回退、无未处理异常且不发布部分缓存。

### Tests for User Story 3

- [X] T013 [US3] 在 `src/test/java/cn/luckyh/mqx/navigation/MqNavigationCacheTest.java` 添加版本、必要字段、枚举、删除文件、越界偏移、部分有效和项目外源码的整批回退测试
- [X] T014 [US3] 运行新增 US3 测试并将无效状态处理缺口的预期失败证据记录到 `specs/004-persistent-endpoint-cache/verification.md`

### Implementation for User Story 3

- [X] T015 [US3] 在 `src/main/java/cn/luckyh/mqx/navigation/MqNavigationService.java` 完成版本、必要字段、枚举、当前项目 source content 与全部源码位置校验及整批降级
- [X] T016 [US3] 对每类无效缓存至少重复 5 次，并对两个项目各执行至少 10 次恢复/刷新隔离验证，记录零部分展示、零异常、零跨项目泄漏到 `specs/004-persistent-endpoint-cache/verification.md`

**Checkpoint**: 三条用户故事全部独立可用，损坏缓存不会泄漏或破坏现有扫描。

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 完成回归、兼容、隔离、源码安全和需求追踪门禁。

- [X] T017 [P] 运行 `MqNavigationServiceTest` 与 `MqTopologyPanelTest` 回归现有搜索、筛选、模块折叠、动态提示、匹配、双击导航、索引等待、项目关闭和最新刷新结果并记录到 `specs/004-persistent-endpoint-cache/verification.md`
- [X] T018 [P] 运行 `MqNavigationPerformanceTest` 并记录 50 模块、2,000 endpoint、40 次刷新 p95 到 `specs/004-persistent-endpoint-cache/verification.md`
- [X] T019 运行 `clean test --no-parallel` 完整测试并记录到 `specs/004-persistent-endpoint-cache/verification.md`
- [X] T020 [P] 运行 `MqBrokerIsolationTest`、runtimeClasspath 依赖和网络/数据库关键字扫描并记录到 `specs/004-persistent-endpoint-cache/verification.md`
- [X] T021 [P] 运行 `verifyPluginProjectConfiguration`、`verifyPluginStructure` 和 `verifyPlugin` 并记录兼容结果到 `specs/004-persistent-endpoint-cache/verification.md`
- [X] T022 对测试沙箱受保护源码做前后 SHA-256 比较并记录项目文件零改动或不可审计项到 `specs/004-persistent-endpoint-cache/verification.md`
- [X] T023 检查 `src/main/java/cn/luckyh/mqx/navigation/MqNavigationService.java` 无多余嵌套、抽象、线程、锁或第二运行时缓存并运行 `git diff --check`
- [X] T024 核对 FR-001–FR-017、SC-001–SC-007 与测试证据，更新 `specs/004-persistent-endpoint-cache/verification.md` 并确认 feature 004 之外没有新增改动

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，先固定工作区和哈希基线。
- **Foundational (Phase 2)**: 依赖 Setup，阻塞生产修改。
- **US1 (Phase 3)**: 依赖 Foundational，提供缓存恢复 MVP。
- **US2 (Phase 4)**: 依赖 US1 的状态模型，扩展刷新与会话失效生命周期。
- **US3 (Phase 5)**: 依赖 US1 的恢复入口，补齐整批校验与项目隔离。
- **Polish (Phase 6)**: 依赖三条故事实现完成。

### User Story Dependencies

- **US1 (P1)**: 首个可交付增量，无其他故事依赖。
- **US2 (P1)**: 复用 US1 的持久状态和首次恢复入口，但可通过刷新生命周期测试独立验收。
- **US3 (P2)**: 复用 US1 的恢复入口，但可通过无效缓存回退测试独立验收。

### Within Each User Story

- 先写测试并确认失败，再修改生产实现。
- 同一文件任务顺序执行，不并行修改 `MqNavigationService.java` 或 `MqNavigationCacheTest.java`。
- 每条故事完成后先运行其独立测试，再进入下一阶段。
- 完整扫描状态替换先于刷新和损坏场景扩展。

### Parallel Opportunities

- 实现完成后，T017、T018、T020、T021 涉及不同验证门禁，可在环境资源允许时并行。
- 用户故事实现共享同一生产与测试文件，为避免冲突按 US1 → US2 → US3 顺序执行。

## Parallel Example: Cross-Cutting Validation

```text
Task T017: navigation and sidebar regression tests
Task T018: heavy refresh performance test
Task T020: broker and dependency isolation checks
Task T021: plugin structure and compatibility verification
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. 完成工作区与现有行为基线。
2. 先写缓存命中、合法空缓存、字段保真和性能测试。
3. 只在现有服务内实现平台持久状态和首次恢复。
4. 验证 US1 后再扩展刷新和损坏回退。

### Incremental Delivery

1. Setup + Foundational → 固定保护范围与现有行为。
2. US1 → 有效缓存快速恢复。
3. US2 → 手动刷新和会话失效更新缓存。
4. US3 → 无效缓存整批降级和项目隔离。
5. Polish → 完整回归、性能、兼容和安全证据。

## Notes

- `[P]` 仅表示验证命令可独立执行，不表示并行修改同一文件。
- 不新增依赖、数据库、线程、锁、监听器、缓存 UI 或顶层生产类。
- 不修改 parser、matcher、刷新 action、侧边栏 UI、运行时模型或既有性能门槛。
- 所有任务完成后必须标记为 `[X]`，并在 `verification.md` 区分 verified、unverified 和 environment blocked。
