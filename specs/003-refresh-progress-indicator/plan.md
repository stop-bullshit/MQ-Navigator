# Implementation Plan: RabbitMQ 刷新进度提示

**Branch**: `003-refresh-progress-indicator`（Spec Kit 逻辑分支；当前 Git checkout 仍为 `master`） | **Date**: 2026-08-24 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/003-refresh-progress-indicator/spec.md`

## Summary

在现有 RabbitMQ 侧边栏底部状态区增加一个紧凑的不确定进度条，与“正在刷新 RabbitMQ 端点”文字同步出现。进度条直接复用 `MqTopologyPanel` 已有 `refreshing` 和 `refreshRequestId` 生命周期：当前最新刷新开始时激活，最新成功或失败回调结束时停止并隐藏，过期回调不得改变其状态。等待项目索引、空结果、无匹配和失败仍使用现有文字反馈，树模型、扫描链路和用户交互保持不变。实现仅修改现有面板和 UI 测试，不新增生产类、依赖、线程、锁、缓存或持久化。

## Technical Context

**Language/Version**: Java 17；Gradle Kotlin DSL；Gradle Wrapper 9.7.0

**Primary Dependencies**: IntelliJ Platform Gradle Plugin `2.18.1`；IntelliJ IDEA Community `2023.3`；bundled Java plugin；现有 Swing/JetBrains UI、`JProgressBar`、`ReadAction.nonBlocking`、`SimpleTree`；不新增依赖

**Storage**: 不新增持久化或独立状态对象；复用面板内 `refreshing`、`refreshRequestId` 和现有状态文字，新增的进度条仅保存组件自身可见与不确定状态

**Testing**: IntelliJ `TestFrameworkType.Plugin.Java`、JUnit 4.13.2、`BasePlatformTestCase`；扩展现有 `MqTopologyPanelTest`，复用 `MqNavigationPerformanceTest`、broker 隔离、完整测试和 Plugin Verifier 门禁

**Target Platform**: IntelliJ IDEA 2023.3+，最低 build `233`；兼容验证目标为 IDEA 2023.3 与 2026.2.1

**Project Type**: 单模块桌面 IDEA 插件

**Performance Goals**: 5 次预热后采集至少 20 次持续 500ms 以上的受控刷新，进度组件进入可见活动状态的 nearest-rank p95 不超过 300ms；成功、合法空结果、失败各至少 5 次及索引等待至少 5 次均保持正确进度状态且终态残留为 0；现有 50 模块/2,000 RabbitMQ endpoint 用户可见刷新 p95 继续小于 3 秒

**Constraints**: 进度条只表达不确定进行状态，不显示百分比；仅在“正在刷新 RabbitMQ 端点”状态下活动；等待索引和所有终态均隐藏；旧树保持可浏览；扫描不在 EDT，UI 只在 EDT 更新；不新增自定义线程、计时器、锁、原子状态、第二套缓存或持久化；不改变刷新结果、parser、matcher、导航、筛选和模块状态规则；不修改用户项目或连接 broker

**Scale/Scope**: 当前打开项目的 RabbitMQ 侧边栏底部状态区；进度显示 5 次预热加 20 次计时样本；三类最终结果与索引等待各至少 5 次；连续刷新至少 10 组反向完成样例；回归规模 50 个真实模块、2,000 个 RabbitMQ endpoint

## Constitution Check

*GATE: Phase 0 前和 Phase 1 后均已检查。*

| Principle | Pre-Research | Post-Design | Evidence |
|-----------|--------------|-------------|----------|
| I. 最小范围与直接实现 | PASS | PASS | 仅修改 `MqTopologyPanel` 和现有 `MqTopologyPanelTest`；不新增生产类、接口、设计方式或中间层 |
| II. 平台优先与证据驱动的复杂度 | PASS | PASS | 使用兼容 IDEA 2023.3+ 的标准 `JProgressBar`；不复制 InterfaceX 的后台任务结构，不使用覆盖式加载面板或自绘动画 |
| III. PSI、索引与并发安全 | PASS | PASS | 现有 NBRA/smart-mode/项目失效主链不变；进度状态只在现有 EDT 入口和最新回调中更新 |
| IV. 用户项目只读与运行时隔离 | PASS | PASS | 功能只修改插件内 UI；`D:\github\interfacex` 保持只读；继续执行 broker 隔离与文件哈希门禁 |
| V. 测试先行与量化证据 | PASS | PASS | 先扩展受控 supplier UI 测试，再修改面板；focused、heavy 回归、完整测试、Verifier、sandbox 和首次使用者验证分开记录 |
| VI. 可追踪的增量交付 | PASS | PASS | 三个用户故事、FR-001–FR-011 和 SC-001–SC-006 均映射到状态矩阵、测试或人工验收步骤 |

无 Constitution 例外，无未解决的 `NEEDS CLARIFICATION`，`Complexity Tracking` 不适用。

## Project Structure

### Documentation (this feature)

```text
specs/003-refresh-progress-indicator/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── sidebar-progress-ui.md
├── checklists/
│   └── requirements.md
└── tasks.md                     # 后续 /speckit-tasks 生成，本阶段不创建
```

### Source Code (repository root)

```text
src/main/java/cn/luckyh/mqx/
├── ui/
│   ├── MqTopologyPanel.java             # 修改：状态行进度条及刷新生命周期同步
│   └── MqToolWindowService.java         # 保护：刷新转发不修改
├── action/
│   └── RefreshMqIndexAction.java        # 保护：刷新入口与调用顺序不修改
└── navigation/
    └── MqNavigationService.java         # 保护：扫描和快照来源不修改

