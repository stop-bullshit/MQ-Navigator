---

description: "MQ Navigator implementation tasks"
---

# Tasks: MQ 发送接收双向导航插件

**Input**: `specs/001-mq-navigation-plugin/` 下的 `spec.md`、`plan.md`、`research.md`、`data-model.md`、`contracts/` 与 `quickstart.md`

**Tests**: Constitution 要求 parser、匹配、缓存、导航、兼容性、性能和只读边界均有可执行证据。每个用户故事先增加能够暴露该故事缺失行为的测试并记录 red 结果，再开始对应实现。

**Organization**: 任务按 US1-US4 及依赖顺序组织。所有修改限定在 `<workspace>`；`<reference-plugin>` 仅作只读参考。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 直接依赖满足后，可与同阶段其他 `[P]` 任务并行，且不修改相同文件
- **[Story]**: 仅用户故事阶段使用，对应 `spec.md` 中的 US1-US4
- 每项任务都包含具体文件路径，编号按执行顺序连续

## Phase 1: Setup（共享项目骨架）

**Purpose**: 建立可编译、可测试并可由 IntelliJ IDEA 加载的 Java 17 单模块插件项目。

- [X] T001 在 `settings.gradle.kts` 配置插件仓库、依赖仓库和根项目名 `mqx-plugin`
- [X] T002 在 `build.gradle.kts` 配置 Java 17、IntelliJ Platform Gradle Plugin 2.18.1、IDEA Community 2023.3、bundled Java plugin、Lombok 1.18.36、SnakeYAML 2.6、JUnit 4.13.2、Plugin Java/light/heavy test framework、since-build 233，并将 Plugin Verifier 固定为 `IntellijIdeaCommunity, "2023.3"` 与 `IntellijIdea, "2026.2.1"`
- [X] T003 生成 Gradle 9.x Wrapper 和构建参数到 `gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar`、`gradle/wrapper/gradle-wrapper.properties` 与 `gradle.properties`
- [X] T004 [P] 创建 `src/main/resources/META-INF/plugin.xml`，声明 MQ Navigator 元数据、`com.intellij.modules.platform` 和 `com.intellij.java` 依赖，暂不注册范围外设置页或工具窗口
- [X] T005 [P] 创建主题兼容的发送端与接收端 gutter 图标 `src/main/resources/icons/mqProducer.svg` 和 `src/main/resources/icons/mqConsumer.svg`
- [X] T006 [P] 创建验证记录骨架 `specs/001-mq-navigation-plugin/verification.md`，分开预留 red/green 测试、Plugin Verifier、性能、sandbox、易用性、broker 隔离和 SHA-256 只读证据章节

**Checkpoint**: Gradle 项目可解析，插件元数据、图标和分阶段证据文件已就绪。

---

## Phase 2: Foundational（阻塞性基础能力）

**Purpose**: 建立所有用户故事共享的不可变模型、唯一 parser 接口和测试 API stub，不提前实现扫描或 UI 行为。

**CRITICAL**: 本阶段完成前不得开始用户故事实现。

- [X] T007 [P] 创建不可变目的地模型 `src/main/java/cn/luckyh/mqx/model/MqDestination.java`，显式保存 RabbitMQ、Kafka、RocketMQ 字段和必要枚举，不使用 `Map<String,String>`、Builder 或目的地类型层次
- [X] T008 [P] 创建不可变端点模型 `src/main/java/cn/luckyh/mqx/model/MqEndpoint.java`，包含系统、角色、解析状态、诊断、展示字段、快照内 ID 和 `SmartPsiElementPointer`，禁止长期保存裸 PSI
- [X] T009 创建唯一 parser 接口 `src/main/java/cn/luckyh/mqx/parser/MqEndpointParser.java`，只定义当前锚点解析与项目候选收集所需方法，不增加注册框架、Manager、Handler、Processor 或转发层
- [X] T010 [P] 在 `src/test/testData/stubs/` 创建 Spring `@Value`、RabbitMQ、Kafka 和 RocketMQ 的最小 Java API/注解 stub，供 light fixture 与后续 heavy fixture 共用且不引入三种 MQ 运行库

**Checkpoint**: 后续 parser、matcher、service 和 marker 可以共享同一模型与真实 PSI resolve stub。

---

## Phase 3: User Story 1 - 从发送端定位接收端（Priority: P1）MVP

