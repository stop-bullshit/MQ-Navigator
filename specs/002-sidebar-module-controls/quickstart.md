# Quickstart Validation Guide: 侧边栏模块浏览与刷新优化

本指南用于实现完成后的自动化与 sandbox 验收。功能只读取当前项目源码，不需要启动 RabbitMQ，也不会发送、消费或查询真实消息。

## 1. Prerequisites

- Windows PowerShell
- JDK 17 用作 Gradle JVM
- IntelliJ IDEA 2023.3 或更高版本
- 工作目录：`D:\github\mqx-plugin`
- 首次构建可访问 Gradle、JetBrains 和 Maven Central 依赖仓库

```powershell
Set-Location 'D:\github\mqx-plugin'
java -version
```

预期：Gradle JVM 为 JDK 17；当前规格目录为 `specs/002-sidebar-module-controls`。

## 2. Focused Automated Validation

### 2.1 Sidebar behavior

```powershell
.\gradlew.bat test --tests "cn.luckyh.mqx.ui.MqTopologyPanelTest" --rerun-tasks
```

预期覆盖：

- 根节点展开、模块首次默认折叠。
- 展开或折叠一个模块不影响其他模块。
- 刷新后仍存在模块恢复状态，新模块折叠，删除模块消失。
- 文本搜索和类型筛选临时展开匹配模块与分类，清除后恢复。
- 合法空结果、筛选无结果和失败保留旧结果有不同反馈。
- 两个刷新请求反向完成时，较早结果不会覆盖较新结果。
- 普通、搜索和筛选结果中的 endpoint 双击导航保持正确。

实现阶段结果见 `verification.md` 的 Focused UI：10 个测试全部通过。

### 2.2 Real-scale visible refresh

```powershell
.\gradlew.bat test --tests "cn.luckyh.mqx.navigation.MqNavigationPerformanceTest" --rerun-tasks --no-parallel
```

除原有三类 MQ 导航性能场景外，侧边栏场景必须在同一个 heavy 测试类中独立创建：

- 50 个真实 IntelliJ Java Module，每个具有独立 content root 和 source root。
- 每模块 20 个 RabbitMQ producer 与 20 个 consumer，合计 2,000 个 RabbitMQ endpoint。
- 首次扫描和首次树构建单独记录，不计入 p95。
- 预热后以 50 模块、2,000 endpoint 基线副本轮换执行新增、修改和删除源码，并提交 PSI 文档；共采集 40 次用户可见刷新。
- 新增样本等待新增 endpoint 可见且模块与根节点数量增加 1；修改样本等待旧展示值消失、新展示值可见且数量不变；删除样本等待目标 endpoint 消失且模块与根节点数量减少 1。
- 每轮从触发刷新开始，到对应操作的全部条件进入 EDT tree model 后停止计时；不得使用未变化的缓存结果作为有效样本。
- 输出 n、p50、p95、max、首次扫描、首次树构建和运行环境；p95 必须小于 3 秒。

实现阶段结果见 `verification.md` 的 Heavy Performance：2 个 heavy 测试全部通过，可见刷新 n=40、p95=360,057,100ns。

## 3. Full Regression and Compatibility

```powershell
.\gradlew.bat clean test
.\gradlew.bat verifyPluginProjectConfiguration verifyPluginStructure
.\gradlew.bat verifyPlugin
```

预期：

- 全部 parser、matcher、导航、缓存、broker 隔离、性能和 UI 测试通过。
- 插件结构与 IDEA 2023.3 API 兼容。
- Plugin Verifier 固定目标均无兼容性错误；下载或网络失败与代码失败分开报告。
- 不新增 MQ client、网络依赖或持久化依赖。

## 4. Sandbox Setup

创建一次性验收副本，不直接修改规范 fixture：

```powershell
$source = Resolve-Path 'src\test\testData\manual-project'
$target = Join-Path (Resolve-Path 'build') 'sidebar-manual-project'
if (Test-Path -LiteralPath $target) {
    Remove-Item -LiteralPath $target -Recurse -Force
}
Copy-Item -LiteralPath $source -Destination $target -Recurse
.\gradlew.bat runIde
```