src/test/java/cn/luckyh/mqx/
├── ui/
│   └── MqTopologyPanelTest.java         # 修改：进度条、连续刷新和交互回归
└── navigation/
    ├── MqNavigationPerformanceTest.java # 保护：仅运行现有 50 模块回归
    └── MqBrokerIsolationTest.java       # 保护：仅运行现有隔离门禁
```

**Structure Decision**: 保持现有单模块插件结构。进度提示属于单个面板的展示职责，直接加入已有状态行；测试继续使用已有包可见 supplier 接缝。没有新增生产类、模型、服务、接口或事件层的现实必要性。

## Design Flow

```text
用户触发刷新
    → MqTopologyPanel.refresh() 在 EDT 创建新的 refreshRequestId
    → refreshing = true
    → 项目可分析：显示“正在刷新 RabbitMQ 端点”并激活不确定进度条
    → 项目正在索引：显示“等待项目索引完成”并保持进度条隐藏
    → 现有 ReadAction.nonBlocking(...).inSmartMode(project).expireWith(project)
    → finishOnUiThread
    → Project 已关闭：不再访问 UI
    → requestId 不是最新：丢弃，不改变文字、进度条或树
    → 最新失败：refreshing = false，停止并隐藏进度条，保留旧树并显示失败文字
    → 最新成功：refreshing = false，停止并隐藏进度条，替换 endpoint 并按现有规则重建树
