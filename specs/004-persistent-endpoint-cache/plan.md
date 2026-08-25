# Implementation Plan: MQ 端点持久缓存

**Branch**: `004-persistent-endpoint-cache`（Spec Kit 逻辑分支；当前 Git checkout 仍为 `master`） | **Date**: 2026-08-24 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/004-persistent-endpoint-cache/spec.md`

## Summary

在现有 `MqNavigationService` 的项目级不可变快照前增加一次性的持久化种子：项目服务首次计算快照时，优先从 IntelliJ Platform 项目级系统缓存恢复全部 endpoint；有效缓存直接成为现有 `CachedValue<EndpointSnapshot>` 的值，缓存缺失、版本不匹配、字段损坏或任一源码位置无法恢复时整批回退现有 parser 扫描。手动刷新继续通过现有 modification tracker 失效快照，并显式禁止再次使用持久缓存；会话内 PSI 或项目根变化仍由现有依赖失效，扫描成功后替换持久状态。实现不增加数据库、依赖、文件监听器、线程、锁、缓存管理 UI 或第二套运行时快照。

## Technical Context

**Language/Version**: Java 17；Gradle Kotlin DSL；Gradle Wrapper 9.7.0

**Primary Dependencies**: IntelliJ Platform Gradle Plugin `2.18.1`；IntelliJ IDEA Community `2023.3`；bundled Java plugin；现有 `CachedValue`、`SmartPsiElementPointer`、`PersistentStateComponent`、`StoragePathMacros.CACHE_FILE`；不新增依赖

**Storage**: IntelliJ Platform 项目级系统缓存文件；仅保存版本、保存时间和扁平 endpoint 状态，不写用户项目、`.idea`、外部数据库或应用自定义目录

**Testing**: IntelliJ `TestFrameworkType.Plugin.Java`、JUnit 4.13.2、`BasePlatformTestCase`；新增 `MqNavigationCacheTest`，复用现有导航、UI、性能、broker 隔离、完整测试和 Plugin Verifier 门禁

**Target Platform**: IntelliJ IDEA 2023.3+，最低 build `233`；兼容验证目标为 IDEA 2023.3 与 2026.2.1

**Project Type**: 单模块桌面 IDEA 插件

**Performance Goals**: 5 次预热后，对 2,000 endpoint 缓存执行至少 20 次恢复，nearest-rank p95 不超过 500ms；现有 50 模块/2,000 endpoint/40 次用户可见刷新 p95 继续严格小于 3 秒

**Constraints**: 有效空缓存必须可恢复；缓存只在每个服务实例第一次快照计算时尝试一次；任一条目无法恢复时整批扫描；手动刷新绕过缓存；发布快照仍不可修改；PSI 访问保持 read action/smart mode；长期状态不保存裸 PSI；不新增线程、计时器、锁、原子状态、监听器、数据库、依赖或用户项目文件

**Scale/Scope**: 当前项目全部 RabbitMQ、Kafka、RocketMQ endpoint；恢复正确性覆盖全部现有系统/角色/目的地字段；损坏与回退每类至少 5 次；项目隔离至少 2 个项目、每个 10 次；性能恢复规模 2,000 endpoint

## Constitution Check

*GATE: Phase 0 前和 Phase 1 后均已检查。*

| Principle | Pre-Research | Post-Design | Evidence |
|-----------|--------------|-------------|----------|
| I. 最小范围与直接实现 | PASS | PASS | 生产仅修改 `MqNavigationService`；不新增顶层生产类、Manager、Handler、接口或转发层 |
| II. 平台优先与证据驱动的复杂度 | PASS | PASS | 使用平台项目级系统缓存和现有 `CachedValue`；持久状态只作为首次计算种子，不形成第二套运行时缓存，不复制 InterfaceX 的 SQLite/后台任务体系 |
| III. PSI、索引与并发安全 | PASS | PASS | 长期状态只保存源码 URL 与偏移；恢复后重新创建 `SmartPsiElementPointer`；读取继续由现有 smart read action 驱动，无自定义线程或锁 |
| IV. 用户项目只读与运行时隔离 | PASS | PASS | `$CACHE_FILE$` 位于 IDE system path；不写项目或连接 broker；`D:\github\interfacex` 全程只读 |
| V. 测试先行与量化证据 | PASS | PASS | 先新增缓存命中、空缓存、损坏回退、手动刷新、版本和 2,000 endpoint p95 测试，再修改服务；保留 heavy、全量、隔离和 Verifier 门禁 |
| VI. 可追踪的增量交付 | PASS | PASS | US1–US3、FR-001–FR-017、SC-001–SC-007 映射到缓存状态机、内部行为文档和后续任务 |

无 Constitution 例外，无未解决的 `NEEDS CLARIFICATION`，`Complexity Tracking` 不适用。

## Project Structure

### Documentation (this feature)

```text
specs/004-persistent-endpoint-cache/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── endpoint-cache-behavior.md
├── checklists/
│   └── requirements.md
└── tasks.md                     # 后续 /speckit-tasks 生成
```

### Source Code (repository root)

```text
src/main/java/cn/luckyh/mqx/
├── navigation/
│   └── MqNavigationService.java        # 修改：平台持久状态、首次恢复、扫描后替换
├── model/
│   ├── MqEndpoint.java                 # 保护：运行时模型不修改
│   └── MqDestination.java              # 保护：目的地模型不修改
├── parser/                              # 保护：全部 parser 不修改
├── match/                               # 保护：匹配规则不修改
├── action/
│   └── RefreshMqIndexAction.java       # 保护：刷新入口和顺序不修改
└── ui/                                  # 保护：侧边栏与进度 UI 不修改

