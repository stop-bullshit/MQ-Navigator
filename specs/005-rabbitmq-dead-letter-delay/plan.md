# Implementation Plan: RabbitMQ 死信与延迟队列拓扑

**Branch**: `005-rabbitmq-dead-letter-delay`（Spec Kit 逻辑分支；当前 Git checkout 保持不变） | **Date**: 2026-08-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/005-rabbitmq-dead-letter-delay/spec.md`

## Summary

在现有 RabbitMQ `QueueBuilder.durable(...)` 队列声明解析上增加队列参数读取，识别 `x-dead-letter-exchange`、可选 `x-dead-letter-routing-key` 和 `x-message-ttl`。普通队列、死信来源队列和 TTL 延迟队列继续使用现有 `MqEndpoint`/`MqDestination`、项目快照、缓存和侧边栏树；死信目标关系由当前项目已经解析出的 Exchange、Binding、Queue 静态推导，不连接 broker，不执行用户代码。实现仅扩展现有模型、RabbitMQ parser、缓存字段和拓扑树关系，不新增依赖、接口、中间层或顶层生产类。

## Technical Context

**Language/Version**: Java 17；Gradle Kotlin DSL；Gradle Wrapper 9.7.0

**Primary Dependencies**: IntelliJ Platform Gradle Plugin `2.18.1`；IntelliJ IDEA Community `2023.3`；bundled Java plugin；现有 PSI、项目搜索、智能指针、缓存与 Swing 树组件；不新增依赖

**Storage**: 复用 `MqNavigationService` 的 IntelliJ Platform 项目级系统缓存；缓存版本升级后保存 RabbitMQ 队列设置，不写用户项目或外部存储

**Testing**: IntelliJ `TestFrameworkType.Plugin.Java`、JUnit 4.13.2、`BasePlatformTestCase`；扩展 RabbitMQ parser 与缓存测试，新增独立特殊队列 UI 测试，复用性能、broker 隔离、完整测试和 Plugin Verifier 门禁

**Target Platform**: IntelliJ IDEA 2023.3+，最低 build `233`；兼容验证目标为 IDEA 2023.3 与 2026.2.1

**Project Type**: 单模块桌面 IDEA 插件

**Performance Goals**: 现有 50 模块、2,000 endpoint、40 次用户可见刷新 nearest-rank p95 继续严格小于 3 秒；死信环路不得随树展开持续增加节点

**Constraints**: 项目级搜索不在 EDT；PSI 访问遵守 read action/smart mode；长期状态不保存裸 PSI；动态值只显示诊断；不执行 Spring 配置或用户业务代码；不连接 RabbitMQ；不新增线程、锁、监听器、数据库、依赖或独立 UI 入口

**Scale/Scope**: 当前项目 Java 源码中的 RabbitMQ `QueueBuilder` 声明；覆盖单参数与参数集合、跨模块 Exchange/Binding/Queue、多目标、缺失目标、动态值、缓存恢复和环路；Kafka、RocketMQ 与普通 RabbitMQ 行为保持不变

## Constitution Check

*GATE: Phase 0 前和 Phase 1 后均已检查。*

| Principle | Pre-Research | Post-Design | Evidence |
|-----------|--------------|-------------|----------|
| I. 最小范围与直接实现 | PASS | PASS | 只修改现有模型、parser、缓存服务和拓扑面板；不新增接口、Manager、Handler、Processor 或顶层生产类 |
| II. 平台优先与证据驱动的复杂度 | PASS | PASS | 复用 PSI、现有项目快照、智能指针、缓存和树组件；不引入第二套索引、关系仓库或运行时探测 |
| III. PSI、索引与并发安全 | PASS | PASS | 新解析仍由现有 smart read action 和项目范围搜索驱动；跨快照仅保存现有智能指针和扁平缓存数据；不新增线程或锁 |
| IV. 用户项目只读与运行时隔离 | PASS | PASS | 只读取源码；不连接 broker，不执行配置方法，不修改用户项目；参考清关源码保持只读 |
| V. 测试先行与量化证据 | PASS | PASS | parser、关系展示和缓存字段先写失败测试，再实现；继续运行 2,000 endpoint 性能、隔离、完整测试和双版本 Verifier |
| VI. 可追踪的增量交付 | PASS | PASS | US1–US3、FR-001–FR-019、SC-001–SC-008 映射到数据模型、交互规则、任务和验证证据 |

无 Constitution 例外，无未解决的 `NEEDS CLARIFICATION`，`Complexity Tracking` 不适用。

## Project Structure

### Documentation (this feature)

```text
specs/005-rabbitmq-dead-letter-delay/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── special-queue-topology.md
├── checklists/
│   └── requirements.md
├── tasks.md
└── verification.md
```

### Source Code (repository root)

```text
src/main/java/cn/luckyh/mqx/
├── model/
│   └── MqDestination.java                    # 修改：RabbitMQ 队列设置与摘要
├── parser/
│   └── RabbitMqEndpointParser.java           # 修改：单参数、参数集合和 TTL 解析
├── navigation/
│   └── MqNavigationService.java              # 修改：缓存版本与新增字段恢复
├── ui/
│   └── MqTopologyPanel.java                  # 修改：死信关系、目标队列和诊断展示
└── match/
    └── MqEndpointMatcher.java                # 保护：复用公开匹配入口，不修改

