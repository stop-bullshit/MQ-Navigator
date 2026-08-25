# 刷新进度提示验证记录

## Pre-change

- 基线提交：`819a0d1`
- 实现前工作区：已有 `specs/002-sidebar-module-controls/`、`RefreshMqIndexAction.java`、`MqTopologyPanel.java`、`MqNavigationPerformanceTest.java` 与 `MqTopologyPanelTest.java` 的未提交改动；本功能必须完整保留这些改动。
- `src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java` SHA-256：`AEFA4819E92489C79CD46BFCF5E26ACE729A2C794E394EDBF69949CEF12ECE8A`
- `src/test/java/cn/luckyh/mqx/ui/MqTopologyPanelTest.java` SHA-256：`F0CEE3DBB2F2BBD2BF4E6A8838AA0F5B88FBFEF0144FD755C02D788D0DF46706`
- 受保护生产文件：`src/main/java/cn/luckyh/mqx/action/RefreshMqIndexAction.java`、RabbitMQ endpoint 服务与解析/匹配实现、`src/main/resources/META-INF/plugin.xml`、`build.gradle.kts`、`gradle.properties`。
- 受保护测试文件：`src/test/java/cn/luckyh/mqx/navigation/MqNavigationPerformanceTest.java`、broker 隔离测试、parser 与 matcher 测试。
- 只读参考目录：`D:\github\interfacex`。

## Focused UI

- 改动前基线命令：`.\gradlew.bat test --tests "cn.luckyh.mqx.ui.MqTopologyPanelTest" --rerun-tasks`
- 结果：通过，10 个测试，0 failure，0 error，0 skipped；Gradle `BUILD SUCCESSFUL in 32s`，测试用时 6.831s。
- 复用能力：现有测试已提供受控与阻塞 endpoint supplier、EDT 执行、命名状态组件递归查找以及请求反向完成控制，无需新增测试框架或生产接缝。
- TDD red 命令：`.\gradlew.bat test --tests "cn.luckyh.mqx.ui.MqTopologyPanelTest" --rerun-tasks`
- TDD red 结果：12 个测试中 4 个失败；`testRefreshShowsIndexAndProgressState`、`testLatestRefreshResultWinsWhenRequestsFinishOutOfOrder`、`testRefreshProgressLifecycleKeepsTreeAndLayoutStable`、`testRefreshProgressAppearsWithinLatencyBudget` 均在 `MqTopologyPanelTest.java:589` 因找不到 `mqTopologyRefreshProgress` 失败。生产代码在此次 red 前未修改。
- US1 green 命令：`.\gradlew.bat test --tests "cn.luckyh.mqx.ui.MqTopologyPanelTest" --rerun-tasks`
- US1 green 结果：通过，12 个测试，0 failure，0 error，0 skipped；Gradle `BUILD SUCCESSFUL in 32s`，测试用时 20.749s。
- 显示延迟：5 次预热后采集 20 次、每次保持刷新至少 500ms；nearest-rank `n=20, p50=0ms, p95=0ms, max=0ms`，满足 p95 不超过 300ms。
- 生命周期：活动刷新可见且为不确定模式、不绘制字符串；成功与失败终态均隐藏且退出不确定模式，活动提示残留为 0；等待索引期间保持隐藏。
- US2 命令：`.\gradlew.bat test --tests "cn.luckyh.mqx.ui.MqTopologyPanelTest" --rerun-tasks`
- US2 结果：通过，13 个测试，0 failure，0 error，0 skipped；测试用时 19.903s。
- 状态矩阵：成功 5/5、合法空结果 5/5、失败 5/5、索引等待 5/5，正确率 100%，终态活动提示残留 0。
- 请求顺序：10 组、每组包含最新请求前后各一个旧请求；提前关闭 0、结束后重新激活 0、旧树覆盖 0。
- US3 命令：`.\gradlew.bat test --tests "cn.luckyh.mqx.ui.MqTopologyPanelTest" --rerun-tasks`
- US3 结果：通过，14 个测试，0 failure，0 error，0 skipped；测试用时 18.82s。
- 固定窄宽布局：面板宽 360px，树滚动区刷新前/中/后高度均为 504px；活动状态边界 `x=4,y=540,w=198,h=16`，进度边界 `x=208,y=540,w=148,h=16`，无重叠。
- 刷新中浏览：模块展开/折叠、文本搜索、endpoint 类型筛选均可操作，进度持续可见且为不确定模式；最新结果应用后模块状态、数量、动态提示和双击导航沿用既有规则。