src/test/java/cn/luckyh/mqx/navigation/
├── MqNavigationCacheTest.java          # 新增：缓存生命周期、恢复性能与隔离
├── MqNavigationServiceTest.java        # 保护：既有导航回归
├── MqNavigationPerformanceTest.java    # 保护：既有 50 模块 heavy 门槛
└── MqBrokerIsolationTest.java          # 保护：broker 零连接门槛
```

**Structure Decision**: 保持单模块插件结构。持久化状态属于现有项目级快照服务职责，直接由 `MqNavigationService` 实现平台状态接口；状态 bean 作为该服务的嵌套静态类型，避免创建仓储、DAO、序列化工具和独立缓存服务。新增独立测试类是必要的，因为现有 `MqNavigationServiceTest` 已超过 500 行，缓存生命周期和 2,000 endpoint 性能应独立验收。

## Design Flow

```text
项目服务首次 currentEndpoints()/findTargets()
    → 现有 CachedValue 请求 EndpointSnapshot
    → 建立 PSI、项目根、手动刷新依赖
    → 本服务实例是否仍允许首次缓存恢复？
        → No：执行现有完整 parser 扫描
        → Yes：只尝试一次持久状态恢复
            → 无保存标记：执行完整扫描
            → 版本不匹配/必要字段损坏：执行完整扫描
            → 任一源码 URL 或偏移无法恢复：丢弃整批并执行完整扫描
            → 全部恢复成功：排序、去重并发布为现有不可变 EndpointSnapshot
    → 完整扫描成功：替换持久状态，包括合法空结果
    → parser 局部失败：返回现有容错结果，但不覆盖上次完整缓存

用户手动刷新
    → RefreshMqIndexAction 继续调用 requestRefresh()
    → 禁止当前服务实例再次使用持久缓存
    → manualRefreshTracker 失效现有 CachedValue
    → 下一次读取执行完整扫描
    → 完整成功后替换持久状态

会话内 PSI/项目根变化
    → 现有依赖失效 CachedValue
    → 首次缓存机会已消耗，直接完整扫描并更新持久状态