**Goal**: 三种 MQ 的受支持 producer 显示“查找消息接收端”入口；唯一目标直达，无目标、动态或不确定关系给出明确反馈。

**Independent Test**: 每种 MQ fixture 各包含一个可静态确定的 producer 和唯一匹配 consumer，从 producer marker 触发后直接定位 consumer；改为无匹配或动态值后保持当前位置并显示原因。

### Tests for User Story 1

- [X] T011 [P] [US1] 先在 `src/test/java/cn/luckyh/mqx/parser/MqValueResolverTest.java` 与 `src/test/testData/config/` 编写失败测试，覆盖字面量、编译期常量、接收注解占位符、producer 直接 `topic`/`this.topic` 的非 static 非 final `String @Value("${...}")` 字段、默认值、同值配置、配置歧义和配置依赖收集，并排除字面 `"${...}"`、`other.topic`、static/final 字段、getter、构造参数、局部变量别名、方法返回、混合/嵌套占位符及 SpEL
- [X] T012 [P] [US1] 先在 `src/test/java/cn/luckyh/mqx/parser/RabbitMqEndpointParserTest.java` 与 `src/test/testData/rabbit/` 编写失败测试，覆盖 RabbitTemplate/AmqpTemplate resolve、send/convertAndSend 重载、默认/显式 exchange、queues/queuesToDeclare/QueueBinding、direct/topic/fanout、headers/custom 不确定状态、同名假类型和排除项
- [X] T013 [P] [US1] 先在 `src/test/java/cn/luckyh/mqx/parser/KafkaEndpointParserTest.java` 与 `src/test/testData/kafka/` 编写失败测试，覆盖 KafkaTemplate.send、KafkaListener topics/topicPattern、group 展示、无效正则，并排除 sendDefault、ProducerRecord、Message header 和跨方法传递
- [X] T014 [P] [US1] 先在 `src/test/java/cn/luckyh/mqx/parser/RocketMqEndpointParserTest.java` 与 `src/test/testData/rocketmq/` 编写失败测试，覆盖 syncSend/asyncSend/sendOneWay、`topic:tag`、RocketMQMessageListener 默认 selector、TAG/SQL92、group 展示和不支持方法族
- [X] T015 [P] [US1] 先在 `src/test/java/cn/luckyh/mqx/match/MqEndpointMatcherTest.java` 编写失败测试，覆盖跨 MQ 隔离、Rabbit 默认/direct/topic/fanout 与不确定类型、Kafka exact/pattern、Rocket topic/TAG/SQL92，确保只返回可证明的确定目标
- [X] T016 [P] [US1] 先在 `src/test/java/cn/luckyh/mqx/navigation/MqNavigationServiceTest.java` 编写失败测试，覆盖首个调用即使用项目级 `CachedValue`、producer 到 consumer 的 UNRESOLVED/NO_MATCH/ONE_MATCH/UNCERTAIN、不可变快照、项目源码范围、失效 pointer 过滤，以及 Java PSI、配置 PSI、项目根/VFS 结构变化后的缓存失效和 EndpointSnapshot 不保存裸 PsiFile
- [X] T017 [P] [US1] 先在 `src/test/java/cn/luckyh/mqx/navigation/MqLineMarkerProviderTest.java` 编写失败测试，覆盖 producer 方法名 `PsiIdentifier` marker、发送端图标/tooltip、marker 不持有目标集合、文档提交、dumb/smart mode、project 关闭取消、source/target 修改后点击同一 marker 查询当前结果、唯一目标直达和零目标/动态/不确定非阻塞提示
- [X] T018 [US1] 运行 `.\gradlew.bat test --tests '*MqValueResolverTest' --tests '*RabbitMqEndpointParserTest' --tests '*KafkaEndpointParserTest' --tests '*RocketMqEndpointParserTest' --tests '*MqEndpointMatcherTest' --tests '*MqNavigationServiceTest' --tests '*MqLineMarkerProviderTest' --rerun-tasks`，将能够暴露缺失实现的 red 结果追加到 `specs/001-mq-navigation-plugin/verification.md`，不得通过删除断言或放宽匹配边界绕过失败

### Implementation for User Story 1

