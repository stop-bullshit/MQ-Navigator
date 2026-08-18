# Implementation Plan: MQ 发送接收双向导航插件

**Branch**: `001-mq-navigation-plugin`（Spec Kit 逻辑分支；当前新项目尚未初始化 Git） | **Date**: 2026-08-17 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-mq-navigation-plugin/spec.md`

## Summary

在独立 IDEA 插件 `MQ Navigator` 中识别 RabbitMQ、Kafka、RocketMQ 的静态消息发送端和接收端，并识别 RabbitMQ `QueueBuilder.durable(...)` 队列及 `BindingBuilder`/`ExchangeBuilder` 静态拓扑，在代码 gutter 提供双向导航，同时通过 RabbitMQ 工具窗口按模块和收发端展示、筛选源码关系。实现以 Java PSI 和平台现有索引缩小候选范围，将端点统一为不可变模型，再按三种消息系统各自的真实路由规则匹配。项目级服务通过平台 `CachedValue` 管理扫描快照，长期保存的 PSI 位置使用智能指针；源码或配置变化后由平台修改计数自动失效，用户也可主动刷新。首版不连接消息服务、不执行 Spring 上下文、不引入自定义索引、监听器、线程池或静态全局缓存。

## Technical Context

**Language/Version**: Java 17；Gradle Kotlin DSL，Gradle Wrapper 9.x

**Primary Dependencies**: IntelliJ Platform Gradle Plugin `2.18.1`；IntelliJ IDEA Community `2023.3`；bundled Java plugin；Lombok `1.18.36`（仅减少不可变模型样板，不使用 Builder）；SnakeYAML `2.6`（安全读取项目 YAML 标量配置）

**Storage**: 仅项目生命周期内的不可变内存快照；使用 IntelliJ `CachedValue`，不持久化用户数据

**Testing**: IntelliJ `TestFrameworkType.Plugin.Java`、JUnit `4.13.2`；Parser/Matcher/Service/LineMarker 与 broker 隔离使用轻量 fixture，唯一的多模块性能测试使用 heavy fixture；Plugin Verifier 和 sandbox `runIde` 人工验证

**Target Platform**: IntelliJ IDEA 2023.3+，最低 build `233`；插件字节码保持 Java 17，后续 IDE 使用各自附带的 JBR

**Project Type**: 单模块桌面 IDE 插件

**Performance Goals**: 初次扫描完成后，在 50 个真实 IntelliJ Java 源码模块、2,000 个端点的验收项目中，导航查询 p95 小于 1 秒；源码提交后刷新查询 p95 小于 3 秒

**Constraints**: 项目级搜索不得在 EDT 执行；索引未就绪时不查询索引；不得保存裸 PSI 长期引用；不得修改业务源码、连接 broker 或发送/消费消息；首版不得添加自定义文件索引、线程池、锁或通用中间层

**Scale/Scope**: 当前打开项目的源码模块；RabbitMQ、Kafka、RocketMQ 静态端点；以 50 个真实 IntelliJ Java 源码模块、2,000 端点作为首轮性能门槛

## Constitution Check

项目 Constitution 已批准为 1.0.0。本计划同时保留用户提供的 `AGENTS.md` 编码规范；两者冲突时以 Constitution 为最高项目规则。

| Principle | Pre-Research | Post-Design | Evidence |
|-----------|--------------|-------------|----------|
| I. 最小范围与直接实现 | PASS | PASS | 单模块、一个项目服务；仅 `MqEndpointParser` 有三个现实实现，不增加 Manager/Handler/Context 链 |
| II. 平台优先与证据驱动复杂度 | PASS | PASS | 复用 Java PSI、平台搜索/索引、CachedValue、智能指针、daemon 和标准导航；不引入自定义索引/监听器/线程池/锁 |
| III. PSI、索引与并发安全 | PASS | PASS | 计划规定项目搜索不在 EDT、dumb mode 不访问索引、长期位置仅保存智能指针、状态按 Project 隔离 |
| IV. 用户项目只读与运行时隔离 | PASS | PASS | 仅分析当前项目 PSI/配置，不执行 Spring 上下文；本地 TCP sentinel 断言三种 broker 连接尝试为零，分阶段 SHA-256 证明用户文件零改动，FeignX 保持只读 |
| V. 测试先行与量化证据 | PASS | PASS | 除唯一 heavy 性能测试外均用轻量 fixture；性能门槛固定为 50 个真实模块、2,000 端点、200/40 样本、nearest-rank p95 和 JUnit 六分钟总超时；自动化只计完整导航结果计算，用户可见体验由固定试用流程验证 |
| VI. 可追踪的增量交付 | PASS | PASS | 每个用户故事可独立验证；点击时最新结果、MVP 缓存、真实多模块基准和配置字段边界均已映射到设计与验收，后续 `/speckit-tasks` 必须据此重生成任务 |

没有 Constitution 例外，也没有未解决的 `NEEDS CLARIFICATION`。Phase 0 研究与 Phase 1 设计已经关闭此前的阻塞门禁。

## Project Structure

### Documentation (this feature)

```text
specs/001-mq-navigation-plugin/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── verification.md           # 实现阶段分开记录各类验证证据
├── contracts/
│   ├── mq-detection.md
│   └── navigation-ui.md
└── tasks.md                 # 已生成的依赖顺序实现清单
```

### Source Code (repository root)

```text
build.gradle.kts
settings.gradle.kts
gradle.properties
README.md
gradle/
└── wrapper/