src/test/java/cn/luckyh/mqx/
├── parser/
│   └── RabbitMqEndpointParserTest.java       # 修改：特殊队列解析测试
├── navigation/
│   └── MqNavigationCacheTest.java            # 修改：新增字段往返和旧版本失效
└── ui/
    └── MqSpecialQueueTopologyPanelTest.java  # 新增：关系、搜索、导航和环路测试

src/test/testData/stubs/org/springframework/amqp/core/
└── QueueBuilder.java                         # 修改：补齐测试声明方法签名
```

**Structure Decision**: 保持单模块插件与现有分层。队列设置属于 `MqDestination` 的 RabbitMQ 特有元数据，使用其内部值类型承载；parser 负责静态提取，缓存服务负责扁平保存，UI 从同一 endpoint 快照推导关系。此方式不增加关系服务、仓储或第二套拓扑模型。

## Design Flow

```text
QueueBuilder.durable(queueName)
    → 沿当前 builder 调用链收集 withArgument / withArguments
    → 参数集合为局部 Map 时，读取声明前可静态追踪的 put / Map.of 值
    → 仅识别 dead-letter exchange、dead-letter routing key、message TTL
    → 静态值写入 RabbitQueueSettings；动态或无效值保留配置标记和诊断
    → 构造现有 DECLARATION endpoint
    → applyBindings 继续补全该队列的普通 Exchange/Binding 信息
    → MqNavigationService 保存/恢复完整 destination 与 RabbitQueueSettings
    → MqTopologyPanel 展示类型与参数摘要
    → 从现有 Exchange/Binding/Queue endpoint 推导 dead-letter 关系