- [X] T019 [P] [US1] 实现 `src/main/java/cn/luckyh/mqx/parser/MqValueResolver.java`，按字面量、`PsiVariable.computeConstantValue()`、合格直接 `@Value` 字段的顺序解析值，安全读取所属模块 application/bootstrap properties/yml/yaml 标量，并用局部 result/依赖集合返回检查过的配置 PSI，不创建配置 service 或长期裸 PSI 集合
- [X] T020 [P] [US1] 实现 `src/main/java/cn/luckyh/mqx/parser/RabbitMqEndpointParser.java`，仅按 resolve 后的完整限定类型、实际重载和受支持注解生成规范化 RabbitMQ producer/consumer 端点，不处理首版排除形式
- [X] T021 [P] [US1] 实现 `src/main/java/cn/luckyh/mqx/parser/KafkaEndpointParser.java`，解析受支持 send topic、listener topics/topicPattern 和展示 group，保留无效/动态诊断并拒绝首版排除形式
- [X] T022 [P] [US1] 实现 `src/main/java/cn/luckyh/mqx/parser/RocketMqEndpointParser.java`，解析受支持发送方法 destination 与 listener topic/group/selector，按第一个冒号拆分 tag，SQL92 只产生不确定结果
- [X] T023 [P] [US1] 实现纯匹配类 `src/main/java/cn/luckyh/mqx/match/MqEndpointMatcher.java`，集中实现 Rabbit exchange/binding、Kafka topic/pattern 和 Rocket topic/TAG 规则，不连接 broker、不执行业务代码且不复制反向规则
- [X] T024 [US1] 实现 final 项目服务 `src/main/java/cn/luckyh/mqx/navigation/MqNavigationService.java`，从 MVP 起用平台 `CachedValue` 构建不可变 EndpointSnapshot，使用平台搜索缩小 Java PSI 候选、智能指针保存位置、`CachedValueProvider.Result` 持有修改/根/配置依赖，并提供 producer 到 consumer 的当前结果；不创建 service 接口、静态状态、监听器、线程池、锁或第二套缓存
- [X] T025 [US1] 实现 `src/main/java/cn/luckyh/mqx/navigation/MqLineMarkerProvider.java` 并在 `src/main/resources/META-INF/plugin.xml` 注册 Java line marker：marker 仅保存 project、源智能指针和身份信息，点击时在 EDT 捕获位置后通过平台 non-blocking read action 等待文档提交和 smart mode、重新解析当前源端点并查询最新快照，单目标确认可导航后直达，零目标显示通知
- [X] T026 [US1] 重新运行 `.\gradlew.bat test --tests '*MqValueResolverTest' --tests '*RabbitMqEndpointParserTest' --tests '*KafkaEndpointParserTest' --tests '*RocketMqEndpointParserTest' --tests '*MqEndpointMatcherTest' --tests '*MqNavigationServiceTest' --tests '*MqLineMarkerProviderTest' --rerun-tasks`，将 green 结果和 producer 到 consumer 独立验收结论追加到 `specs/001-mq-navigation-plugin/verification.md`

**Checkpoint**: 完成 T001-T026 后形成可独立演示的 producer 到 consumer MVP，且缓存、线程和点击查询结构已是最终基线。

---

## Phase 4: User Story 2 - 从接收端反查发送端（Priority: P2）

**Goal**: 受支持 consumer 显示“查找消息发送端”入口，并复用同一匹配规则返回发送位置。

**Independent Test**: fixture 仅包含一个 consumer 和一个匹配 producer，从 consumer marker 触发后直达 producer；移除 producer 后保留当前位置并提示未找到发送端。

### Tests for User Story 2

- [X] T027 [P] [US2] 先扩展 `src/test/java/cn/luckyh/mqx/navigation/MqNavigationServiceTest.java`，增加 consumer 到 producer 的 NO_MATCH/ONE_MATCH/UNRESOLVED/UNCERTAIN 和跨消息系统排除失败测试
- [X] T028 [P] [US2] 先扩展 `src/test/java/cn/luckyh/mqx/navigation/MqLineMarkerProviderTest.java`，增加 Rabbit/Kafka 方法级 listener、Rabbit 类级 listener 和 Rocket listener 类名/方法名叶子 marker、接收端图标/tooltip、唯一 producer 导航及无 producer 提示失败测试
- [X] T029 [P] [US2] 先扩展 `src/test/java/cn/luckyh/mqx/match/MqEndpointMatcherTest.java`，验证反向查询调用同一个 producer/consumer 兼容判断且结果不因方向改变的失败用例
- [X] T030 [US2] 运行 `.\gradlew.bat test --tests '*MqNavigationServiceTest' --tests '*MqLineMarkerProviderTest' --tests '*MqEndpointMatcherTest' --rerun-tasks`，将反向服务与 receiver marker 的 red 结果追加到 `specs/001-mq-navigation-plugin/verification.md`

