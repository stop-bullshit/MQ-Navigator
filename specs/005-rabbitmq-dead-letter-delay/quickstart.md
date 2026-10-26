# Quickstart: RabbitMQ 死信与延迟队列拓扑验证

## Prerequisites

- Windows PowerShell
- JDK 17
- 仓库根目录 `<workspace>`
- Gradle Wrapper 可用

## 1. Parser Focused Validation

```powershell
.\gradlew.bat test --tests "cn.luckyh.mqx.parser.RabbitMqEndpointParserTest" --rerun-tasks --no-parallel
```

Expected:

- 用户示例形式的参数 Map 能得到 queue、DLX、TTL；
- 单参数形式能得到 DLX、dead-letter key、TTL；
- 仅 TTL 与仅 DLX 分类不同；
- 动态或无效 TTL 保留诊断，不丢 Queue declaration；
- 无关 quorum 参数不影响结果。

## 2. Cache Focused Validation

```powershell
.\gradlew.bat test --tests "cn.luckyh.mqx.navigation.MqNavigationCacheTest" --rerun-tasks --no-parallel
```

Expected:

- cache v2 往返后设置字段和源码位置 100% 一致；
- cache v1 自动回退当前源码扫描；
- 无效 TTL、null diagnostic 或不一致标记使整批缓存失效。

## 3. Sidebar Relationship Validation

```powershell
.\gradlew.bat test --tests "cn.luckyh.mqx.ui.MqSpecialQueueTopologyPanelTest" --rerun-tasks --no-parallel
```

Expected:

- 完整 DLX 路径显示 Exchange、Binding、Queue；
- 多目标全部保留；
- 缺失目标不虚构节点；
- `delay`、DLX、TTL 和 key 可被现有搜索命中；
- 双击关系节点跳转真实源码；
- 环路只展开一层。

## 4. Existing Behavior Regression

```powershell
.\gradlew.bat test --tests "cn.luckyh.mqx.ui.MqTopologyPanelTest" --rerun-tasks --no-parallel
.\gradlew.bat test --tests "cn.luckyh.mqx.navigation.MqNavigationServiceTest" --tests "cn.luckyh.mqx.match.MqEndpointMatcherTest" --rerun-tasks --no-parallel
.\gradlew.bat test --tests "cn.luckyh.mqx.navigation.MqNavigationPerformanceTest" --rerun-tasks --no-parallel
.\gradlew.bat test --tests "*MqBrokerIsolationTest" --rerun-tasks --no-parallel
```

Expected:

- 普通 RabbitMQ、Kafka、RocketMQ 结果不变；
- 50 模块、2,000 endpoint、40 次刷新 p95 严格小于 3 秒；
- broker 连接尝试为 0。

## 5. Full and Compatibility Gates

```powershell
.\gradlew.bat clean test --no-parallel
.\gradlew.bat verifyPluginProjectConfiguration verifyPluginStructure --no-parallel
.\gradlew.bat verifyPlugin --no-parallel
```

Expected:

- 完整测试通过；
- 插件结构检查通过；
- IDEA 2023.3 和 2026.2.1 兼容验证通过。

## 6. Scope and Safety Check

```powershell
rg -n -i "ConnectionFactory|RabbitAdmin|getQueueInfo|RabbitManagement|localhost:5672" src/main/java
git diff --check
git status --short
```

Expected:

- 本功能未新增 RabbitMQ 网络或管理调用；
- 无 diff 格式错误；
- 变更仅落在 plan 允许的生产、测试和 `specs/005-rabbitmq-dead-letter-delay/` 文件，既有未提交文件保持原样。
