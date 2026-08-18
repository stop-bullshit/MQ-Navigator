# Quickstart Validation Guide: MQ Navigator

本指南用于实现完成后的端到端验收。它不要求启动 RabbitMQ、Kafka、RocketMQ，也不会发送或消费真实消息。

## 1. Prerequisites

- Windows PowerShell
- JDK 17 用作 Gradle JVM
- IntelliJ IDEA 2023.3 或更高版本
- 首次构建可访问 Gradle、JetBrains 和 Maven Central 依赖仓库
- 工作目录：`D:\github\mqx-plugin`

确认 Java：

```powershell
java -version
```

预期：Gradle 使用 JDK 17；较新 sandbox IDE 仍使用其下载包自带的 JBR。

## 2. Automated Validation

### 2.1 Functional tests

```powershell
Set-Location 'D:\github\mqx-plugin'
.\gradlew.bat clean test
```

预期：

- RabbitMQ、Kafka、RocketMQ parser 测试全部通过。
- 常量、唯一配置、配置歧义和动态表达式边界全部通过。
- direct/topic/fanout、Kafka exact/pattern、Rocket TAG 规则全部通过。
- 零目标、单目标、多目标和失效 pointer 测试全部通过。
- 测试不需要任何 MQ 服务，不修改测试项目源文件。

### 2.2 Plugin configuration and package structure

```powershell
.\gradlew.bat verifyPluginProjectConfiguration verifyPluginStructure
```

预期：

- `plugin.xml`、Java plugin 依赖、since-build 和打包结构有效。
- 未引用目标平台 2023.3 之外才存在的 API。

### 2.3 Binary compatibility

```powershell
.\gradlew.bat verifyPlugin
```

预期：配置固定覆盖 IDEA Community 2023.3 与计划核实时的最新稳定版 IDEA 2026.2.1，且均无兼容性错误；不得使用动态 `latest()` 作为门禁。网络或下载问题必须与代码兼容性结果分开报告。

### 2.4 Broker isolation

```powershell
.\gradlew.bat test --tests '*MqBrokerIsolationTest' --rerun-tasks
$runtimeDependencies = .\gradlew.bat dependencies --configuration runtimeClasspath
$forbiddenDependencies = $runtimeDependencies |
    Select-String -Pattern 'spring-rabbit|amqp-client|kafka-clients|rocketmq-client'
if ($forbiddenDependencies) {
    throw 'MQ client dependency found in plugin runtime classpath'
}
$productionNetworkUsage = Get-ChildItem -LiteralPath 'src/main/java' -Recurse -Filter '*.java' |
    Select-String -Pattern '(java|javax)\.net\.'
if ($productionNetworkUsage) {
    throw 'Direct production network API usage found'
}
```

`MqBrokerIsolationTest` 在环回地址启动三个同步 TCP sentinel，将端口写入测试项目配置，执行扫描、双向查询和 Rescan 后检查待处理连接。预期 RabbitMQ、Kafka、RocketMQ sentinel 的连接尝试均为 0，插件运行依赖中不存在三种 MQ client，生产源码也没有直接网络 API。测试不得连接真实 broker。

## 3. Sandbox IDE Validation

启动：

```powershell
.\gradlew.bat runIde
```

先将 `src/test/testData/manual-project` 复制为 `build/manual-sandbox-project`，只在 sandbox IDEA 中打开这个一次性副本，不能直接打开或修改规范 fixture。等待 indexing 完成后执行以下场景。

### Scenario A: RabbitMQ

1. 打开使用默认 exchange 和静态 queue/routing key 的 producer。
2. 点击 producer gutter，验证直接跳转到唯一 `@RabbitListener(queues=...)`。
3. 在 listener 端反向点击，验证跳回 producer。
4. 打开 named direct exchange 的 producer，验证只显示相同 exchange 和 key 的 `@QueueBinding`。
5. 验证 topic `order.*` 与 `order.created` 匹配，`order.*` 与 `order.created.eu` 不匹配。
6. 验证 fanout 返回同 exchange 的所有 queue listener。
7. 验证 headers/custom exchange 只显示无法静态确认提示，不执行错误跳转。