### Implementation for User Story 2

- [X] T031 [US2] 在 `src/main/java/cn/luckyh/mqx/navigation/MqNavigationService.java` 增加 consumer 到 producer 查询，复用 `MqEndpointMatcher` 与同一不可变快照，保留全部有效发送端且不增加第二套反向匹配实现
- [X] T032 [US2] 在 `src/main/java/cn/luckyh/mqx/navigation/MqLineMarkerProvider.java` 增加方法级和类级 consumer marker，继续使用点击时最新后台查询，唯一 producer 直达且无目标时不移动光标
- [X] T033 [US2] 重新运行 `.\gradlew.bat test --tests '*MqNavigationServiceTest' --tests '*MqLineMarkerProviderTest' --tests '*MqEndpointMatcherTest' --rerun-tasks`，将 green 结果和 consumer 到 producer 独立验收结论追加到 `specs/001-mq-navigation-plugin/verification.md`

**Checkpoint**: US2 可独立验证反向一对一与无目标行为，未复制 matcher、service 或 provider 层。

---

## Phase 5: User Story 3 - 在多个候选位置中选择（Priority: P2）

**Goal**: 双向查询出现多个有效目标时展示完整、稳定、可区分的标准候选列表，并先过滤失效位置再决定状态。

**Independent Test**: 构造一个 producer 对多个 consumer 和一个 consumer 对多个 producer，两侧 marker 均先显示全部候选；删除一个目标后该目标不再出现，选择其余候选可正确跳转。

### Tests for User Story 3

- [X] T034 [P] [US3] 先扩展 `src/test/java/cn/luckyh/mqx/navigation/MqNavigationServiceTest.java`，增加一对多、多对一、null/失效/非项目源码 pointer 过滤、过滤后状态重算及 module/file/class/member/endpointId 固定排序失败测试
- [X] T035 [P] [US3] 先扩展 `src/test/java/cn/luckyh/mqx/navigation/MqLineMarkerProviderTest.java`，增加双向 MULTIPLE_MATCHES、标准 PSI 目标弹窗、全部候选保留和 `[MQ] module / file : class#member - destination` 展示内容失败测试
- [X] T036 [US3] 运行 `.\gradlew.bat test --tests '*MqNavigationServiceTest' --tests '*MqLineMarkerProviderTest' --rerun-tasks`，将多候选与失效位置过滤的 red 结果追加到 `specs/001-mq-navigation-plugin/verification.md`

### Implementation for User Story 3

- [X] T037 [US3] 在 `src/main/java/cn/luckyh/mqx/navigation/MqNavigationService.java` 发布 NavigationOutcome 前过滤失效和非项目源码智能指针，按固定键排序，并在过滤后计算 ONE_MATCH 或 MULTIPLE_MATCHES
- [X] T038 [P] [US3] 创建 `src/main/java/cn/luckyh/mqx/navigation/MqTargetRenderer.java`，使用 `PsiTargetPresentationRenderer` 语义展示 MQ、模块、文件、类、成员和目的地摘要，不创建 Swing/ListPopup 或 chooser 包装层
- [X] T039 [US3] 在 `src/main/java/cn/luckyh/mqx/navigation/MqLineMarkerProvider.java` 使用 `NavigationUtil` 标准 PSI 目标弹窗和 `MqTargetRenderer` 处理多目标，单目标仍直接导航且不得截断同等有效候选
- [X] T040 [US3] 重新运行 `.\gradlew.bat test --tests '*MqNavigationServiceTest' --tests '*MqLineMarkerProviderTest' --rerun-tasks`，将 green 结果、双向候选数量和稳定顺序结论追加到 `specs/001-mq-navigation-plugin/verification.md`

**Checkpoint**: US3 可独立验证双向多候选、标准选择交互、稳定顺序和失效目标过滤。

---

## Phase 6: User Story 4 - 获得最新且可信的导航结果（Priority: P3）

**Goal**: 源码、配置和项目根变化后旧快照自动失效，同一已显示 marker 点击当前结果；用户可主动 Rescan，indexing 和局部语法错误不破坏其他导航。