## Heavy Regression

- 命令：`.\gradlew.bat test --tests "cn.luckyh.mqx.navigation.MqNavigationPerformanceTest" --rerun-tasks --no-parallel`
- 结果：通过，2 个 heavy 测试，0 failure，0 error，0 skipped；Gradle `BUILD SUCCESSFUL in 58s`，测试用时 48.829s。
- 侧边栏样本：50 模块、2,000 RabbitMQ endpoint、40 次用户可见刷新；nearest-rank `p50=255.430ms, p95=322.263ms, max=408.365ms`，严格小于 3 秒。
- 受保护性能测试和阈值未修改。

## Full Test

- 命令：`.\gradlew.bat clean test`
- 首次沙箱执行因 Gradle Wrapper 尝试下载 9.7.0 时网络权限受限而中止；授权后使用同一命令重新执行成功，此环境问题不属于代码失败。
- 结果：通过，10 个测试套件、61 个测试，0 failure，0 error，0 skipped；累计测试用时 64.323s，Gradle `BUILD SUCCESSFUL in 1m 13s`。

## Plugin Verifier

- 项目配置与结构命令：`.\gradlew.bat verifyPluginProjectConfiguration verifyPluginStructure`；授权环境下通过，Gradle `BUILD SUCCESSFUL in 2s`。
- 兼容命令：`.\gradlew.bat verifyPlugin`；Plugin Verifier 1.409 对 `IC-233.11799.241` 与 `IU-262.9437.185` 均判定 `Compatible`，Gradle `BUILD SUCCESSFUL in 4m 34s`。
- IDEA 2023.3：兼容；2026-08-25 最终复验无 deprecated/planned-removal API 引用。
- IDEA 2026.2.1：兼容；`JBProgressBar` planned-removal 引用已清零，仅保留 3 个与本次进度组件无关的既有 deprecated API 引用。

## Sandbox

- 一次性副本：`build/sidebar-progress-manual-project`，来源为只读 `src/test/testData/manual-project`；6 个受保护文件的来源/副本 SHA-256 差异为 0，检查前后差异为 0。
- Scenario A 人工目视：**未验证**。当前终端会话没有可审计 GUI 操作，未把自动化结果替代为人工通过；活动/结束生命周期由 focused 自动化验证。
- Scenario B 人工操作：**未验证**。当前终端会话没有可审计 GUI 操作；360px 固定窄宽、树高、组件边界和刷新中浏览由 focused 自动化验证。
- Scenario C：等待索引、失败恢复与 10 组连续刷新顺序已由自动化验证；sandbox 快速重复点击的人工观察 **未验证**。
- 用户项目非用户改动：副本创建及终端检查期间为 0；由于未执行 GUI 场景，不将该数字表述为生产 GUI 操作证据。

## Usability

- SC-005 首次使用者测试：**未验证**。当前执行环境无法组织 10 名真实首次使用者，未伪造参与者、耗时或 9/10 结论。

## Source Safety

- 最小性审查：生产实现只在 `MqTopologyPanel` 增加一个 `JProgressBar` 字段、一个固定本地占位面板和一个被初始化、开始、成功、失败四条路径复用的 `setRefreshProgress` 方法。
- 控制流审查：新增逻辑保持提前返回和线性刷新主线，没有新增超过两层的条件嵌套、只调用一次的一行方法、类、接口、中间层、自定义线程、计时器、锁、缓存或持久化。
- 生命周期审查：原 `refreshRequestId` 过期请求判断、`expireWith(project)`、刷新入口和回调中的 disposed guard 均保留；过期或项目关闭后不更新 UI。
- 设计同步：`plan.md`、`data-model.md`、`contracts/sidebar-progress-ui.md` 已同步组件名和固定槽的实际尺寸规则；`quickstart.md` 已引用本验证记录，无需求或范围偏移。

## Broker Isolation

- 隔离命令：`.\gradlew.bat test --tests "*MqBrokerIsolationTest" --rerun-tasks`；首次遇到 Gradle 增量编译产物瞬态缺失，未改代码按原命令重试后通过，1 个测试，0 failure，RabbitMQ/Kafka/RocketMQ 三个 sentinel 的连接尝试均为 0。
- 依赖命令：`.\gradlew.bat dependencies --configuration runtimeClasspath`；结果仅 `org.yaml:snakeyaml:2.6`，`spring-rabbit`、`amqp-client`、`kafka-clients`、`rocketmq-client` 均为 0。
- 源码扫描：广义名称扫描仅命中 `RocketMqEndpointParser` 中 2 个用于 PSI 匹配的类名字符串；Java 网络包、Socket 构造、HttpClient、URLConnection 和三种 MQ client 的直接 import/调用为 0。