src/main/java/cn/luckyh/mqx/
├── model/
│   ├── MqEndpoint.java
│   └── MqDestination.java
├── parser/
│   ├── MqEndpointParser.java
│   ├── RabbitMqEndpointParser.java
│   ├── KafkaEndpointParser.java
│   ├── RocketMqEndpointParser.java
│   └── MqValueResolver.java
├── match/
│   └── MqEndpointMatcher.java
├── navigation/
│   ├── MqNavigationService.java
│   ├── MqLineMarkerProvider.java
│   └── MqTargetRenderer.java
├── ui/
│   ├── MqToolWindowFactory.java
│   ├── MqToolWindowService.java
│   └── MqTopologyPanel.java
└── action/
    └── RefreshMqIndexAction.java

src/main/resources/
├── META-INF/
│   └── plugin.xml
└── icons/
    ├── mqProducer.svg
    └── mqConsumer.svg

src/test/java/cn/luckyh/mqx/
├── parser/
│   ├── RabbitMqEndpointParserTest.java
│   ├── KafkaEndpointParserTest.java
│   ├── RocketMqEndpointParserTest.java
│   └── MqValueResolverTest.java
├── match/
│   └── MqEndpointMatcherTest.java
└── navigation/
    ├── MqNavigationServiceTest.java
    ├── MqLineMarkerProviderTest.java
    ├── MqNavigationPerformanceTest.java
    └── MqBrokerIsolationTest.java

src/test/testData/
├── stubs/                   # light/heavy fixture 共用的最小框架 API
├── rabbit/
├── kafka/
├── rocketmq/
├── config/
├── manual-project/
└── performance/
    └── templates/           # 六类重复源码模板，测试运行时生成 50 个真实模块
```

**Structure Decision**: 使用单模块插件。解析器按消息系统拆分是因为三者入口和匹配信息确实不同；缓存、扫描协调和查询统一放在一个项目级服务中。首版不创建独立 scanner、cache manager、listener、recover、chooser 或配置 service。

## Design Flow

```text
Java leaf element
    → MqLineMarkerProvider creates LineMarkerInfo + source smart pointer
    → gutter click schedules non-blocking read action with committed documents and smart mode
    → MqNavigationService (project-scoped CachedValue, present from the first MVP story)
    → 3 MqEndpointParser implementations + MqValueResolver
    → immutable endpoint snapshot + SmartPsiElementPointer
    → MqEndpointMatcher
    → latest zero / one / many navigation result
    → notification / direct navigation / standard IntelliJ target popup