**Independent Test**: marker 显示后依次新增、修改、移动、重命名和删除端点或配置，不等待 marker 重建直接再次点击同一 marker，结果使用已提交的当前状态；主动 Rescan 后完全一致，dumb mode 下不访问索引。

### Tests for User Story 4

- [X] T041 [P] [US4] 先扩展 `src/test/java/cn/luckyh/mqx/navigation/MqNavigationServiceTest.java`，增加主动刷新计数按 Project 隔离、`requestRefresh` 使当前 CachedValue 失效并保留其他 Project 状态，以及删除/移动/重命名后的综合刷新测试
- [X] T042 [P] [US4] 先扩展 `src/test/java/cn/luckyh/mqx/navigation/MqLineMarkerProviderTest.java`，增加 source/target/config 连续修改后点击同一 marker 的回归场景，并验证执行 Rescan 后重启 daemon、当前结果不变旧且不产生逐 marker 队列
- [X] T043 [P] [US4] 先扩展 `src/test/java/cn/luckyh/mqx/parser/RabbitMqEndpointParserTest.java`、`src/test/java/cn/luckyh/mqx/parser/KafkaEndpointParserTest.java` 和 `src/test/java/cn/luckyh/mqx/parser/RocketMqEndpointParserTest.java`，增加单文件语法/resolve 失败隔离测试，证明其他端点继续进入快照
- [X] T044 [US4] 运行 `.\gradlew.bat test --tests '*MqNavigationServiceTest' --tests '*MqLineMarkerProviderTest' --tests '*RabbitMqEndpointParserTest' --tests '*KafkaEndpointParserTest' --tests '*RocketMqEndpointParserTest' --rerun-tasks`，将 US1 已有缓存/marker 基线的通过结果与尚缺的主动 Rescan、parser 错误隔离 red 结果分别追加到 `specs/001-mq-navigation-plugin/verification.md`

### Implementation for User Story 4

- [X] T045 [US4] 在 `src/main/java/cn/luckyh/mqx/navigation/MqNavigationService.java` 增加按 Project 隔离的手动刷新计数和 `requestRefresh` 入口，使当前 CachedValue 失效；不得重复 US1 已实现的 PSI/config/root 依赖或创建第二套缓存
- [X] T046 [US4] 在 `src/main/java/cn/luckyh/mqx/parser/RabbitMqEndpointParser.java`、`src/main/java/cn/luckyh/mqx/parser/KafkaEndpointParser.java` 和 `src/main/java/cn/luckyh/mqx/parser/RocketMqEndpointParser.java` 局部隔离单文件语法/resolve 失败，使其他候选继续进入当前快照
- [X] T047 [US4] 创建 `src/main/java/cn/luckyh/mqx/action/RefreshMqIndexAction.java`，调用 `MqNavigationService.requestRefresh` 并重启 daemon 分析，不扫描外部仓库、不写项目文件、不连接 broker
- [X] T048 [US4] 在 `src/main/resources/META-INF/plugin.xml` 注册 `Tools > MQ Navigator > Rescan Endpoints` action 并绑定 `RefreshMqIndexAction`，不增加持久化配置或设置页
- [X] T049 [US4] 运行 `.\gradlew.bat test --tests '*MqNavigationServiceTest' --tests '*MqLineMarkerProviderTest' --tests '*RabbitMqEndpointParserTest' --tests '*KafkaEndpointParserTest' --tests '*RocketMqEndpointParserTest' --rerun-tasks`，将 US4 green 结果及 US1-US3 回归、同一 marker 最新查询和主动 Rescan 结论追加到 `specs/001-mq-navigation-plugin/verification.md`

**Checkpoint**: US4 可独立验证自动/主动刷新、点击时最新结果、错误隔离和 indexing 安全，且仍只有一个平台缓存。

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: 完成文档、真实多模块性能、兼容性、sandbox、易用性与只读交付门禁，不扩大首版范围。

