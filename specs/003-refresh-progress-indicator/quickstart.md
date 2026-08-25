# Quickstart Validation Guide: RabbitMQ 刷新进度提示

本指南用于实现完成后的自动化与 sandbox 验收。功能仅增加侧边栏视觉反馈，不需要启动 RabbitMQ，也不会发送、消费或查询真实消息。

## 1. Prerequisites

- Windows PowerShell
- JDK 17 用作 Gradle JVM
- IntelliJ IDEA 2023.3 或更高版本
- 工作目录：`D:\github\mqx-plugin`
- 首次构建可访问 Gradle、JetBrains 和 Maven Central 依赖仓库

```powershell
Set-Location 'D:\github\mqx-plugin'
java -version
Get-Content '.specify\feature.json'
```

预期：Gradle JVM 为 JDK 17；当前规格目录为 `specs/003-refresh-progress-indicator`。

## 2. Test-First Focused Validation

先在 `MqTopologyPanelTest` 增加进度组件和生命周期断言，在修改生产代码前运行：

```powershell
.\gradlew.bat test --tests "cn.luckyh.mqx.ui.MqTopologyPanelTest" --rerun-tasks
```

预期 red 阶段至少命中缺少命名进度组件或刷新中不可见的断言，不得伪造失败。

实现后再次运行同一命令，预期覆盖：

- 项目可分析时，刷新调用返回前进度条已经可见并处于不确定模式。
- 5 次预热后采集 20 次持续至少 500ms 的刷新，输出 n、p50、p95、max，nearest-rank p95 不超过 300ms。
- 最新成功、失败和合法空结果后进度条隐藏且停止活动。
- 成功、合法空结果、失败和等待索引各至少 5 次，对应状态文字出现时进度状态正确率为 100%。
- 至少 10 组请求反向完成时，较早回调不提前关闭或重新激活进度条。
- 刷新保持进行时，模块折叠、文本搜索和类型筛选仍可操作，刷新反馈不被覆盖。
- 原有模块状态、数量、动态提示和双击导航测试继续通过。

## 3. Real-Scale and Full Regression

```powershell
.\gradlew.bat test --tests "cn.luckyh.mqx.navigation.MqNavigationPerformanceTest" --rerun-tasks --no-parallel
.\gradlew.bat clean test
```

预期：

- 现有 50 模块、2,000 RabbitMQ endpoint 场景全部通过。
- 40 次用户可见刷新 nearest-rank p95 继续严格小于 3 秒。
- 全部 parser、matcher、导航、缓存、broker 隔离、性能和 UI 测试通过。
- 进度提示不得通过修改 heavy fixture 或放宽阈值获得通过。

## 4. Structure and Compatibility

```powershell
.\gradlew.bat verifyPluginProjectConfiguration verifyPluginStructure
.\gradlew.bat verifyPlugin
```

预期：

- 项目配置和插件结构通过。
- IDEA 2023.3 与 2026.2.1 均无兼容性错误。
- 下载或网络失败与代码兼容失败分开记录。
- 不新增运行依赖或插件版本变更。

## 5. Sandbox Setup

创建一次性验收副本，不直接修改规范 fixture：

```powershell
$workspace = (Resolve-Path '.').Path
$buildRoot = (Resolve-Path 'build').Path
$source = (Resolve-Path 'src\test\testData\manual-project').Path
$target = Join-Path $buildRoot 'sidebar-progress-manual-project'
$expectedPrefix = $buildRoot + [IO.Path]::DirectorySeparatorChar
if (-not $target.StartsWith($expectedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Unsafe sandbox target: $target"
}
if (Test-Path -LiteralPath $target) {
    Remove-Item -LiteralPath $target -Recurse -Force
}
Copy-Item -LiteralPath $source -Destination $target -Recurse
.\gradlew.bat runIde
```

在 sandbox IDEA 中打开 `build/sidebar-progress-manual-project`，等待必要索引完成，然后打开右侧 `MQ Navigator` 工具窗口。

## 6. Manual Scenarios

### Scenario A: Visible progress

