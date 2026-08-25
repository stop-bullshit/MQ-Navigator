# Tasks: 修复侧边栏源码导航线程异常

**Input**: Design documents from `/specs/006-fix-sidebar-read-access/`

**Prerequisites**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, `contracts/sidebar-source-navigation.md`, `quickstart.md`

**Tests**: 本功能遵循 Constitution 的测试先行要求；普通节点、特殊关系节点、失效位置、性能、全量回归和双版本兼容均必须验证。

**Organization**: 任务按两个用户故事组织。生产代码只修改现有侧边栏导航方法，不新增生产类、接口、线程、锁、缓存或导航层。

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 固定异常证据、脏工作区和受保护范围。

- [X] T001 将 IDEA 2026.2 完整异常栈、当前分支、`git status --short` 和 `src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java` SHA-256 基线记录到 `specs/006-fix-sidebar-read-access/verification.md`
- [X] T002 核对 `.gitignore`、Java 17、Gradle Wrapper、现有依赖、`.specify/extensions.yml` 和禁止新增项并记录到 `specs/006-fix-sidebar-read-access/verification.md`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 固定当前导航行为并确认平台最小修复入口。

**⚠️ CRITICAL**: 完成本阶段后才能修改生产代码。

- [X] T003 运行 `MqTopologyPanelTest` 与 `MqSpecialQueueTopologyPanelTest` 基线并将当前测试结果及 IDEA 2026.2 运行时失败差异记录到 `specs/006-fix-sidebar-read-access/verification.md`
- [X] T004 核对 IDEA 2023.3 可用的平台导航描述 API、`MqTopologyPanel.openSelectedSource` 调用链和只修改一个生产方法的边界，记录到 `specs/006-fix-sidebar-read-access/research.md`

**Checkpoint**: 已确认异常仅来自双击导航把 PSI 带出 read action，且无需新抽象。

---

## Phase 3: User Story 1 - 正常打开侧边栏端点源码 (Priority: P1) 🎯 MVP

**Goal**: 普通节点和特殊关系节点双击后一次打开真实源码，最新 IDE 不再产生线程访问异常。

**Independent Test**: 普通端点、特殊队列、交换机、绑定和目标关系各重复双击 20 次，全部打开对应源码且每次小于 1 秒。

### Tests for User Story 1

- [X] T005 [P] [US1] 在 `src/test/java/cn/luckyh/mqx/ui/MqTopologyPanelTest.java` 增加普通端点 20 次双击与每次严格小于 1 秒的回归测试
- [X] T006 [P] [US1] 在 `src/test/java/cn/luckyh/mqx/ui/MqSpecialQueueTopologyPanelTest.java` 增加特殊队列、死信交换机、绑定和目标关系各 20 次双击导航及每次严格小于 1 秒测试
- [X] T007 [US1] 运行新增 UI 测试并把 IDEA 2026.2 日志中的预期失败调用栈作为 read-action 缺口证据记录到 `specs/006-fix-sidebar-read-access/verification.md`

### Implementation for User Story 1

- [X] T008 [US1] 在 `src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java` 的现有 read action 内把有效源码元素转换为平台稳定导航描述，并在 read action 外执行导航
- [X] T009 [US1] 运行 `MqTopologyPanelTest` 与 `MqSpecialQueueTopologyPanelTest` 并记录普通/特殊节点重复导航和 1 秒门槛结果到 `specs/006-fix-sidebar-read-access/verification.md`

**Checkpoint**: 有效节点导航可独立验收，不再把裸 PSI 带出 read action。

---

## Phase 4: User Story 2 - 安全处理失效源码位置 (Priority: P2)

**Goal**: 项目、源码位置或导航目标失效时静默停止，不打开错误文件、不抛内部异常。

**Independent Test**: 源码删除、位置失效、不可导航和无源码节点各重复 10 次，打开文件数量和内部异常均为 0；项目关闭分支通过生命周期测试与代码路径验证。

### Tests for User Story 2

- [X] T010 [US2] 在 `src/test/java/cn/luckyh/mqx/ui/MqTopologyPanelTest.java` 增加源码删除、失效位置、不可导航、项目关闭和无源码节点各 10 次安全降级测试

### Implementation for User Story 2

- [X] T011 [US2] 在 `src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java` 完成项目关闭、智能位置失效、导航元素失效和描述不可导航的提前返回，保持旧文件不被误开
- [X] T012 [US2] 运行两个 UI 测试类并记录失效场景、旧选中项隔离和双击坐标解析结果到 `specs/006-fix-sidebar-read-access/verification.md`

**Checkpoint**: 有效和失效导航均满足侧边栏行为要求。

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: 完成回归、兼容、安全和需求追踪门禁。

- [X] T013 运行全部 `cn.luckyh.mqx.navigation.*Test`、`cn.luckyh.mqx.ui.*Test` 与 `clean test --no-parallel`，记录测试数量和结果到 `specs/006-fix-sidebar-read-access/verification.md`
- [X] T014 运行 `verifyPluginProjectConfiguration`、`verifyPluginStructure` 和 `verifyPlugin`，记录 IDEA 2023.3/2026.2.1 兼容结果及新增弃用 API 数量到 `specs/006-fix-sidebar-read-access/verification.md`
- [X] T015 扫描 `src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java` 的 PSI 导航边界、线程/锁/依赖/broker 调用并运行 `git diff --check` 与目标 diff
- [X] T016 核对 FR-001–FR-010、SC-001–SC-006、27 个受保护 feature 005 任务和当前 dirty work，完成 `specs/006-fix-sidebar-read-access/verification.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup**: 无依赖，先固定异常和工作区证据。
- **Foundational**: 依赖 Setup，阻塞生产修改。
- **US1**: 依赖 Foundational，形成可交付修复。
- **US2**: 复用 US1 的稳定导航描述并补齐失效分支。
- **Polish**: 依赖两个故事完成。

### User Story Dependencies

- **User Story 1 (P1)**: 首个可交付增量，无其他故事依赖。
- **User Story 2 (P2)**: 依赖 US1 的导航描述生成流程，但可用失效场景独立验收。

### Within Each User Story

- 测试和失败证据先于生产修改。
- 同一文件任务顺序执行；两个 UI 测试文件可并行准备。
- 生产修改保持在一个现有方法内，先生成稳定描述，再处理失效提前返回。
- 每个故事完成后运行 focused tests。

### Parallel Opportunities

- T005 与 T006 修改不同测试文件，可并行。
- T013 的测试类选择与 T015 的静态扫描可在生产修复完成后并行。

## Parallel Example: User Story 1

```text
Task T005: ordinary endpoint repeated navigation tests
Task T006: special relationship repeated navigation tests
```

## Implementation Strategy

### MVP First (User Story 1 Only)

1. 固定 IDEA 2026.2 异常调用栈和测试基线。
2. 先补普通与特殊关系重复导航测试。
3. 在 read action 内生成稳定导航描述。
4. focused tests 通过后再补失效目标场景。

### Incremental Delivery

1. Setup + Foundational → 根因和范围固定。
2. US1 → 有效节点恢复导航且无线程异常。
3. US2 → 失效节点安全停止。
4. Polish → 全量回归、双版本兼容和范围证明。

## Notes

- 不新增生产类、接口、方法层、线程、锁、缓存、通知、重试或依赖。
- 不修改 gutter 导航、刷新、搜索、模块折叠、缓存或特殊队列关系逻辑。
- 所有任务完成后标记为 `[X]`；验证文档必须区分自动化结果、日志证据和人工 smoke test。