- [X] T050 [P] 创建 `README.md`，说明 RabbitMQ/Kafka/RocketMQ 支持矩阵、运行方式、双向 gutter、主动 Rescan、动态诊断和明确排除范围
- [X] T051 [P] 创建可复制到 `build/manual-sandbox-project` 的 `src/test/testData/manual-project/`，用最小 Java/配置样例覆盖 `specs/001-mq-navigation-plugin/quickstart.md` 场景 A-F，且不依赖真实 MQ 服务
- [X] T052 [P] 在 `src/test/testData/performance/templates/` 创建六类 producer/consumer 模板，复用 `src/test/testData/stubs/` 的最小 API，测试运行时生成重复源码，不提交 2,000 份样例且不新增生产生成器
- [X] T053 在 `src/test/java/cn/luckyh/mqx/navigation/MqNavigationPerformanceTest.java` 创建唯一 heavy fixture：50 个真实 IntelliJ Java Module 各有独立 content root/source root，每模块 Rabbit 7+7、Kafka 7+7、Rocket 6+6 共 40 端点，并先断言恰好 50 模块、2,000 端点及系统/方向分布
- [X] T054 在 `src/test/java/cn/luckyh/mqx/navigation/MqNavigationPerformanceTest.java` 增加 JUnit 4 六分钟总超时、1 次快照、20 次导航、3 次编辑/commit/刷新预热、200 次导航和 40 次刷新确定性采样，按 nearest-rank 断言 navigation p95 小于 1 秒、refresh p95 小于 3 秒，并输出 n/p50/p95/max、IDE/JBR/OS/CPU/heap、初始化与首次快照耗时
- [X] T055 运行 `.\gradlew.bat clean test` 及 `.\gradlew.bat test --tests '*MqNavigationPerformanceTest' --rerun-tasks --no-parallel`，将功能测试数量、50/2,000 分布、200/40 样本、p50/p95/max、JUnit 六分钟门禁和失败原因追加到 `specs/001-mq-navigation-plugin/verification.md`；性能失败只记录数据与 profiling 证据，不直接引入自定义索引或第二套缓存
- [X] T056 运行 `.\gradlew.bat verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin`，将 IDEA Community 2023.3 和 IDEA 2026.2.1 的结果追加到 `specs/001-mq-navigation-plugin/verification.md`，把网络/下载阻塞与二进制兼容结论分开记录
- [ ] T057 运行 `.\gradlew.bat runIde`，按 `specs/001-mq-navigation-plugin/quickstart.md` 在一次性 manual sandbox 副本完成场景 A-F，并把单/双向、同一 marker、多候选、动态提示、Rescan、indexing 和错误隔离结果追加到 `specs/001-mq-navigation-plugin/verification.md`
- [ ] T058 组织至少 10 名首次使用 MQ Navigator 的目标开发人员，从同一 producer 文件且 marker 已显示的起点执行 producer→consumer→producer；从发出指令到返回 producer 计时，期间不得查阅说明，并将逐人耗时、失败原因和至少 9/10 在 5 秒内完成的结论写入 `specs/001-mq-navigation-plugin/verification.md`
- [X] T059 创建 `src/test/java/cn/luckyh/mqx/navigation/MqBrokerIsolationTest.java` 并运行 `.\gradlew.bat test --tests '*MqBrokerIsolationTest' --rerun-tasks`：三个同步环回 TCP sentinel 接收数必须均为零；同时按 `specs/001-mq-navigation-plugin/quickstart.md` 检查运行依赖、生产源码直接网络 API，并比较规范 fixture、分阶段 manual sandbox 的 SHA-256 清单，将 broker 与文件证据追加到 `specs/001-mq-navigation-plugin/verification.md`
- [X] T060 审查 `src/main/java/cn/luckyh/mqx/` 与 `src/test/java/cn/luckyh/mqx/`，移除不必要接口、转发层、一次性 trivial 方法、嵌套分支和范围外能力，确认生产源码无直接网络 API、裸 PSI、静态项目状态、自定义索引/监听器/线程池/锁，并把审查结论写入 `specs/001-mq-navigation-plugin/verification.md`
- [ ] T061 在最终审查后重新运行 `.\gradlew.bat clean test`、`.\gradlew.bat test --tests '*MqNavigationPerformanceTest' --rerun-tasks --no-parallel`、`.\gradlew.bat verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin` 和必要的 `.\gradlew.bat runIde` 回归，将已验证、未验证、环境阻塞与生产验证状态汇总到 `specs/001-mq-navigation-plugin/verification.md`

**Checkpoint**: 功能、性能、兼容性、sandbox、易用性、broker 隔离和项目只读证据齐全；任何未通过项均明确记录而非推断完成。

---

## Phase 8: Real-Project RabbitMQ Queue Declarations

**Purpose**: 根据真实多模块项目中的 `QueueBuilder.durable(CONSTANT)`、`BindingBuilder.bind(...).to(...)`、exchange 声明和封装发送方法，补齐队列到交换机再到 sender 的导航。

