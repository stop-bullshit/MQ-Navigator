# Phase 0 Research: MQ 发送接收双向导航插件

**Verified**: 2026-08-17

本文件记录实现计划中的技术决策。所有待定项已解决，没有剩余 `NEEDS CLARIFICATION`。

## Decision 1: 以最低支持的 IntelliJ 版本编译

**Decision**:

- 使用 IntelliJ Platform Gradle Plugin `2.18.1`、Gradle Wrapper 9.x 和构建 JDK 17。
- 编译目标为 IntelliJ IDEA Community 2023.3（build branch `233`），插件字节码为 Java 17。
- Gradle 声明 bundled Java plugin，`plugin.xml` 同步依赖 `com.intellij.java`。
- `sinceBuild` 设置为 `233`，不固定只支持 `233.*`；Plugin Verifier 门禁固定覆盖 IDEA Community 2023.3 与本次计划核实的最新稳定版 IDEA 2026.2.1（build `262.9437.185`），不使用动态 `latest()`。
- verifier 目标显式声明为 `IntellijIdeaCommunity, "2023.3"` 与 `IntellijIdea, "2026.2.1"`；2025.3 及以后使用统一 IntelliJ IDEA 产品类型。
- 后续 IDE 使用各发行版附带的 JBR，不能强迫 2024.2+ 使用 JDK 17 运行 IDE。

**Rationale**: JetBrains 官方 2.x 文档当前列出的稳定插件版本为 2.18.1，最低要求 IntelliJ Platform 2023.3、Gradle 9.0、Java runtime 17。面向最低平台编译能避免意外调用新平台 API，同时 Java 17 字节码可在后续更高版本 JBR 上运行。固定 verifier 上界能让相同提交得到可复现结果；更新最新版是显式维护动作。

**Alternatives considered**:

- Gradle IntelliJ Plugin 1.x：官方已不再积极维护，不采用。
- 以最新 IDEA/JDK 21 或 25 编译后声明支持 2023.3：会产生旧 IDE 不可加载的 API 或字节码，不采用。
- 将 `untilBuild` 固定为 `233.*`：与 2023.3+ 的范围不符，不采用。