```

## Static Resolution Rules

1. 只处理已解析为 Spring AMQP `QueueBuilder.durable(String)` 的调用，现有队列名称解析规则不变。
2. 依次读取该 builder 链上的 `withArgument(key, value)` 和 `withArguments(map)`；后出现的同名静态设置覆盖前值。
3. 参数集合支持当前方法中的局部 Map、线性 `put` 赋值和 `Map.of`；条件分支、循环、无法解析的 key 或外部返回 Map 不执行，只为已知特殊 key 记录动态诊断。
4. 交换机和路由键继续复用现有字符串值解析；TTL 使用常量求值，仅接受可静态确定的非负数字，`0` 保留为有效值。
5. 只有已出现的特殊 key 才设置“已配置”；无关 queue 参数忽略，不影响普通队列识别。
6. 队列名称解析状态与特殊参数解析状态分开：静态队列不会因某个动态 TTL 失去既有 Queue/Binding 关系，动态参数通过设置诊断展示。

## Data and Cache Design

1. `MqDestination` 增加可空的内部 `RabbitQueueSettings` 值，普通 RabbitMQ、Kafka、RocketMQ destination 仍为 `null`。
2. 设置保存三个“是否配置”标记、三个静态值和不可修改诊断列表；队列类型由配置组合派生，不重复持久化。
3. `MqDestination.summary()` 对特殊队列追加 `dead-letter` 或 `delay`、TTL、DLX、路由方式和未解析提示，使现有标签、搜索与 tooltip 自动获得属性文本。
4. `MqNavigationService` 缓存版本从 1 升至 2；扁平缓存三个标记、三个值和诊断列表。版本 1 按现有整批回退规则重新扫描，避免恢复成缺少特殊属性的完整结果。
5. 恢复时校验诊断列表无 null、TTL 非负、未标记配置的字段不得携带值；任一条目无效时整批回退。

## UI Relationship Rules

1. 对带死信交换机静态值的 Queue declaration，侧边栏在该队列下复用现有 endpoint 生成 `Dead-letter exchanges`、`Dead-letter bindings`、`Dead-letter queues` 关系分类。
2. 显式死信路由键使用现有 RabbitMQ direct/topic/fanout 匹配入口与 binding 对比；fanout 忽略 key；headers 或未知类型不冒充精确结果。
3. 未配置专用死信路由键时显示“沿用原路由键”；只有 fanout 或其他无需路由键即可确定的情况展示精确目标。
4. 默认交换机且存在静态死信路由键时，按 RabbitMQ 默认交换机的队列名规则关联目标 Queue declaration。
5. 关系节点直接引用原 endpoint 智能指针，双击继续使用现有源码跳转；关系节点不递归添加下一层关系，因此环路不会无限展开。
6. 缺失 Exchange、Binding 或 Queue 时，保留源队列摘要与诊断，不创建虚构 endpoint。

## New Classes and Abstractions

不新增顶层生产类、接口、服务、Manager、Handler、Processor、Context、仓储、监听器或线程设施。

- `MqDestination.RabbitQueueSettings`：新增内部不可变值类型。必要性是同时保存 RabbitMQ 队列的配置存在性、静态值和局部诊断，并避免把特殊参数塞入 `MqEndpoint` 或另建关系服务。
- `MqSpecialQueueTopologyPanelTest`：新增独立测试类。现有 `MqTopologyPanelTest` 已很大，特殊队列关系、搜索和环路应独立验收，避免继续扩大既有测试类。

parser 中只增加可命名的参数收集与值解析私有步骤，不增加单行转发方法或通用抽象。

## Implementation Boundaries

### Files to modify

- `src/main/java/cn/luckyh/mqx/model/MqDestination.java`
- `src/main/java/cn/luckyh/mqx/parser/RabbitMqEndpointParser.java`
- `src/main/java/cn/luckyh/mqx/navigation/MqNavigationService.java`
- `src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java`
- `src/test/java/cn/luckyh/mqx/parser/RabbitMqEndpointParserTest.java`
- `src/test/java/cn/luckyh/mqx/navigation/MqNavigationCacheTest.java`
- `src/test/java/cn/luckyh/mqx/ui/MqSpecialQueueTopologyPanelTest.java`
- `src/test/testData/stubs/org/springframework/amqp/core/QueueBuilder.java`
- `specs/005-rabbitmq-dead-letter-delay/*`

### Explicitly protected scope

- 不修改 `MqEndpoint` 角色、`MqEndpointMatcher` 匹配实现、gutter 行标、刷新 action、工具窗口注册、plugin.xml、依赖或插件版本。
- 不新增 `x-delayed-message`、消息级 `x-delay`、重试队列、消息重放或运行时 RabbitMQ 管理功能。
- 不修改 Kafka、RocketMQ parser 和现有普通 RabbitMQ producer/consumer 行为。
- 不修改 `<sample-project>` 参考源码及其他工作区已有未提交变更。
- 不执行用户 Spring 配置，不连接 broker，不写用户项目。

## Risks and Controls

- **Map 静态分析误判**: 只接受当前方法内线性、声明前的赋值；分支和循环标记动态，不执行代码。
- **动态特殊参数污染普通队列状态**: 队列名称状态与设置诊断分离，普通 Queue/Binding 继续可用。
- **死信目标误匹配**: 必须通过静态 DLX、路由规则与现有 Binding；无专用 key 时除无需 key 的交换机外不宣称精确目标。
- **缓存恢复遗漏字段**: 版本升级为 2，旧缓存整批回退；新增字段参加完整往返测试。
- **关系环路卡死**: 关系节点不再次递归构建关系，只展示一层现有 endpoint。
- **UI 范围膨胀**: 复用现有 Topology/Queues 树和搜索，不新增工具窗口、按钮或筛选控件。
- **PSI 与 EDT 风险**: 解析继续位于现有后台 smart read action；UI 仅消费不可变 endpoint 快照。

## Verification Design

### Test-first order

1. 扩展 QueueBuilder 测试 stub，并在 `RabbitMqEndpointParserTest` 增加用户示例形式、单参数形式、仅 TTL、仅 DLX、动态/无效 TTL、Map.of 和条件赋值测试，先获得失败证据。
2. 在 `MqNavigationCacheTest` 增加设置字段序列化往返、版本 1 回退和无效设置回退测试。
3. 新增 `MqSpecialQueueTopologyPanelTest`，覆盖完整单目标、多目标、缺失目标、跨模块、搜索、双击导航、默认交换机和环路。
4. 仅在测试失败证据确认后修改模型、parser、缓存与 UI，再执行相同 focused 测试转绿。

### Focused and regression gates

- parser：`.\gradlew.bat test --tests "cn.luckyh.mqx.parser.RabbitMqEndpointParserTest" --rerun-tasks --no-parallel`
- 缓存：`.\gradlew.bat test --tests "cn.luckyh.mqx.navigation.MqNavigationCacheTest" --rerun-tasks --no-parallel`
- 特殊队列 UI：`.\gradlew.bat test --tests "cn.luckyh.mqx.ui.MqSpecialQueueTopologyPanelTest" --rerun-tasks --no-parallel`
- 原 UI：`.\gradlew.bat test --tests "cn.luckyh.mqx.ui.MqTopologyPanelTest" --rerun-tasks --no-parallel`
- 导航与匹配：`.\gradlew.bat test --tests "cn.luckyh.mqx.navigation.MqNavigationServiceTest" --tests "cn.luckyh.mqx.match.MqEndpointMatcherTest" --rerun-tasks --no-parallel`
- 真实规模：`.\gradlew.bat test --tests "cn.luckyh.mqx.navigation.MqNavigationPerformanceTest" --rerun-tasks --no-parallel`
- broker 隔离：`.\gradlew.bat test --tests "*MqBrokerIsolationTest" --rerun-tasks --no-parallel`
- 完整测试：`.\gradlew.bat clean test --no-parallel`
- 配置与结构：`.\gradlew.bat verifyPluginProjectConfiguration verifyPluginStructure`
- 二进制兼容：`.\gradlew.bat verifyPlugin`

### Safety and reporting gates

- `verification.md` 分开记录 red/green、focused、完整测试、性能、broker 隔离、Verifier 和未执行的 sandbox/生产验证。
- 最终搜索 RabbitMQ 网络客户端与管理 API；运行依赖不得新增 MQ client。
- 最终执行 `git diff --check`、目标文件 diff 和 `git status --short`，只核对本 feature 允许文件，不覆盖既有未提交工作。

## Traceability

| Scope | Requirements | Design/Validation |
|-------|--------------|-------------------|
| US1 死信关系 | FR-001–FR-004, FR-007–FR-012, FR-016–FR-018 | parser 参数提取、关系分类、动态降级、环路保护、parser/UI tests |
| US2 TTL 延迟 | FR-001–FR-007, FR-010 | TTL 常量求值、类型派生、动态/无效诊断、parser/UI tests |
| US3 侧边栏追踪 | FR-011–FR-019 | 摘要搜索、模块树、缓存 v2、刷新回归、导航与隔离 tests |
| 量化结果 | SC-001–SC-008 | focused 样例、20 队列 UI 场景、缓存往返、2,000 endpoint p95、完整回归与 Verifier |

## Complexity Tracking

无 Constitution 例外。