- [X] T062 在 `RabbitMqEndpointParserTest`、`MqNavigationServiceTest` 和 `MqLineMarkerProviderTest` 先增加跨文件编译期常量、声明双向导航及直接 sender/consumer 优先级回归，并记录缺少 `DECLARATION` 角色的 red 编译结果
- [X] T063 在现有 `MqEndpoint`、`RabbitMqEndpointParser`、`MqEndpointMatcher`、`MqNavigationService` 和 `MqLineMarkerProvider` 内实现静态队列声明，不新增生产类、索引、监听器或缓存
- [X] T064 更新 README、spec/plan/data-model/detection 文档、Spring AMQP 测试 stub 和 manual sandbox，记录 `QueueBuilder`、`BindingBuilder`、`ExchangeBuilder` 及源码级 Spring AMQP 发送封装的支持边界
- [X] T065 运行 Rabbit 定向测试、完整 `clean test`、50 模块性能测试和 Plugin Verifier，并将真实项目回归及最终交付状态写入 `verification.md`

---

## Phase 9: RabbitMQ Module View and Enum Routing Keys

**Purpose**: 修复 `OrderEventListener` 一类枚举 routing key 链路，并把 RabbitMQ 项目关系按模块和收发端展示、筛选。

- [X] T066 在 `MqValueResolverTest` 和 `MqNavigationServiceTest` 增加普通 getter、Lombok getter 等价枚举场景，以及 listener→binding→exchange→producer 完整回归
- [X] T067 在 `MqValueResolver` 通用解析枚举常量无参 String getter 到构造参数的静态映射；无法证明时保持 dynamic，不写项目专用枚举名或 getter 名
- [X] T068 在 `MqTopologyPanel` 改为模块→Consumers/Producers/Topology 分组，增加文本与类型筛选，并展示 consumer、binding、producer 的可导航关系
- [X] T069 使用 16px Tool Window 和刷新工具栏图标；0.2.11 已用项目自有 MQ 图标替换早期第三方图标
- [X] T069a 按 SemVer 将插件版本从 `0.1.0` 升级为 `0.2.0`，并同步构建产物与验证记录
- [X] T069b 修复 Tool Window producer 节点首次双击只打开文件、再次双击才精确定位的问题，并按 SemVer 发布 `0.2.1`
- [X] T069c 接入 `gutter-consumer.png`、`gutter-producer.png`、`mq-navigator-plugin-logo.png` 和 `mq-navigator-logo-lockup.png`，并按 SemVer 发布 `0.2.2`
- [X] T069d 使用标准 `META-INF/pluginIcon.svg`，将 Tool Window、刷新动作和 gutter 图标统一切换为 SVG，并按 SemVer 发布 `0.2.3`
- [ ] T070 在目标真实项目重新安装插件，验证 `OrderEventListener#consumeOrderEvent` gutter 和侧边栏都能跳转到 `OrderEventService` producer

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 Setup**: 无依赖；T004-T006 可在 T001 后并行准备，T003 在 Gradle 配置明确后生成。
- **Phase 2 Foundational**: 依赖 Phase 1，完成后阻塞门禁解除。
- **Phase 3 US1**: 依赖 Phase 2，是 MVP；测试 T011-T017 全部先于实现 T019-T025，T018 是 red gate。
- **Phase 4 US2**: 依赖 US1 的 matcher、项目服务和 provider；测试和验收仍以 receiver 入口独立完成。
- **Phase 5 US3**: 依赖 US1/US2 的双向查询，只增加候选状态、稳定排序和标准弹窗，不改变匹配规则。
- **Phase 6 US4**: 依赖已存在的 MVP CachedValue 与点击处理，补齐变更、主动刷新、dumb mode 和错误隔离证据。
- **Phase 7 Polish**: 依赖计划交付的所有用户故事；性能失败不得在本阶段越过 plan 引入复杂机制。

### User Story Dependency Graph

```text
Setup -> Foundational -> US1 (MVP) -> US2 -> US3 -> US4 -> Polish
```

此顺序反映单模块共享 service/provider 的最小实现依赖。每个故事都有独立测试条件和 red/green 证据，可在对应 checkpoint 停止验收。

### Within Each User Story

