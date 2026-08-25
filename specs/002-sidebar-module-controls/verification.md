# Verification: 侧边栏模块浏览与刷新优化

## Pre-change

- 状态：已记录
- Git checkout：`master`
- `git status --short`：`?? specs/002-sidebar-module-controls/`
- `src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java` SHA-256：`D5FE53EB966AE67ED5C27DC94CA2DF35A081123EE292E4D8D497343F5B835FC4`
- `src/main/java/cn/luckyh/mqx/action/RefreshMqIndexAction.java` SHA-256：`02E4C240C1C09890FDF67F0529F7B2D61C9123DBCA0C5A7F591C6B732BDED507`
- `.gitignore` 已覆盖当前 Java/Gradle/IntelliJ 构建目录；未检测到 Docker、Node、ESLint、Prettier、Terraform 或 Helm 配置，无需新增忽略文件。
- 受保护生产文件：`MqNavigationService`、全部 MQ parser、`MqEndpointMatcher`、`MqToolWindowService`、`plugin.xml`、依赖和插件版本。
- 受保护功能：Consumers、Producers、Topology、Queues、Bindings、Exchanges 的分组和匹配含义；Kafka/RocketMQ 工具窗口；全部展开/全部折叠；状态持久化。
- 只读外部参考：`D:/github/interfacex`、`D:/github/feignx-plugin`。

## Focused UI

- 状态：测试支架已编译，行为测试未运行
- T002：`.\gradlew.bat compileTestJava --rerun-tasks`，`BUILD SUCCESSFUL`，5 个任务执行，耗时 3m11s。
- T003 red：`.\gradlew.bat test --tests "cn.luckyh.mqx.ui.MqTopologyPanelTest" --rerun-tasks`，2 个测试执行，1 个失败；`testRootExpandedAndModulesDefaultCollapsed` 在模块默认折叠断言失败，证明现有 `rebuildTree()` 自动展开全部模块。
- T003 回归基线：`testExpandingOneModuleKeepsOtherModuleStateAndCategories` 已通过，Consumers、Producers、Topology/Queues 数量语义未发现回归。
- T005 green：同一定向命令执行 2 个测试，全部通过；根节点展开、11 个模块/项目分组默认折叠、单模块操作隔离和分类数量均已验证，耗时 14s。
- T006 red：定向执行 `MqTopologyPanelTest` 共 7 个测试，4 个失败；分别命中缺失的状态标签、失败恢复处理、最新请求覆盖旧请求和标准刷新图标，刷新后的模块状态保留用例已通过。
- T011 green：`.\gradlew.bat test --tests "cn.luckyh.mqx.ui.MqTopologyPanelTest" --rerun-tasks` 执行 7 个测试全部通过，`BUILD SUCCESSFUL in 16s`；US1 回归、空结果、失败恢复、索引等待、最新请求和标准图标均已验证。
- T012 red：完成测试支架修正后，同一定向命令执行 10 个测试、3 个失败；分别命中搜索展开了 endpoint 关系子树、类型筛选未临时展开分类、双击导航在读锁内调用异步导航入口的问题。
- T014 green：`.\gradlew.bat test --tests "cn.luckyh.mqx.ui.MqTopologyPanelTest" --rerun-tasks` 执行 10 个测试全部通过，`BUILD SUCCESSFUL in 15s`；文本搜索、四种类型筛选、可见/总数、状态恢复、无匹配反馈、动态标识/tooltip 和普通/搜索/筛选双击导航均已验证。
- 过程记录：一次并行 instrumentation 因 `MqTopologyPanel$1.class` 临时缺失失败；使用 `--no-parallel` 重跑通过，随后任务原命令再次通过，未修改构建配置。

## Heavy Performance

- 状态：已验证
- T007 red：`\.\gradlew.bat test --tests "cn.luckyh.mqx.navigation.MqNavigationPerformanceTest.sidebarVisibleRefreshMeetsP95Budget"` 在真实 50 模块/2,000 RabbitMQ endpoint 基线上执行；基线索引数量断言通过，新增源码后索引诊断为 2,001 条、目标模块 41 条，但 EDT tree model 30 秒内未进入对应新增状态，命中旧刷新结果覆盖新结果的缺失行为。
- red 阶段未产生 40 次完整样本，因此不伪造 n/p50/p95/max；阈值保持 nearest-rank p95 `< 3_000_000_000 ns`，待 T011 green 运行记录。
- T011 green：`.\gradlew.bat test --tests "cn.luckyh.mqx.navigation.MqNavigationPerformanceTest" --rerun-tasks --no-parallel` 执行 2 个 heavy 测试全部通过，`BUILD SUCCESSFUL in 54s`。
- 可见刷新：50 模块、2,000 RabbitMQ endpoint，n=40，p50=261,275,600ns，p95=360,057,100ns，max=476,022,800ns，首次快照=1,858,228,900ns，首次树构建=313,505,900ns。
- 原有导航/刷新基线：导航 n=200、p95=2,440,400ns；刷新 n=40、p95=167,163,600ns；IDEA `IC-233.11799.241`、JBR `17.0.9+7-b1087.7`、Windows 11。