1. 在能够让 RabbitMQ endpoint 刷新持续至少 500ms 的只读验收项目中打开侧边栏。
2. 点击现有刷新入口。
3. 确认“正在刷新 RabbitMQ 端点”文字旁出现活动进度条。
4. 确认进度条不显示百分比、剩余时间或阶段数字。
5. 刷新完成后确认进度条停止并消失，最终状态文字和树结果正确。
6. 如果刷新短于 500ms 而无法稳定目视，记录为该人工场景未验证，不能用自动化结果替代人工通过。

### Scenario B: Continue browsing

1. 在刷新仍进行时展开和折叠已有模块，确认操作不影响其他模块。
2. 输入文本搜索并切换 Consumers、Producers、Topology 和 Unresolved。
3. 确认旧树仍可浏览，进度条不遮挡搜索、筛选、树或刷新入口。
4. 将工具窗口收窄到相同验收宽度，确认进度条出现前、出现中和消失后主要树区域高度不变，状态文字、进度条和刷新入口没有重叠。
5. 确认筛选重建不会清除“正在刷新”文字或提前隐藏进度条。
6. 刷新结束后确认模块状态、数量和筛选结果按现有规则更新。

### Scenario C: Waiting, failure and request ordering

1. 等待索引场景由 focused dumb-mode 测试证明“等待项目索引完成”文字与隐藏进度条，不要求在生产代码加入索引控制入口。
2. 失败和恢复场景由受控 supplier 自动化测试证明旧树保留、进度条结束和后续成功恢复，不增加生产故障注入入口。
3. 连续刷新反向完成由至少 10 组自动化样例证明；sandbox 可快速重复点击刷新观察最终 UI，但不得把无法控制完成顺序的人工操作作为唯一证据。

## 7. Usability Validation

使用至少 10 名首次使用 MQ Navigator 的目标开发人员，在刷新持续超过 500ms 的相同验收项目中：

1. 为每人恢复相同的工具窗口尺寸和初始结果。
2. 用户点击刷新时开始计时，不提供进度提示说明。
3. 用户明确回答“仍在刷新”时停止计时；错误判断或超过 1 秒记为失败。
4. 至少 9/10 在 1 秒内正确判断，才满足 SC-005。
5. 逐人记录耗时与结果；未组织测试必须标记未验证。

## 8. Source and Broker Safety

```powershell
.\gradlew.bat test --tests "*MqBrokerIsolationTest" --rerun-tasks
$runtimeDependencies = .\gradlew.bat dependencies --configuration runtimeClasspath
$runtimeDependencies | Select-String -Pattern 'spring-rabbit|amqp-client|kafka-clients|rocketmq-client'
rg -n "java\.net|Socket|URLConnection|HttpClient|com\.rabbitmq|org\.apache\.kafka|org\.apache\.rocketmq" src/main/java
```

预期：三种 broker 连接尝试均为 0，运行依赖中不存在三种 MQ client，新增生产代码没有直接网络 API。

对 `build/sidebar-progress-manual-project` 中除 `.idea`、`.gradle`、`build`、`out` 和 `*.iml` 外的文件，在 Scenario A-C 前后生成按相对路径排序的 SHA-256 清单并比较。预期插件操作造成的新增、删除和内容变化均为 0。

## 9. Result Recording

实现阶段将以下结果分别记录到 `specs/003-refresh-progress-indicator/verification.md`：

- focused red/green 的测试数量、失败断言和最终结果。
- 5 次预热、20 次显示延迟样本的 n、p50、p95、max 和 300ms 门槛结果。
- 10 组反向完成、失败、空结果、等待索引和刷新中交互结果。
- heavy 回归的模块数、endpoint 数、样本数和 p95。
- 完整测试、结构检查和两个固定 IDE 的 Plugin Verifier 结果。
- sandbox Scenario A-C 的实际完成情况。
- 10 名首次使用者逐人耗时与成功数。
- broker 连接尝试数、依赖检查、网络 API 搜索和 SHA-256 差异。
- 未运行、环境阻塞和生产项目验证必须单独标记，不能写成已通过。