```

- marker 只保存当前源码锚点的智能指针和查询键，不保存创建 marker 时的目标集合；同一个可见 marker 每次点击都查询当前 `CachedValue` 快照。
- 点击处理在 EDT 捕获弹窗位置，然后通过平台 non-blocking read action 等待文档提交与 smart mode；项目关闭时自动取消，不创建监听器或自定义线程池。
- 零目标或动态值：保留可点击 marker，点击后给出方向明确的非阻塞提示。
- 单目标：确认目标仍可导航后直接跳转。
- 多目标：使用 `NavigationUtil` 的标准目标弹窗，并由实现 `PsiTargetPresentationRenderer` 语义的 `MqTargetRenderer` 补充模块、文件、类和方法信息。
- dumb mode：provider 不访问索引、不注册逐元素延迟任务；索引恢复后由代码分析重新计算 marker。

## New Classes and Abstractions

| Type | Responsibility | Why it is necessary |
|------|----------------|---------------------|
| `MqEndpoint` | 保存端点角色、消息系统、解析状态、源码智能指针和展示信息 | 发送端与接收端需要同一份不可变模型才能双向查询 |
| `MqDestination` | 明确保存 topic、exchange、queue、routing key、tag/selector、group 等字段 | 避免把三种路由错误压成一个字符串；仍保持单个具体模型，不创建目的地类型层次 |
| `MqEndpointParser` | 定义当前端点解析和项目候选收集两个步骤 | 有三个同时存在的真实实现；这是唯一新增接口，不为未来扩展预留注册框架 |
| 三个具体 Parser | 按各自完整限定类型识别发送方法和接收注解 | 三类框架的参数位置、注解字段和静态边界不同，拆分可独立测试并避免一个超长条件类 |
| `MqValueResolver` | 解析字面量、Java 编译期常量、可证明的枚举常量字符串 getter、直接引用的 `@Value` 实例字段和唯一配置标量 | 值解析跨 PSI 与资源文件，属于可独立测试的完整步骤；枚举 getter 只读取源码中可映射到构造参数的静态字符串，不执行方法或追踪普通 getter、局部变量别名、方法返回或跨方法数据流 |
| `MqEndpointMatcher` | 实现 Rabbit exchange、Kafka topic/pattern、Rocket topic/tag 匹配 | 三类规则复杂且需要纯单元测试，不放进 UI 或扫描代码 |
| `MqNavigationService` | 从首个 MVP 故事开始提供项目级快照、缓存失效、稳定排序和反向查询 | 状态必须按 project 隔离；使用 final light service，不创建 service interface |
| `MqLineMarkerProvider` | 识别叶子锚点，创建不携带目标快照的 gutter marker，并在点击时发起最新查询 | IntelliJ 扩展点要求独立实现类；点击处理保持在该类，不新增 navigation handler 层 |
| `MqTargetRenderer` | 通过平台目标展示 API 为多候选补充区分信息 | 直接对应“模块、文件、代码位置”验收要求，不承担其他业务逻辑 |
| `RefreshMqIndexAction` | 用户主动失效快照并重启代码分析 | 直接满足主动重新扫描要求；框架要求独立 Action 类 |
| `MqToolWindowFactory` / `MqToolWindowService` / `MqTopologyPanel` | 展示按模块和收发端分类的 RabbitMQ 端点、绑定关系与筛选结果 | 工具窗口扩展点需要工厂；项目服务保存窗口实例；面板只消费现有快照，不增加第二套扫描或缓存 |

以上是首版全部生产类与抽象。配置依赖可由 `MqValueResolver` 的 private/internal record 或局部集合在一次缓存计算中返回；它不是新接口、Spring Bean 或长期模型，并在 `CachedValueProvider.Result` 创建后立即释放。

## Implementation Boundaries

### Files to create or modify

- 仅创建 `D:\github\mqx-plugin` 下的 Gradle、插件描述、源码、测试与本功能文档。
- 不复制 FeignX 的包名结构、静态缓存实现或监听 Handler；只复用已验证的交互思想。
- 不修改 `D:\github\feignx-plugin` 中任何现有或未提交文件。

### Explicitly protected scope

- 不支持 Kotlin/Groovy/UAST。
- RabbitMQ 编程式拓扑支持 `QueueBuilder.durable(String)`、`BindingBuilder.bind(queueMethod()).to(exchangeMethod()).with(routingKey)`，以及 `new *Exchange(String)` / `ExchangeBuilder.*Exchange(String)` 的静态名称；headers/custom exchange、动态 bean 图仍不支持。Kafka `ProducerRecord`/消息 header、RocketMQ orderly/transaction/request-reply 不支持。
- 不解析 SpEL、Spring Bean 图、跨方法数据流、环境变量、Config Server 或运行时 profile。
- producer 额外支持 destination 参数直接引用本类普通 `String` 实例字段，且该字段直接标注 Spring `@Value` 的单一 `${...}` 占位符；也支持枚举常量无参 getter 在源码中明确返回 String 字段、且该字段能映射到枚举常量构造参数的形式。不支持 `Outer.this`、其他对象字段、static/final `@Value` 字段、普通 getter、构造参数流、局部变量别名、混合文本或嵌套占位符。
- 不连接 broker，不做消息轨迹、积压或运行状态查询。
- 不新增设置页、持久化配置或 Marketplace 发布流程；RabbitMQ 工具窗口只读取现有项目快照。

## Risks and Controls

- **RabbitMQ 拓扑不完整**: `@RabbitListener(queues=...)` 无法证明命名 exchange 的绑定；只对默认 exchange 或显式 `@QueueBinding` 建立确定匹配，其余提示无法静态确认。
- **配置多 profile 歧义**: 同一 key 出现多个不同标量值时不猜测，端点保留但不建立确定连线。
- **缓存失效范围偏宽**: `PsiModificationTracker.MODIFICATION_COUNT` 正确性优先，可能导致频繁重算；先用 50 个真实 IntelliJ Java 模块/2,000 端点基准验证，只有证据不足时才规划更细依赖或自定义索引。
- **IDE 跨版本兼容**: 以 2023.3 编译、开放后续版本支持会承担 API 演进风险；Plugin Verifier 门禁固定覆盖 IDEA 2023.3 与计划核实时的最新稳定版 IDEA 2026.2.1，升级该版本必须显式修改计划。
- **动态路由误判**: 动态值、Rabbit headers/custom exchange、Rocket SQL92 只展示诊断或 topic 级候选，不标记为确定接收关系。

## Verification Design

### Functional and compatibility gates

- Parser、值解析、Matcher、服务缓存与 LineMarker 行为使用 light fixture/纯 JUnit，三种 MQ 均覆盖零、单、多目标及正反向导航。
- `MqBrokerIsolationTest` 使用三个绑定环回地址的本地 TCP sentinel，将端口写入测试项目配置后执行扫描、双向查询和主动刷新；sentinel 使用带超时的同步 accept，不创建生产线程，并断言三种连接尝试均为零。构建依赖检查同时确认插件不打包三种 MQ client。
- `verifyPlugin` 固定验证 IDEA Community 2023.3 与 IDEA 2026.2.1，不使用随时间漂移的 `latest()` 作为发布门禁。
- sandbox 人工验收必须复用同一个已显示 marker：修改或删除目标后不等待 marker 重建，直接再次点击并确认使用最新结果。

### One heavy performance gate

- 仅 `MqNavigationPerformanceTest` 使用 heavy fixture，创建 50 个真实 `Module`，每个模块独立 content root 与 `src/main/java`；不导入 Gradle/Maven 项目。
- 每模块固定 40 个端点：RabbitMQ 7 producer + 7 consumer、Kafka 7 + 7、RocketMQ 6 + 6，合计 2,000；重复源码由测试类基于少量模板写入 fixture 临时目录，不新增生产生成器。
- 首次索引与首次快照单独记录但不计入 p95。预热为 1 次完整快照、20 次导航、3 次编辑/commit/刷新循环。
- 导航确定性采样 200 次，从调用查询服务到 `NavigationOutcome` 完成、失效指针过滤并稳定排序；刷新采样 40 次，从 write action 修改并 commit 文档后开始，到索引可用、缓存重算并返回新结果为止。两类查询都在非 EDT read action 执行。
- p95 使用 nearest-rank：`ceil(0.95 × n) - 1`；严格断言导航 `< 1_000_000_000 ns`、刷新 `< 3_000_000_000 ns`。唯一 heavy 测试使用 JUnit 4 六分钟总超时，命令使用 `--rerun-tasks --no-parallel`，并输出 n/p50/p95/max 与运行环境。

### User-project source safety

- 手工验收只打开 `build/manual-sandbox-project` 中的一次性副本，不直接打开规范 fixture。
- 对受保护的 Java、properties、YAML 与构建文件生成按相对路径排序的 SHA-256 清单，清单写在被检查目录外；排除 `.idea`、`.gradle`、`build`、`out` 和 `*.iml` 等 IDE/构建产物。
- 无用户编辑的导航/Rescan 场景比较操作前后清单；有意编辑场景先在编辑完成并提交后重建基线，再比较导航/Rescan 前后清单。差异检查必须同时发现新增、删除和内容变化。

### Broker runtime isolation

- 自动化 sentinel 测试统计扫描、双向查询和 Rescan 期间对 RabbitMQ、Kafka、RocketMQ 测试地址的 TCP 连接尝试，三者必须均为零。
- Gradle 运行依赖报告不得包含 Spring AMQP/RabbitMQ client、Kafka client 或 RocketMQ client，生产源码不得直接使用网络 API；这两项检查与 TCP sentinel 结果分别记录。