## Full Test

- 状态：已验证
- T017：`.\gradlew.bat clean test`，`BUILD SUCCESSFUL in 1m 23s`；10 个 suite、57 个测试，failures=0、errors=0、skipped=0。
- 完整回归中的侧边栏 heavy：50 模块、2,000 endpoint，n=40，p95=484,517,900ns；原有导航 n=200、p95=1,695,200ns，原有刷新 n=40、p95=159,853,200ns。

## Plugin Verifier

- 状态：已验证
- T019 结构：`.\gradlew.bat verifyPluginProjectConfiguration verifyPluginStructure`，`BUILD SUCCESSFUL in 2s`，项目配置与插件结构通过。
- T019 IDEA 2023.3：Plugin Verifier 1.409 对 `IC-233.11799.241` 判定 `Compatible`，动态插件资格通过。
- T019 IDEA 2026.2.1：对 `IU-262.9437.185` 判定 `Compatible. 3 usages of deprecated API`；涉及两处 `ReadAction.compute`（其中一处为既有 line marker）和既有 `DaemonCodeAnalyzer.restart()`，无兼容性错误。
- `.\gradlew.bat verifyPlugin` 最终 `BUILD SUCCESSFUL in 6m 49s`；两份报告位于 `build/reports/pluginVerifier/IC-233.11799.241` 与 `build/reports/pluginVerifier/IU-262.9437.185`。

## Sandbox

- 状态：自动化场景已验证，交互式 sandbox 未验证
- T020：已从 `src/test/testData/manual-project` 创建一次性副本 `build/sidebar-manual-project`，未直接打开或修改原 fixture；受保护文件 6 个，准备前后 SHA-256 差异 0。
- T022 复核：再次计算副本中 6 个受保护文件的 SHA-256，最终差异仍为 0。
- Scenario A 模块折叠：自动化 `MqTopologyPanelTest` 已验证根展开、模块默认折叠和独立展开；未在 `runIde` 交互窗口人工复验。
- Scenario B 刷新：自动化已验证标准图标、状态恢复、新增/删除模块、最新请求和空结果；未在副本中人工编辑并点击工具栏。
- Scenario C 筛选：自动化已验证文本/四类筛选、分类临时展开、关系子树保持折叠、数量和清除恢复；未在 `runIde` 人工复验。
- Scenario D 导航：自动化已从普通、搜索、Unresolved 筛选树双击同一动态 endpoint 并定位相同源码；受控失败/恢复由 T006/T014 通过，未增加生产故障注入入口。
- 交互式 sandbox 未执行原因：当前 Codex CLI 门禁运行不提供可审计的首次使用者 GUI 操作与计时输入；不得把自动化证据写成人工通过。

## Usability

- 状态：未验证
- T021：50 模块/2,000 endpoint 自动化验收项目已由 heavy fixture 验证，但未组织 10 名首次使用者，SC-003 不判定通过。

| 参与者 | 耗时 | 结果 |
|--------|------|------|
| U01 | 未执行 | 未验证 |
| U02 | 未执行 | 未验证 |
| U03 | 未执行 | 未验证 |
| U04 | 未执行 | 未验证 |
| U05 | 未执行 | 未验证 |
| U06 | 未执行 | 未验证 |
| U07 | 未执行 | 未验证 |
| U08 | 未执行 | 未验证 |
| U09 | 未执行 | 未验证 |
| U10 | 未执行 | 未验证 |

## Source Safety

- 状态：已验证允许范围；真实用户项目交互未验证
- T015：`MqTopologyPanel` 保留现有 guard clause，并将筛选分类展开提取为有明确层级边界的递归方法；主刷新与状态分支不超过两层条件嵌套。
- T015：仅新增必要的 endpoint supplier 测试接缝、刷新结果私有 record、模块名集合、请求序号和轻量布尔状态；未新增生产类、接口、自定义线程、锁、原子状态、第二套缓存或持久化。
- T015：`RefreshMqIndexAction` 只替换平台标准图标，`requestRefresh()` → `MqToolWindowService.refresh()` → daemon restart 顺序未变；双击导航仍使用原智能指针，改为读操作结束后直接导航，避免在读锁内启动异步导航。
- T016：`plan.md`、`data-model.md`、`contracts/sidebar-ui.md`、`quickstart.md` 已同步实际字段名、精确状态文本和已验证命令/结果引用；未发现需要反向修改需求或重新执行 analyze 的设计偏移。
- T022：`git diff --check` 与 `git diff --cached --check` 均通过；仅出现 LF 将由 Git 转为 CRLF 的提示，无空白错误。
- T022：最终状态仅包含批准的 2 个生产文件、2 个测试文件和 `specs/002-sidebar-module-controls` feature 文档；`plugin.xml`、parser、matcher、service、依赖和插件版本均未改动。