### Scenario B: Kafka

1. 点击 `KafkaTemplate.send("orders", ...)`，验证定位 `topics="orders"` 的 listener。
2. 验证 `topicPattern="orders\\..*"` 也作为候选。
3. 准备两个不同 group 的 listener，确认两个候选均显示并带 group 信息。
4. 验证 `sendDefault`、`ProducerRecord` 和动态 topic 不产生确定目标。

### Scenario C: RocketMQ

1. 点击 `syncSend("notice:SMS", ...)`，验证匹配 topic `notice` 且 selector 为 `*`、`SMS` 或包含 `SMS` 的 `||` 表达式。
2. 验证 selector `EMAIL` 不进入确定候选。
3. 验证不同 consumer group 都保留并显示 group 信息。
4. 验证 SQL92 listener 只给出不确定说明，不声明确定投递。

### Scenario D: Values and configuration

1. 将 destination 写成 Java 编译期常量，验证结果与字面量一致。
2. 将 listener topic 写为 `${mq.topic}`，在模块配置中提供唯一值，验证能匹配。
3. 将 producer destination 写为直接字段引用 `topic` 和 `this.topic`，字段是普通非 static、非 final `String` 且直接标注 `@Value("${mq.topic}")`，验证能匹配。
4. 验证 producer 参数中的字面字符串 `"${mq.topic}"` 仍作为普通 topic 文本，不读取配置。
5. 在两个 profile 配置中提供不同值，验证 marker 保留但提示配置歧义；若多个定义值完全相同，则仍可确定。
6. 使用 SpEL、`other.topic`、static/final `@Value` 字段、getter、构造参数、局部别名、方法返回或跨方法传递，验证不猜测目标。

### Scenario E: Multiple targets and refresh

1. 为同一目的地创建两个接收端，验证平台候选列表包含全部目标。
2. 检查候选按模块、文件、类和成员稳定排序。
3. marker 显示后删除一个接收端，提交文档但不等待 marker 重建；再次点击同一个 marker，确认旧候选消失。
4. marker 显示后移动或重命名剩余接收端；再次点击同一个 marker，确认跳转到新位置。
5. marker 显示后修改 destination；再次点击同一个 marker，确认旧关系失效并显示新结果。
6. 执行 `Tools > MQ Navigator > Rescan Endpoints`，确认结果与当前源码一致且 marker 点击使用刷新后的快照。

### Scenario F: Indexing and error isolation

1. 在 IDEA indexing 期间打开示例文件。
2. 确认编辑器不冻结，日志中没有 `IndexNotReadyException` 或 EDT slow-operation。
3. 等待 smart mode，确认 gutter 自动出现。
4. 在一个 Java 文件中制造未完成语法，确认其他文件的已识别端点仍可导航。

## 4. Usability Validation

使用至少 10 名首次使用 MQ Navigator 的目标开发人员，并为每人恢复相同起点：

1. 打开同一个 producer 文件并等待导航入口显示。
2. 发出“定位接收端并反向返回发送端”的任务指令时开始计时。
3. 参与者不得查阅 README、quickstart 或接受操作提示。
4. 成功返回原 producer 时停止计时；未完成双向导航、发生错误跳转或超过 5 秒均记为失败。
5. 记录每名参与者的耗时与结果；至少 9/10 成功才满足 SC-004。

## 5. Performance Validation

唯一的 heavy 性能测试必须创建 50 个真实 IntelliJ Java `Module`，每个模块有独立 content root 和 `src/main/java`，不导入 Gradle/Maven 项目。每模块固定生成 40 个端点：RabbitMQ 7 producer + 7 consumer、Kafka 7 + 7、RocketMQ 6 + 6，合计 2,000 个端点。测试先断言模块数、端点总数、三种 MQ 和两个方向的数量正确，并等待索引就绪。

定向运行：

```powershell
.\gradlew.bat test --tests '*MqNavigationPerformanceTest' --rerun-tasks --no-parallel
```