**Sources**: [IntelliJ Platform Gradle Plugin 2.x](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html), [build number ranges](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html), [plugin dependencies](https://plugins.jetbrains.com/docs/intellij/plugin-dependencies.html), [IntelliJ Platform releases](https://plugins.jetbrains.com/docs/intellij/intellij-artifacts.html), [Gradle Plugin releases](https://github.com/JetBrains/intellij-platform-gradle-plugin/releases)

## Decision 2: 使用 Java PSI 和平台现有索引，不依赖 MQ 运行库

**Decision**:

- 仅支持 Java，使用类型化 Java PSI、完整限定类名、方法 resolve 和注解限定名识别端点。
- 项目级候选先由平台搜索/index API 缩小，再解析相关 PSI；不递归整个 VFS，不按源码文本或正则搜索 Java。
- 插件不引入 Spring AMQP、Spring Kafka、RocketMQ client/starter 依赖；被扫描项目缺少这些运行库时，对应端点自然不存在。
- 首版不使用 UAST、自定义 `FileBasedIndexExtension`、Stub Index 或 Gist；只有性能验收失败且有 profiling 证据时再规划。

**Rationale**: 当前目标只有 Java，Java PSI 能直接 resolve 方法和注解。框架运行库只会放大插件体积和类加载风险，静态识别所需信息已经存在于被扫描项目的 PSI 与库 stub 中。平台已有索引足以先验证 2,000 端点规模。

**Alternatives considered**:

- 文本或正则扫描：无法可靠处理导入、重载、常量和注解，不采用。
- UAST：首版没有 Kotlin/Groovy 需求，不增加抽象层。
- 首版自定义索引：需要版本、序列化、重建和 dumb-mode 管理，当前没有性能证据，不采用。

**Sources**: [navigating PSI](https://plugins.jetbrains.com/docs/intellij/navigating-psi.html), [PSI Cookbook](https://plugins.jetbrains.com/docs/intellij/psi-cookbook.html), [PSI performance](https://plugins.jetbrains.com/docs/intellij/psi-performance.html), [indexing and PSI stubs](https://plugins.jetbrains.com/docs/intellij/indexing-and-psi-stubs.html)

## Decision 3: 一个项目级服务管理平台缓存

**Decision**:

- 使用一个 final light project service `MqNavigationService`，不创建 service interface。
- 服务构造方法只保存 `Project`，不扫描、不查询索引、不提前获取其他服务。
- `CachedValue` 从首个 MVP 故事就存在，不先实现无缓存全项目扫描。它依赖 `PsiModificationTracker.MODIFICATION_COUNT`、项目根/VFS 结构变化、一次计算中检查过的全部配置 PSI 文件和主动刷新计数。
- 缓存 miss 只在平台 non-blocking read action 中计算，并要求文档已提交、项目处于 smart mode；`CachedValue` 本身不负责切换线程。
- 快照使用不可变集合；跨 read action 保存的源码目标全部使用 `SmartPsiElementPointer`，使用前过滤 `null` 和失效元素。
- 配置文件依赖由 `MqValueResolver` 的局部集合或 private/internal result record 在一次计算内收集，并传给 `CachedValueProvider.Result.create(...)`；发布的 `EndpointSnapshot` 不保存裸 `PsiFile`。
- 不创建静态 `Map<basePath, ...>`、裸 PSI 长期缓存、手写 `PsiTreeChangeListener`、Recover 工具或自定义线程池。

**Rationale**: PSI 与缓存都应按 project 隔离。平台缓存能统一处理源码和索引状态的失效，智能指针可以在重命名、移动和重解析后恢复位置。依赖只是缓存计算的输入，不应泄漏到长期快照模型。参考插件的监听链只更新类列表，没有闭环清理导航缓存，因此不能直接复制。

**Alternatives considered**:

- application service 或静态缓存：混合多个项目并强持有 PSI，不采用。
- 每次点击重新扫描：把重复成本放到用户交互路径，不采用。
- 自己维护 PSI 事件 add/modify/remove Handler：状态多且容易漏配置、删除和注解变化，不采用。

**Sources**: [plugin services](https://plugins.jetbrains.com/docs/intellij/plugin-services.html), [threading model](https://plugins.jetbrains.com/docs/intellij/threading-model.html), [dynamic plugins and PSI references](https://plugins.jetbrains.com/docs/intellij/dynamic-plugins.html), [SmartPsiElementPointer source](https://github.com/JetBrains/intellij-community/blob/idea/262.8665.337/platform/core-api/src/com/intellij/psi/SmartPsiElementPointer.java)

## Decision 4: marker 不保存目标，点击时使用平台异步读取和标准弹窗

**Decision**:

- 注册一个 Java `RelatedItemLineMarkerProvider`，同时处理发送端和接收端，并创建带 `GutterIconNavigationHandler` 的 `LineMarkerInfo`。
- marker 挂在方法名或类名的最小叶子 `PsiIdentifier`，不挂整个方法、注解或类。
- marker 只保存 project、源锚点智能指针和查询键，不保存创建 marker 时的目标集合，也不使用 `NavigationGutterIconBuilder.setTargets(results)` 固化结果。
- 点击时先在 EDT 捕获 `RelativePoint`，再通过 `ReadAction.nonBlocking(...)`、`withDocumentsCommitted(project)`、`inSmartMode(project)`、`expireWith(project)` 在平台 executor 上查询当前缓存；UI 结果切回 EDT。
- 单目标确认仍可导航后直接跳转；多目标使用 `NavigationUtil` 标准 PSI 目标弹窗和 `PsiTargetPresentationRenderer`，候选按模块、文件、类、方法稳定排序。
- 没有目标、值动态或只能得到不确定候选时仍显示端点 marker；点击只显示明确诊断，不做近似跳转。
- dumb mode 下不访问索引、不为每个元素注册回调；smart mode 恢复后由 daemon 重新计算。

**Rationale**: marker 可能在 daemon 重算前继续可见，若保存目标列表，删除或修改 destination 后点击会导航到旧结果。点击时查询当前 `CachedValue` 才能满足“同一可见 marker 使用最新状态”。平台 non-blocking read action、项目生命周期取消和标准目标弹窗已经覆盖线程、索引等待、键盘操作与主题适配，无需监听器、自定义线程或 chooser。

**Alternatives considered**:

- `NavigationGutterIconBuilder.setTargets(results)`：会把 marker 创建时的目标集合固化到点击时，不采用。
- 自建 Swing/ListPopup：重复平台能力，不采用。
- 拆成发送、接收两个 Provider：判断和 UI 基本相同，会产生重复代码，首版不拆。
- 没有目标就不显示 marker：用户无法触发“未找到接收端/发送端”反馈，不符合规格。

**Sources**: [Line Marker Provider](https://plugins.jetbrains.com/docs/intellij/line-marker-provider.html), [threading and smart mode](https://plugins.jetbrains.com/docs/intellij/threading-model.html), [LineMarkerInfo 233 source](https://github.com/JetBrains/intellij-community/blob/idea/233.11799.241/platform/lang-api/src/com/intellij/codeInsight/daemon/LineMarkerInfo.java), [GutterIconNavigationHandler 233 source](https://github.com/JetBrains/intellij-community/blob/idea/233.11799.241/platform/lang-api/src/com/intellij/codeInsight/daemon/GutterIconNavigationHandler.java), [NavigationUtil 233 source](https://github.com/JetBrains/intellij-community/blob/idea/233.11799.241/platform/lang-impl/src/com/intellij/codeInsight/navigation/NavigationUtil.java)

## Decision 5: 静态值分为三种解析状态

**Decision**:

1. `STATIC`: 字符串字面量、Java 编译期常量以及可常量折叠的字符串拼接，可参与精确匹配。
2. `CONFIG_RESOLVED`: 接收端注解中的 `${key}`/`${key:default}`，或 producer destination 参数直接引用的合格 `@Value` 字段，在当前项目资源中得到唯一标量值时参与匹配。
3. `DYNAMIC`: SpEL、方法参数、普通可变字段、方法返回、`String.format`、`StringBuilder`、条件表达式和运行时拼接，只保留端点与原因，不建立确定连线。

配置读取覆盖同模块资源中的 `application*.properties/yml/yaml` 和 `bootstrap*.properties/yml/yaml`。同一 key 的所有候选值相同仍可确定，出现不同值时标记动态/歧义；`${key:default}` 在 key 缺失且 default 为单一静态标量时使用 default。不读取环境变量、命令行、Config Server 或 Spring 容器。YAML 使用 SnakeYAML 2.6 的安全构造方式，只读取标量树。

producer 参数先按实际 resolve 的方法与重载确定 destination 位置，再按字面量、`PsiVariable.computeConstantValue()`、直接字段引用的顺序解析。字段引用只接受无 qualifier 的 `topic` 或 `this.topic`，且必须直接 resolve 到本类普通、非 static、非 final 的 `String` 实例字段；字段直接标注完整限定名为 `org.springframework.beans.factory.annotation.Value` 的注解，注解文本必须恰好是一个 `${...}` 占位符。`Outer.this`、`other.topic`、getter、构造参数、局部别名、方法返回、跨方法传递、混合文本、嵌套占位符和 SpEL 均为 `DYNAMIC`。producer 参数中的字面字符串 `"${...}"` 仍按普通字符串处理，不自动获得 `@Value` 语义。

**Rationale**: 静态分析没有运行中的 Spring Environment。只有可唯一证明的值才能标记确定关系，否则文本相同也可能在不同模块/profile 中解析为不同目标。

**Alternatives considered**:

- 执行 SpEL 或启动 Spring 上下文：有副作用且依赖运行环境，不采用。
- getter、构造参数、局部别名和跨方法数据流：首版复杂度过高，按实际漏报再规划。
- 多 profile 取任意一个值：会制造错误跳转，不采用。

**Sources**: [Spring AMQP listener placeholders](https://docs.spring.io/spring-amqp/reference/amqp/receiving-messages/async-annotation-driven.html), [Spring Kafka listener annotation](https://docs.spring.io/spring-kafka/docs/current/api/org/springframework/kafka/annotation/KafkaListener.html), [SnakeYAML 2.6](https://central.sonatype.com/artifact/org.yaml/snakeyaml/2.6)

## Decision 6: RabbitMQ 按 exchange 与 binding 匹配

**Decision**:

- 发送端识别 `RabbitTemplate`/`AmqpTemplate` 的 `send(...)`、`convertAndSend(...)`：支持显式 `(exchange, routingKey, ...)` 和只有 `(routingKey, ...)` 的可确定重载。
- 仅有消息参数、依赖模板 bean 默认 exchange/routing key 的重载保留为动态端点，不建立确定连线。
- 接收端识别 `@RabbitListener(queues/queuesToDeclare/bindings)` 和 `@QueueBinding`；每个 queue/key 形成独立绑定。
- 匹配规则：exchange 先相等；默认 exchange 要求 routing key 等于 queue；direct 精确匹配；topic 支持 `*` 一个段、`#` 零到多段；fanout 忽略 routing key；headers/custom/x-delayed 标记无法静态确认。
- 只有 `queues=...` 而没有显式 binding 时，只能与默认 exchange 关联，不能猜测命名 exchange 的外部拓扑。

**Rationale**: RabbitMQ 的生产目标是 exchange，队列通过 binding 接收消息。只有默认 exchange 才存在 queue name 等于 routing key 的特殊映射。

**Alternatives considered**:

- 只比较 routing key 或 queue 字符串：命名 exchange、topic 和 fanout 都会误判，不采用。
- 首版解析任意 `@Bean Binding` 图：需要 Spring bean 关系分析，超出首版范围。

**Sources**: [RabbitTemplate API](https://docs.spring.io/spring-amqp/docs/current/api/org/springframework/amqp/rabbit/core/RabbitTemplate.html), [annotation-driven listeners](https://docs.spring.io/spring-amqp/reference/amqp/receiving-messages/async-annotation-driven.html), [RabbitMQ exchanges](https://www.rabbitmq.com/docs/next/exchanges)

## Decision 7: Kafka 按 topic 或 topicPattern 匹配

**Decision**:

- 发送端只识别 `KafkaTemplate.send(String topic, ...)`。
- 接收端识别 `@KafkaListener(topics=...)` 和 `topicPattern=...`；topics 逐个精确匹配，pattern 使用 Java `Pattern.matcher(topic).matches()` 完整匹配。
- `groupId` 或可推导的 `id` 仅用于候选展示，不过滤发送端到接收端关系；不同 group 都应作为候选。
- 首版不解析 `sendDefault`、`send(ProducerRecord)`、`send(Message<?>)` header 和 `topicPartitions`。

**Rationale**: Kafka producer 指定 topic，不指定 consumer group。group 决定同一订阅内部如何分担分区，不能用于排除其他监听器。

**Alternatives considered**:

- 按 `topic + group` 匹配：会漏掉合法监听器，不采用。
- 首版展开 ProducerRecord 或消息 header：需要对象流分析，不采用。

**Sources**: [KafkaTemplate sending methods](https://docs.spring.io/spring-kafka/reference/kafka/sending-messages.html), [Kafka listener annotation](https://docs.spring.io/spring-kafka/reference/4.0/kafka/receiving-messages/listener-annotation.html)

## Decision 8: RocketMQ 按 topic 与 TAG selector 匹配

**Decision**:

- 发送端识别 `RocketMQTemplate.syncSend`、`asyncSend`、`sendOneWay` 中首参数为 destination 的重载；destination 按第一个 `:` 分为 `topic:tag`。
- 接收端识别类上的 `@RocketMQMessageListener(topic, consumerGroup, selectorType, selectorExpression)`。
- topic 必须相等；默认 TAG selector 中 `*` 匹配全部，单 tag 精确匹配，`TagA||TagB` 按 OR 匹配。
- consumer group 只用于展示，不过滤 producer 关系。
- SQL92 依赖消息属性，只提供同 topic 的不确定诊断，不标记确定匹配。
- 首版不支持 orderly、transaction、request/reply 方法族。

**Rationale**: RocketMQ tag 是 topic 下的二级过滤条件，producer destination 不携带 consumer group；SQL92 又需要静态取得消息属性，不能只靠 destination 证明。

**Alternatives considered**:

- 只比较 topic：会把明确不满足 tag 的 listener 当作接收者，不采用。
- 首版实现 SQL92 解释器：即使能解析表达式也缺少 producer 属性数据，不采用。

**Sources**: [RocketMQTemplate source](https://github.com/apache/rocketmq-spring/blob/master/rocketmq-spring-boot/src/main/java/org/apache/rocketmq/spring/core/RocketMQTemplate.java), [RocketMQ listener annotation](https://github.com/apache/rocketmq-spring/blob/master/rocketmq-spring-boot/src/main/java/org/apache/rocketmq/spring/annotation/RocketMQMessageListener.java), [RocketMQ filtering](https://rocketmq.apache.org/docs/featureBehavior/07messagefilter/)

## Decision 9: 轻量功能测试、唯一 heavy 性能测试与固定跨版本门禁

**Decision**:

- Gradle 测试依赖使用 `TestFrameworkType.Plugin.Java` 和 JUnit 4.13.2。
- Parser、service 与 line marker 使用轻量 Java fixture 和真实 PSI/stub；Matcher 使用纯 JUnit。只有 `MqNavigationPerformanceTest` 使用 heavy fixture 创建 50 个真实 IntelliJ Java 模块；首版不 mock PSI，也不引入 UI 自动化。
- 自动化覆盖三类端点、常量/配置/动态值、零/单/多目标、删除/移动后的 pointer、缓存失效和 dumb-mode 安全路径。
- heavy fixture 每模块创建独立 content root 与 source root，每模块 40 个端点，总计 50 模块、2,000 端点；不导入 Gradle/Maven 项目，不把索引与项目同步噪声伪装成导航时间。
- 构建门槛包含 `clean test`、targeted heavy test、`verifyPluginProjectConfiguration`、`verifyPluginStructure` 和 `verifyPlugin`；verifier 固定 IDEA 2023.3 与 IDEA 2026.2.1；sandbox `runIde` 验证 gutter、弹窗、点击时最新结果、编辑刷新和日志。

**Rationale**: 真实 IntelliJ light fixture 比 mock PSI 更能验证 resolve、注解和缓存失效；而 JetBrains 明确要求多模块项目使用 heavy test，单模块 light fixture 无法证明 50 个真实模块的门槛。Plugin Verifier 解决二进制兼容，不能替代功能测试。

**Alternatives considered**:

- 普通 JUnit mock PSI：无法覆盖平台行为，不采用。
- 50 个“等价分组”的 light fixture：没有真实 Module 与独立源码根，不能证明规模目标，不采用。
- 50 个 Gradle/Maven 子项目导入：同步开销与波动会污染导航性能，不采用。
- UI 自动化：首版以计算路径的稳定基准和一次 runIde 人工验证覆盖，不引入。
- 只执行 `runIde` 或只执行 Plugin Verifier：都不能形成完整回归闭环。

**Sources**: [light and heavy tests](https://plugins.jetbrains.com/docs/intellij/light-and-heavy-tests.html), [test framework dependencies](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html), [Plugin Verifier](https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html), [official plugin template](https://github.com/JetBrains/intellij-platform-plugin-template)

## Decision 10: 固定 p95 方法与分阶段 SHA-256 只读证据

**Decision**:

- targeted heavy gate 使用 1 次完整快照、20 次导航和 3 次编辑/commit/刷新循环预热；随后确定性执行 200 次导航和 40 次刷新采样。
- 导航从调用查询服务开始，至 `NavigationOutcome` 完成、失效 pointer 过滤并稳定排序结束；刷新从 write action 修改并提交文档后开始，索引恢复、缓存重算和新结果可见均计时。
- p95 使用 nearest-rank：排序后取 `ceil(0.95 × n) - 1`；严格断言导航小于 1 秒、刷新小于 3 秒。命令增加 `--rerun-tasks --no-parallel`，唯一 heavy 测试使用 JUnit 4 六分钟总超时，不依赖尚不存在的 CI 配置。
- 手工验证只打开 `build/manual-sandbox-project` 的一次性副本。对受保护源码、配置和构建文件生成按相对路径排序的 SHA-256 清单；排除 `.idea`、`.gradle`、`build`、`out` 与 `*.iml`，清单写到被检查目录外。
- 无用户编辑的场景直接比较前后清单；有意编辑场景在用户修改并提交后重建基线，再验证点击/Rescan 不产生新增、删除或内容变化。

**Rationale**: 固定样本、边界和 nearest-rank 才能让性能结论可复现。当前项目没有 Git 元数据，且编辑场景本来会改变 fixture；分阶段 SHA-256 能区分用户主动修改与插件写入，并同时发现文件新增、删除和内容变化。

**Alternatives considered**:

- 只记录平均值：会掩盖尾延迟，不采用。
- 将首次索引计入导航 p95：测到的是 fixture 初始化而不是已完成初扫后的验收目标，不采用；首次索引仍单独输出。
- 只运行 `git status`：当前目录不是 Git 仓库，也不能证明未跟踪文件内容不变，不采用。
- 对整个 sandbox 目录做零变化断言：IDE 必然写 `.idea` 等运行文件，不采用；证据限定为受保护的业务源码、配置和构建文件。

**Sources**: [JetBrains light and heavy tests](https://plugins.jetbrains.com/docs/intellij/light-and-heavy-tests.html), [tests and fixtures](https://plugins.jetbrains.com/docs/intellij/tests-and-fixtures.html), [Microsoft Get-FileHash](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.utility/get-filehash), [Microsoft Compare-Object](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.utility/compare-object)

## Decision 11: 用本地 TCP sentinel 验证 broker 零连接

**Decision**:

- 新增一个 light fixture 测试，在环回地址启动三个同步 `ServerSocket` sentinel，并把各自端口写入测试项目的 RabbitMQ、Kafka、RocketMQ 配置。
- 测试执行项目扫描、双向导航查询和主动 Rescan 后，对每个 sentinel 使用短超时 `accept()` 检查待处理连接；三种连接尝试数必须均为零，不创建生产线程或 broker client。
- Gradle 运行依赖另行检查，确保没有 Spring AMQP/RabbitMQ、Kafka 或 RocketMQ client；生产源码同时检查是否直接使用网络 API。sentinel 运行证据、依赖证据和源码检查分别记录。

**Rationale**: 只审查源码不能证明运行时没有连接尝试；真实 broker 又会引入环境依赖和业务风险。本地环回 sentinel 可以在无消息服务的情况下观察插件是否尝试连接项目配置中的地址，且同步超时检查保持测试结构简单。

**Alternatives considered**:

- 连接真实 broker 后查看日志：违反运行时隔离原则，不采用。
- 只检查依赖树：能够降低风险，但不能单独证明运行路径没有直接 socket 连接，不采用。
- sandbox 期间人工观察所有系统网络连接：IDE 自身联网会产生噪声，难以形成可重复断言，不采用。

**Sources**: [Java 17 ServerSocket](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/ServerSocket.html), [JUnit 4 Timeout](https://junit.org/junit4/javadoc/4.13/org/junit/rules/Timeout.html)