```

### Status row design

1. 在现有底部状态区保留 `JBLabel statusLabel`，增加名为 `mqTopologyRefreshProgress` 的 `JProgressBar refreshProgressBar`，二者放入同一个始终存在的状态行；固定占位区沿用进度条平台首选宽度，高度取进度条首选高度与状态文字字体高度的较大值，隐藏进度条时仍保留状态行高度。
2. 进度条使用平台默认外观、紧凑宽度和不确定模式，不绘制百分比、字符串或剩余时间。
3. 正常状态下进度条不可见且不处于不确定模式；开始显示时先进入不确定模式再显示，结束时停止不确定模式并隐藏，避免隐藏组件继续动画。
4. 进度条设置稳定组件名称，供现有递归组件查找测试使用；状态文字继续承担可读说明。
5. 状态行位于树滚动区之外，不覆盖树、搜索框、类型筛选或刷新入口；进度条出现和消失不得替换 tree model。

### Refresh lifecycle handling

1. `refresh()` 继续在 EDT 递增 `refreshRequestId`。如果当前不是 dumb mode，状态文字和进度条在提交后台任务前同步进入刷新状态，因此没有额外异步显示延迟。
2. 如果当前处于 dumb mode，保持“等待项目索引完成”文字并隐藏进度条，避免把尚未开始的 endpoint 扫描表示为已有扫描进度。
3. 最新失败回调在写入失败文字前结束进度条；最新成功回调在调用 `replaceTree()` 前结束进度条，随后由现有 `rebuildTree()` 决定成功、空结果或无匹配文字。
4. 过期回调的请求号检查继续位于任何 UI 终态更新之前，因此不能提前关闭最新请求的进度条，也不能重新激活已结束提示。
5. 项目关闭仍由 `expireWith(project)` 和现有 disposed 检查处理；不新增监听器、回调包装器或取消状态。
6. 可增加一个被多个刷新分支复用的私有进度状态方法，同时更新 `indeterminate` 与 `visible`。该方法表达完整 UI 状态转换，不是单行转发；除此之外不拆分新的方法。

## New Classes and Abstractions

不新增生产类、测试类、接口、设计方式、Manager、Handler、Processor、Context 或中间层。

允许在 `MqTopologyPanel` 内新增：

- 一个 `JProgressBar` 字段；
- 一个被开始、成功和失败路径复用的私有进度状态方法；
- 现有 UI 测试中的组件查找和断言辅助，不新增独立测试框架。

## Implementation Boundaries

### Files to modify

- `src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java`: 将状态标签包装为稳定状态行；增加 `JProgressBar`；在最新刷新开始、成功和失败路径同步其状态。
- `src/test/java/cn/luckyh/mqx/ui/MqTopologyPanelTest.java`: 先增加活动、结束、索引等待、失败、空结果、连续刷新和刷新期间交互断言，再实现生产逻辑。
- `specs/003-refresh-progress-indicator/*`: 保持规格、计划、研究、状态模型、UI 约定和验证指南同步；实现阶段新增 `verification.md`。

### Explicitly protected scope

- 不修改 `RefreshMqIndexAction`、`MqToolWindowService`、`MqNavigationService`、`MqNavigationPerformanceTest`、`MqBrokerIsolationTest`、各 MQ parser、`MqEndpointMatcher`、`plugin.xml`、依赖或插件版本。
- 不修改 `refreshRequestId` 最新结果规则、NBRA/smart-mode/项目失效链、endpoint 扫描和 tree model 构建逻辑。
- 不修改模块折叠、搜索、类型筛选、数量、动态提示、源码指针和双击导航行为。
- 不新增取消、暂停、自动刷新、阶段进度、百分比、完成通知或其他消息系统进度提示。
- 不修改 `D:\github\interfacex` 或 `D:\github\feignx-plugin`。
- 不连接 broker，不写入用户项目，不执行项目业务代码。

## Risks and Controls

- **过期请求提前关闭最新进度**: 所有停止操作都位于现有 `requestId == refreshRequestId` 检查之后；连续反向完成测试循环至少 10 组。
- **隐藏进度条仍消耗动画资源**: 隐藏前关闭不确定模式；显示时才重新开启。
- **等待索引被误认为扫描进度**: dumb mode 保留等待文字并隐藏进度条，不新增推测阶段。
- **筛选重建覆盖刷新反馈**: 复用现有 `refreshing` guard；刷新中执行搜索、筛选和模块操作的测试同时检查进度状态。
- **进度组件遮挡或引发布局跳动**: 使用底部独立状态行和平台组件，不采用覆盖层；sandbox 对窄工具窗口和出现/消失状态进行目视检查。
- **快速刷新导致闪烁**: 不新增延时计时器；快速完成允许提示短暂或不可感知，但结束后必须无残留。
- **平台版本兼容**: `JProgressBar` 已在 IDEA 2023.3 与 2026.2.1 编译和 Plugin Verifier 中确认兼容。
- **范围扩张到后台任务体系**: 不采用 InterfaceX 的 `Task.Backgroundable`/`ProgressIndicator` 扫描结构，因为现有 NBRA 已满足索引和线程边界。

## Verification Design

### Test-first order

1. 在现有 `MqTopologyPanelTest` 中先查找命名进度组件，添加刷新可见、成功/失败结束、等待索引隐藏、两个请求反向完成和固定窄宽度不重叠的首组失败断言；5 次预热后采集 20 次持续至少 500ms 的刷新显示延迟并使用 nearest-rank 断言 p95 不超过 300ms。
2. 对成功、合法空结果、失败和索引等待各执行至少 5 次受控状态样例，确认对应文字出现时进度状态正确，最终状态没有活动提示残留，旧树保留规则不变。
3. 扩展 dumb-mode 测试，确认等待索引文字保留且进度条隐藏，恢复 smart mode 后正常结束。
4. 将现有反向完成测试扩展为至少 10 组，确认较早回调不提前关闭或重新激活最新进度，最终树仍只应用最新结果。
5. 增加刷新保持进行时的模块展开、文本搜索和类型筛选操作，确认已有树可用、刷新文字和进度条不被筛选状态覆盖。
6. 记录上述 red 结果后，仅修改 `MqTopologyPanel`，再运行相同 focused 命令转绿。

### Focused and regression gates

- 定向 UI：`.\gradlew.bat test --tests "cn.luckyh.mqx.ui.MqTopologyPanelTest" --rerun-tasks`
- 现有真实规模回归：`.\gradlew.bat test --tests "cn.luckyh.mqx.navigation.MqNavigationPerformanceTest" --rerun-tasks --no-parallel`
- 完整测试：`.\gradlew.bat clean test`
- broker 隔离：`.\gradlew.bat test --tests "*MqBrokerIsolationTest" --rerun-tasks`
- 运行依赖：`.\gradlew.bat dependencies --configuration runtimeClasspath`
- 配置与结构：`.\gradlew.bat verifyPluginProjectConfiguration verifyPluginStructure`
- 二进制兼容：`.\gradlew.bat verifyPlugin`
- sandbox：`.\gradlew.bat runIde`

### Timing and usability evidence

- 进度显示计时先执行 5 次预热，再采集 20 次样本；每次从测试线程提交 EDT 刷新调用前开始，到 EDT 上读取到进度条 `visible=true` 且 `indeterminate=true` 时结束，阻塞 supplier 保持该次刷新持续至少 500ms，输出 n、p50、p95 和 max，nearest-rank p95 必须不超过 300ms。
- 成功、合法空结果和失败各至少 5 次，索引等待至少 5 次；状态文字可见时同步断言进度状态，成功、空结果和失败均要求活动提示残留 0 次。
- 50 模块/2,000 endpoint heavy 回归继续验证新增组件未使用户可见刷新 p95 超过 3 秒，不修改 heavy fixture 或阈值。
- 至少 10 名首次使用者在持续超过 500ms 的刷新中，无说明判断刷新是否仍在进行；至少 9 人在 1 秒内正确判断才满足 SC-005。

### Safety and reporting gates

- 复用 `MqBrokerIsolationTest`、运行依赖搜索和生产源码网络 API 搜索，RabbitMQ/Kafka/RocketMQ 连接尝试保持 0。
- sandbox 使用一次性项目副本，操作前后比较受保护文件 SHA-256，插件造成的非用户改动保持 0。
- 在 `verification.md` 分开记录 red/green focused、heavy 回归、完整测试、Verifier、sandbox、首次使用者和生产项目验证；未运行或环境阻塞不得写成通过。
- 最终运行 `git diff --check` 与 `git status --short`，确认只出现本计划批准的一个生产文件、一个测试文件和本 feature 文档。

## Traceability

| Scope | Requirements | Success Criteria | Primary Evidence |
|-------|--------------|------------------|------------------|
| US1 刷新进行反馈 | FR-001–FR-005 | SC-001, SC-002, SC-005 | `MqTopologyPanelTest`、sandbox、首次使用者判断测试 |
| US2 多结果生命周期 | FR-004–FR-007 | SC-002, SC-003 | 失败/空结果测试、10 组反向完成测试 |
| US3 刷新期间继续浏览 | FR-007–FR-010 | SC-004 | 刷新中折叠、搜索、筛选回归和 sandbox |
| 只读与运行时隔离 | FR-011 | SC-006 | broker 隔离、依赖检查、网络 API 搜索、SHA-256 基线 |

## Complexity Tracking

无 Constitution 违规，不需要复杂度例外。