`MqNavigationPerformanceTest` 使用 JUnit 4 六分钟总超时，超时即失败，不依赖外部 CI 配置。测量规则：

- fixture 初始化、首次索引和首次快照单独输出，不计入 p95。
- 预热 1 次完整快照、20 次导航和 3 次编辑/commit/刷新循环。
- 导航确定性采样 200 次，轮询 50 个模块、三种 MQ 和两个方向；从调用查询服务开始，到 `NavigationOutcome` 完成、失效 pointer 过滤并稳定排序时停止，不计 gutter 绘制和 popup paint。
- 刷新采样 40 次，每次在 write action 修改不同模块的静态 destination；`PsiDocumentManager` 提交后开始，到索引重新可用、缓存重算并返回新 destination 且旧 destination 消失时停止。
- 两类查询均在非 EDT read action 执行。p95 使用 nearest-rank：样本升序后取 `ceil(0.95 × n) - 1`。

严格验收：

- 初次快照完成后，重复导航查询的 p95 小于 1 秒。
- 提交修改后，刷新查询的 p95 小于 3 秒。
- 测试期间没有全 VFS 文本扫描、EDT 项目级查询或 broker 连接。
- 输出 navigation/refresh 的 n、p50、p95、max，以及 IntelliJ build、JBR、OS、CPU 数、max heap、fixture 初始化和首次快照耗时；不主动调用 GC。

如果性能门槛失败，先输出 profiling 证据；不得直接增加自定义索引、线程池或第二套缓存。

## 6. Source-Safety Check

当前新项目没有 Git 元数据，不能用 `git status` 证明只读。对 `build/manual-sandbox-project` 中除 `.idea`、`.gradle`、`build`、`out` 和 `*.iml` 外的受保护文件生成按相对路径排序的 SHA-256 清单；清单变量或导出文件必须保存在被检查目录之外。

PowerShell 清单函数：

```powershell
function Get-ProjectManifest([string]$Root) {
    $resolved = (Resolve-Path -LiteralPath $Root).Path.TrimEnd('\')

    Get-ChildItem -LiteralPath $resolved -Force -Recurse -File |
        ForEach-Object {
            $relative = $_.FullName.Substring($resolved.Length + 1).Replace('\', '/')
            if ($relative -notmatch '^(\.idea|\.gradle|build|out)(/|$)' `
                    -and $relative -notmatch '(^|/)[^/]+\.iml$') {
                [pscustomobject]@{
                    Path = $relative
                    Sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
                }
            }
        } |
        Sort-Object Path
}
```

分阶段执行：

1. 规范 `src/test/testData/manual-project` 在整个人工测试前后清单完全一致。
2. 对无用户编辑的导航和 Rescan 场景，在操作前保存 `$before`，操作后保存 `$after`，执行 `Compare-Object $before $after -Property Path,Sha256`，结果必须为空。
3. 对 Scenario E 的有意编辑，在用户修改并提交文档后重新生成 `$before`，然后执行点击导航或 Rescan，再生成 `$after`；比较结果必须为空。

该检查同时覆盖新增、删除和内容变化。验收结论限定为“插件操作对受保护的源码、配置和构建文件零改动”，不声称 IDE 自有目录完全不变化。

## 7. Result Recording

交付时分别记录：

- `test` 的通过数量和失败详情。
- `verifyPlugin*` 的目标 IDEA 版本与结果。
- broker sentinel 的三类连接尝试数和运行依赖检查结果。
- `runIde` 完成的场景 A-F。
- 至少 10 名首次使用者的逐人耗时、成功数和 SC-004 结论。
- 性能 fixture 的真实 module/endpoint 数、预热与样本数、navigation/refresh 的 p50、p95、max 和运行环境。
- 分阶段 SHA-256 清单比较结果；若有差异，记录相对路径和变更类型。
- 未覆盖的动态形式，必须对应 [mq-detection.md](contracts/mq-detection.md) 的明确范围，不能以“可能支持”表述。