在 sandbox IDEA 中打开 `build/sidebar-manual-project`，等待 indexing 完成，然后打开右侧 `MQ Navigator` 工具窗口。

## 5. Manual Scenarios

### Scenario A: Initial module layout

1. 确认 RabbitMQ 根节点展开。
2. 确认每个模块显示当前 endpoint 数量并默认折叠。
3. 展开一个模块，确认 Consumers、Producers、Topology 分类保持现有含义。
4. 展开第二个模块并折叠第一个模块，确认两个模块状态互不影响。

### Scenario B: Refresh

1. 确认侧边栏工具栏使用可识别的标准刷新图标，tooltip 明确表示刷新 RabbitMQ 端点。
2. 展开一个模块、保持另一个模块折叠。
3. 在一次性副本中新增、修改或删除 RabbitMQ endpoint，提交文档后点击刷新。
4. 确认刷新期间旧树仍可见，并显示等待索引或正在刷新状态。
5. 确认成功后模块数量与源码一致；原模块状态恢复，新模块默认折叠，删除模块消失。
6. 快速连续点击刷新并继续修改 endpoint，确认最终树不回退到较早结果。

### Scenario C: Search and type filter

1. 设置至少一个模块展开、另一个模块折叠。
2. 搜索折叠模块中的 queue、exchange、routing key、类名或成员名。
3. 确认匹配模块和分类临时展开，关系子树没有全部展开。
4. 切换 Consumers、Producers、Topology 和 Unresolved，确认匹配内容可见。
5. 清空文本并恢复 All，确认模块回到步骤 1 的状态。
6. 输入无匹配条件，确认显示无结果反馈；清除后原状态仍恢复。

### Scenario D: Navigation and failures

1. 分别从普通树、搜索结果和类型筛选结果双击 endpoint，确认精确打开相同源码位置。
2. 删除目标源码并刷新，确认失效节点不再跳转到错误位置。
3. 执行 T006 对应的定向 UI 测试，通过测试中的受控 endpoint supplier 构造刷新失败，确认旧树和模块状态保留并显示刷新失败反馈。
4. 使用同一自动化测试让 supplier 在失败后返回成功结果，确认失败状态清除并展示最新树；不得在生产插件中增加故障注入入口。

## 6. Usability Validation

准备一个具有 50 个真实模块和 2,000 个 RabbitMQ endpoint 的只读验收项目，使用至少 10 名首次使用 MQ Navigator 的目标开发人员：

1. 为每人恢复相同的初始模块折叠状态和工具窗口尺寸。
2. 发出“展开指定模块并定位到指定分类”的任务指令时开始计时。
3. 参与者不得查阅说明或接受操作提示。
4. 正确定位时停止计时；错误模块、错误分类或超过 5 秒均记为失败。
5. 至少 9/10 在 5 秒内完成，才满足 SC-003。

## 7. Source and Broker Safety

复用已有 broker 隔离门禁：

```powershell
.\gradlew.bat test --tests "*MqBrokerIsolationTest" --rerun-tasks
$runtimeDependencies = .\gradlew.bat dependencies --configuration runtimeClasspath
$runtimeDependencies | Select-String -Pattern 'spring-rabbit|amqp-client|kafka-clients|rocketmq-client'
```

预期：三种 broker 连接尝试均为 0，运行依赖中不存在三种 MQ client。

对 `build/sidebar-manual-project` 中除 `.idea`、`.gradle`、`build`、`out` 和 `*.iml` 外的文件，在 Scenario A-D 前后生成按相对路径排序的 SHA-256 清单并比较。预期插件操作造成的新增、删除和内容变化均为 0。

## 8. Result Recording

实现阶段将以下结果分别记录到 `specs/002-sidebar-module-controls/verification.md`：

- `MqTopologyPanelTest` 的通过数量和失败详情。
- 唯一 heavy 测试的 module/endpoint 数、预热、样本、p50/p95/max 和运行环境。
- 完整 `test`、结构检查和 Plugin Verifier 结果。
- sandbox Scenario A-D 的实际完成情况。
- 10 名首次使用者的逐人耗时和成功数。
- broker 连接尝试数、运行依赖检查和 SHA-256 差异。
- 未运行、环境阻塞和生产项目验证必须单独标记，不能写成已通过。