## Requirement Traceability

| Requirement | Status | Evidence |
|-------------|--------|----------|
| FR-001 | 已验证 | 活动刷新同时显示精确状态文字和命名进度条。 |
| FR-002 | 已验证 | `isIndeterminate=true` 且 `isStringPainted=false`，无百分比、剩余时间或阶段数量。 |
| FR-003 | 已验证 | 每个延迟样本保持刷新至少 500ms，放行前进度条持续活动。 |
| FR-004 | 已验证 | 成功、空结果、失败各 5 次及索引等待 5 次状态均正确，终态残留 0。 |
| FR-005 | 已验证 | 既有成功、空结果、无匹配、等待索引和失败文字断言全部通过。 |
| FR-006 | 已验证 | 10 组请求顺序样例的提前关闭、结束后重激活均为 0。 |
| FR-007 | 已验证 | 活动状态不替换旧树，过期结果覆盖次数为 0，最新结果仍由原请求序号规则应用。 |
| FR-008 | 已验证 | 刷新中模块折叠/展开、搜索和类型筛选回归通过。 |
| FR-009 | 已验证 | 360px 宽度下状态与进度边界不重叠，树高前/中/后均为 504px。 |
| FR-010 | 已验证 | 模块状态、数量、动态提示、tooltip 和双击导航回归及完整测试通过。 |
| FR-011 | 已验证（自动化范围） | broker 连接尝试为 0，直接网络 API 为 0，副本受保护文件哈希差异为 0；生产 GUI 操作未验证。 |
| SC-001 | 已验证 | 5 次预热、20 次样本，`p95=0ms <= 300ms`。 |
| SC-002 | 已验证 | 四类状态各 5 次，正确率 100%，活动残留 0。 |
| SC-003 | 已验证 | 10 组请求，提前结束 0、重新激活 0。 |
| SC-004 | 已验证 | 全部刷新中交互样例完成，可用旧内容丢失 0。 |
| SC-005 | 未验证 | 无 10 名真实首次使用者，不伪造 9/10 结果。 |
| SC-006 | 已验证（自动化范围） | 三种 broker 连接均为 0，副本受保护文件差异为 0；生产 GUI 操作未验证。 |

## Final Status

- Focused UI：**已验证**；14 个测试通过，含 red/green、延迟、状态矩阵、请求顺序与窄宽交互。
- Heavy Regression：**已验证**；50 模块、2,000 endpoint、40 次刷新 p95 严格小于 3 秒。
- Full Test：**已验证**；61 个测试通过。
- Plugin Verifier：**已验证**；IDEA 2023.3 与 2026.2.1 均兼容，进度组件 planned-removal 警告已清零。
- Sandbox：自动化与 SHA-256 检查 **已验证**；Scenario A/B 和重复点击人工观察 **未验证**。
- Usability：**未验证**；未组织 10 名首次使用者。
- Production GUI：**未验证**；未在真实用户项目执行可审计 GUI 操作。
- 环境阻塞：最终门禁无阻塞；Gradle Wrapper 沙箱网络限制均在授权环境重跑并获得明确结果。
- `git diff --check`：通过，退出码 0。
- 最终 `MqTopologyPanel.java` SHA-256：`C9C7AEF3994DF91E7A9CE62D1F0F5538BE93A5402CDE9FF291ACAD3E7233E701`。
- 最终 `MqTopologyPanelTest.java` SHA-256：`92CDB69ECA7A4D8D478A3E249BF4FA712925085C60C6679B14590D1A07F342CF`。
- 工作区范围：T001 已记录的 feature 002 文档、`RefreshMqIndexAction.java` 和 `MqNavigationPerformanceTest.java` 既有未提交改动仍保留；本功能未新增任何范围外路径，仅修改 `MqTopologyPanel.java`、`MqTopologyPanelTest.java` 与 `specs/003-refresh-progress-indicator/`。
- 收尾完整性：任务 19/19 完成，requirements checklist 16/16 完成；`.specify/extensions.yml` 不存在，因此无 `after_implement` 扩展钩子需要执行。