1. 写入该故事的测试与 fixture。
2. 运行新增测试并记录 red 证据；不得删除断言求绿。
3. 完成纯值解析/parser/matcher，再实现 service，最后接入 gutter/UI。
4. 运行故事测试和既有故事回归并记录 green 证据。
5. 当前故事 checkpoint 通过后再进入下一故事。

### Parallel Opportunities

- Setup: T004-T006 修改不同文件，可并行。
- Foundational: T007、T008、T010 可并行；完成模型后执行 T009。
- US1: T011-T017 可并行编写；red gate 后 T019 与 T023 可并行，T019 完成后 T020-T022 三个 parser 可并行，T024-T025 顺序集成。
- US2: T027-T029 修改三个不同测试文件，可并行。
- US3: T034-T035 可并行；red gate 后 T037 与 T038 修改不同生产文件，可并行，T039 最后集成。
- US4: T041-T043 修改不同测试文件，可并行；red gate 后依次完成 T045 手动刷新、T046 parser 隔离、T047 action 和 T048 注册。
- Polish: T050-T052 可并行准备；T055-T061 串行记录证据，避免后续改动使早期结论失效。

---

## Parallel Execution Examples

### User Story 1

```text
并行测试：T011 value resolver | T012 Rabbit parser | T013 Kafka parser | T014 Rocket parser | T015 matcher | T016 service | T017 line marker
red gate：T018
并行实现第一批：T019 value resolver | T023 matcher
并行实现第二批：T020 Rabbit parser | T021 Kafka parser | T022 Rocket parser
汇合：T024 navigation service -> T025 producer marker -> T026 green gate
```

### User Story 2

```text
并行测试：T027 reverse service | T028 receiver marker | T029 symmetric matcher
汇合：T030 red gate -> T031 reverse query -> T032 receiver marker -> T033 green gate
```

### User Story 3

```text
并行测试：T034 multiple service | T035 multiple marker
并行实现：T037 filter/sort | T038 target renderer
汇合：T039 standard popup -> T040 green gate
```

### User Story 4

```text
并行测试：T041 invalidation | T042 same-marker/dumb-mode | T043 parser isolation
汇合：T044 red gate -> T045 manual refresh -> T046 parser isolation -> T047 refresh action -> T048 registration -> T049 green gate
```

---

## Implementation Strategy

### MVP First（仅 User Story 1）

1. 完成 T001-T006（Setup）。
2. 完成 T007-T010（Foundational）。
3. 完成 T011-T026（US1）。
4. 停止扩展，复核 US1 red/green 证据并在三种 MQ 的单 producer/consumer fixture 上独立验收。
5. 只有 MVP 通过后再进入反向导航。

### Incremental Delivery

1. **T001-T026**: producer 到 consumer MVP，已包含最终缓存和点击线程基线。
2. **T027-T033**: 增加 consumer 到 producer，不复制匹配规则。
3. **T034-T040**: 增加双向多候选、稳定展示和失效过滤。
4. **T041-T049**: 增加变更期最新结果、主动 Rescan、dumb mode 和错误隔离。
5. **T050-T061**: 完成文档、真实性能、兼容性、sandbox、易用性和只读证据。

### Scope Guardrails During Execution

- 不修改 `<reference-plugin>`。
- 不增加 Kotlin/Groovy/UAST、自定义索引、PSI 监听器、自定义线程池、锁、第二套缓存、设置页或运行时 MQ 能力；RabbitMQ 工具窗口复用同一项目快照。
- 仅 `MqEndpointParser` 使用接口，因为首版同时存在三个真实实现；`MqNavigationService` 保持 final 具体 project service。
- 配置依赖仅存在于一次 `CachedValueProvider.Result` 计算，EndpointSnapshot 不保存裸 PsiFile。
- marker 不保存创建时目标集合；每次点击基于源智能指针和已提交的当前项目状态重新查询。
- 若真实性能门槛失败，先在 `verification.md` 保留原始数据和 profiling 证据，再更新 plan；当前任务不得直接加入复杂机制。

## Notes

- `[P]` 仅表示直接依赖满足后允许并行，不得跨过阶段 red/green checkpoint。
- 新增测试必须先运行并证明能暴露该故事尚缺行为；实现后再运行至通过。
- 每项任务或紧密相关小组完成后单独核验，避免把多个用户故事混在一次变更中；当前项目未初始化 Git，不预设提交动作。
- 未支持形式必须给出动态/不确定诊断或明确非匹配，不能以近似跳转代替。