## Requirement Traceability

| Requirement | 状态 | 证据 |
|-------------|------|------|
| FR-001 | 已验证 | focused UI 在 RabbitMQ 根下验证 11 个模块分组。 |
| FR-002 | 已验证 | focused UI 验证模块可见端点数量随搜索和类型筛选更新。 |
| FR-003 | 已验证 | focused UI 验证单模块展开/折叠不改变其他模块状态。 |
| FR-004 | 已验证 | focused UI 验证根默认展开、无会话状态的模块默认折叠。 |
| FR-005 | 已验证 | focused UI 验证 Consumers、Producers、Topology 分类及数量保持不变。 |
| FR-006 | 已验证 | focused UI 验证平台标准刷新图标、明确文本和 tooltip。 |
| FR-007 | 已验证 | focused UI 与 heavy 验证刷新后模块、分类、数量和端点内容更新。 |
| FR-008 | 已验证 | focused UI 验证同名存续模块在刷新后恢复原展开状态。 |
| FR-009 | 已验证 | focused UI 验证新模块默认折叠，删除或重命名模块不再保留旧节点。 |
| FR-010 | 已验证 | focused UI 验证两个反向完成的请求仅应用最新结果。 |
| FR-011 | 已验证 | focused UI 验证索引等待与失败反馈，失败时旧树保留，合法空结果单独处理。 |
| FR-012 | 已验证 | focused UI 验证文本和四类筛选临时展开匹配模块及分类。 |
| FR-013 | 已验证 | focused UI 验证清除全部条件后恢复完整数量和筛选前模块状态。 |
| FR-014 | 已验证 | focused UI 验证普通、搜索、筛选双击导航目标一致，动态标识与 tooltip 保留。 |
| FR-015 | 自动化已验证；交互式未验证 | 源码范围审查、broker 隔离和沙箱哈希均通过；未执行 `runIde` 人工操作。 |

| Success Criterion | 状态 | 证据 |
|-------------------|------|------|
| SC-001 | 已验证 | 11 模块自动化样例中一次目标模块操作不改变其他模块状态。 |
| SC-002 | 已验证 | 50 模块/2,000 endpoint、40 次增改删刷新全部与源码一致，完整回归 p95=484,517,900ns。 |
| SC-003 | 未验证 | 未组织 10 名首次使用者，不能判定 5 秒定位成功率。 |
| SC-004 | 已验证 | focused UI 中同名模块状态恢复和新模块默认折叠准确率均为 100%。 |
| SC-005 | 已验证 | 自动化普通、搜索、筛选样例导航成功率 100%，错误目标 0。 |
| SC-006 | 已验证 | 索引等待、刷新失败、合法空结果和无匹配结果均有明确反馈，失败时旧内容丢失 0 次。 |
| SC-007 | 自动化部分已验证；完整交互式验收未验证 | broker 连接尝试 0、MQ client 依赖 0、生产网络 API 0、沙箱受保护文件哈希差异 0。 |

## Validation Boundaries

| 验证层级 | 状态 | 说明 |
|----------|------|------|
| Focused UI | 已验证 | 10/10 通过。 |
| Heavy Performance | 已验证 | 2/2 通过，用户可见刷新 p95 低于 3 秒。 |
| Full Build/Test | 已验证 | `clean test` 共 57/57 通过。 |
| Plugin Verifier | 已验证 | IDEA 2023.3 与 2026.2.1 均兼容。 |
| Sandbox Automation | 已验证 | 一次性副本、自动化场景和最终哈希复核通过。 |
| Sandbox GUI | 未验证 | 未执行可审计的 `runIde` 人工交互。 |
| Usability | 未验证 | 缺少 10 名首次使用者数据。 |
| Production Usage | 未验证 | 未在真实用户项目或生产使用环境执行交互式验证。 |

## Broker Isolation

- 状态：已验证
- T018：`.\gradlew.bat test --tests "*MqBrokerIsolationTest" --rerun-tasks`，1 个测试通过，`BUILD SUCCESSFUL in 17s`；RabbitMQ、Kafka、RocketMQ 三个本地 sentinel 端口的连接尝试均为 0。
- T018：`runtimeClasspath` 仅包含 `org.yaml:snakeyaml:2.6`；`spring-rabbit`、`amqp-client`、`kafka-clients`、`rocketmq-client` 匹配数为 0。
- T018：搜索 `src/main/java` 的 `java.net`、Socket、URLConnection、HTTP client 及三类 MQ client API，匹配数为 0。