```

## Persistence Design

1. `MqNavigationService` 增加平台 `@State`，存储目标使用 `$CACHE_FILE$`，继续保持 `@Service(Service.Level.PROJECT)`。
2. 服务实现平台 `PersistentStateComponent<CacheState>`；`CacheState` 与 `CachedEndpointState` 是 XML 序列化所需的嵌套可变 bean，不进入运行时业务模型。
3. `CacheState` 保存 `version`、`savedAt` 与条目列表；`savedAt > 0` 使合法空列表与缓存缺失可区分。
4. `CachedEndpointState` 扁平保存 endpoint 和 destination 的所有值字段，并保存源码 `VirtualFile` URL 与文本偏移；不保存 `PsiElement`、`SmartPsiElementPointer` 或 Project。
5. 恢复时通过当前项目的 PSI 文件和偏移重新创建 `SmartPsiElementPointer`；恢复数量必须与持久条目数量相同，否则整份状态无效。
6. 状态版本常量首版为 `1`；未知版本直接回退扫描，不做推测迁移。
7. 完整扫描后先构造新的 `CacheState`，全部 endpoint 都可序列化后再一次替换服务字段；扫描或转换失败不触碰旧状态。

## Runtime Snapshot Rules

1. `CachedValue<EndpointSnapshot>` 仍是唯一运行时真相来源；缓存恢复结果只作为 provider 的一次返回值。
2. 服务字段 `restorePersistentCache` 初始为 `true`，首次 provider 计算后立即转为 `false`；同一实例不反复从持久状态恢复。
3. `loadState()` 只安装平台反序列化状态；测试可以创建新服务实例并调用该方法模拟项目重开。
4. `requestRefresh()` 先禁用持久恢复，再递增现有 modification tracker，确保刷新不能命中旧持久状态。
5. 排序继续使用现有 `ENDPOINT_ORDER`，重复 endpoint 继续按 `endpointId` 去重；缓存数据不得改变 matcher 或导航优先级。
6. parser 级已知异常继续局部隔离，但本轮标记为非完整扫描，因此不替换上次完整缓存。

## New Classes and Abstractions

不新增顶层生产类、接口、仓储、DAO、数据库、Manager、Handler、Processor、Context、监听器或线程设施。

在 `MqNavigationService` 内新增：

- 平台 `PersistentStateComponent<CacheState>` 实现：平台状态保存所必需，不是项目自定义接口；
- `CacheState`：持久快照头和条目列表；
- `CachedEndpointState`：扁平序列化 endpoint；
- 最少的“恢复整批缓存”和“创建持久状态”私有业务步骤，二者分别隔离 PSI 恢复与状态转换，便于独立测试。

新增 `MqNavigationCacheTest` 测试类，不新增测试框架或生产测试接缝。

## Implementation Boundaries

### Files to modify

- `src/main/java/cn/luckyh/mqx/navigation/MqNavigationService.java`: 接入平台项目缓存、恢复整批 endpoint、手动刷新绕过、完整扫描后替换。
- `src/test/java/cn/luckyh/mqx/navigation/MqNavigationCacheTest.java`: 先覆盖命中、空缓存、损坏、版本、源码失效、刷新、字段一致、项目隔离和 2,000 endpoint 性能。
- `specs/004-persistent-endpoint-cache/*`: 规格、设计、任务和验证证据。

### Explicitly protected scope

- 不修改 `MqEndpoint`、`MqDestination`、所有 parser、`MqEndpointMatcher`、`RefreshMqIndexAction`、`MqToolWindowService`、`MqTopologyPanel` 和进度状态。
- 不修改 `MqNavigationService` 的匹配、角色优先级、源码范围判断、排序比较器和现有缓存依赖。
- 不修改 `MqNavigationPerformanceTest`、`MqBrokerIsolationTest`、`plugin.xml`、依赖或插件版本。
- 不修改 `D:\github\interfacex`、`D:\github\feignx-plugin` 或用户项目 fixture 原件。
- 不连接 broker，不执行用户业务代码，不创建自定义缓存文件或数据库。

## Risks and Controls

- **持久缓存成为第二套真相**: 只在现有 provider 首次计算时尝试一次，恢复后仍发布为同一个 `CachedValue`；手动刷新和会话内失效都执行扫描。
- **部分旧结果误导用户**: 采用整批恢复，数量不一致或任一源码位置失效即扫描。
- **缓存导航偏移错误**: 恢复当前 `PsiFile` 的实际元素并重新创建智能指针；无元素或越界直接判缓存无效。
- **合法空结果被当成缓存缺失**: 使用保存时间标记区分，空列表可以直接发布并在刷新后覆盖旧缓存。
- **parser 局部失败覆盖完整缓存**: 扫描记录完整性，存在被隔离异常时不替换持久状态。
- **跨项目泄漏**: 平台状态与服务均为 Project 级；恢复的 VirtualFile 必须属于当前项目源码范围。
- **缓存恢复阻塞 EDT**: 调用仍位于现有 smart read action/NBRA；不在工具窗口构造或 EDT 直接访问 PSI。
- **状态文件污染用户项目**: 固定使用平台 `$CACHE_FILE$` system storage，并通过 sandbox 哈希证明项目零改动。
- **平台兼容**: 在 IDEA 2023.3 与 2026.2.1 运行 Plugin Verifier；不使用新版本专属 API。

## Verification Design

### Test-first order

1. 新增 `MqNavigationCacheTest`，用新服务实例手动加载状态模拟项目重开；先断言缓存中的专用目的地值被恢复，而当前源码扫描不会产生该值，获得真实 red。
2. 覆盖合法空缓存、版本不匹配、必要字段损坏、源码 URL 不存在和偏移越界；无效状态必须回退当前源码扫描，不能返回部分缓存。
3. 对 RabbitMQ、Kafka、RocketMQ 和现有角色构造代表条目，比较恢复前后全部 endpoint/destination 字段与源码导航目标。
4. 缓存命中后调用 `requestRefresh()`，确认扫描恢复当前源码值并更新持久状态；再创建新服务验证更新结果可再次恢复。
5. 构造两个项目服务状态并至少循环 10 次，确认恢复结果和源码均不串项目。
6. 以同一有效源码位置生成 2,000 个不同 endpoint 条目；预热 5 次、采集 20 次完整恢复，输出 n/p50/p95/max 并断言 nearest-rank p95 不超过 500ms。
7. 保存 red 证据后仅修改 `MqNavigationService`，再执行同一 focused 命令转绿。

### Focused and regression gates

- 缓存定向：`.\gradlew.bat test --tests "cn.luckyh.mqx.navigation.MqNavigationCacheTest" --rerun-tasks --no-parallel`
- 导航服务：`.\gradlew.bat test --tests "cn.luckyh.mqx.navigation.MqNavigationServiceTest" --rerun-tasks --no-parallel`
- UI 与刷新进度：`.\gradlew.bat test --tests "cn.luckyh.mqx.ui.MqTopologyPanelTest" --rerun-tasks --no-parallel`
- 真实规模：`.\gradlew.bat test --tests "cn.luckyh.mqx.navigation.MqNavigationPerformanceTest" --rerun-tasks --no-parallel`
- 完整测试：`.\gradlew.bat clean test --no-parallel`
- broker 隔离：`.\gradlew.bat test --tests "*MqBrokerIsolationTest" --rerun-tasks --no-parallel`
- 运行依赖：`.\gradlew.bat dependencies --configuration runtimeClasspath`
- 配置与结构：`.\gradlew.bat verifyPluginProjectConfiguration verifyPluginStructure`
- 二进制兼容：`.\gradlew.bat verifyPlugin`

### Safety and reporting gates

- 在 `verification.md` 分开记录 focused red/green、字段一致、损坏回退、项目隔离、2,000 endpoint 恢复、heavy、完整测试、Verifier、sandbox 和生产验证。
- sandbox 使用一次性项目副本，缓存恢复与刷新前后比较受保护文件 SHA-256；平台缓存文件不得出现在副本或源 fixture 中。
- 复用 broker 隔离测试、运行依赖和网络 API 搜索；三种 broker 连接尝试保持 0，运行依赖不新增 MQ client 或数据库驱动。
- 最终运行 `git diff --check` 与 `git status --short`；T001 基线之外，本 feature 只允许一个生产文件、一个新测试文件和 `specs/004-persistent-endpoint-cache/`。

## Traceability

| Scope | Requirements | Success Criteria | Primary Evidence |
|-------|--------------|------------------|------------------|
| US1 快速恢复 | FR-001–FR-008 | SC-001, SC-002 | 缓存命中、空缓存、字段一致、源码导航和 2,000 endpoint 恢复测试 |
| US2 刷新更新 | FR-009–FR-012 | SC-003, SC-005 | 手动刷新、会话失效、下一实例恢复、既有 heavy 回归 |
| US3 安全降级 | FR-002, FR-003, FR-007, FR-013–FR-017 | SC-004, SC-006, SC-007 | 损坏/版本/源码失效回退、双项目隔离、broker 与哈希门禁 |

## Complexity Tracking

无 Constitution 违规，不需要复杂度例外。
