# MQ 发送封装识别变更日志

## [CHANGE-更新: MQ发送封装委托链识别-20261023-1125-01]

| 字段 | 内容 |
| --- | --- |
| 日期 | 2026-10-23 |
| 模块 | RabbitMQ 发送端解析、端点索引与缓存 |
| 触发需求 | 同一业务方法调用三参数和两参数 `publishMessage`，仅前者出现跳转图标 |
| 业务规则 | 用户提供的重载场景；当前分支为 master，本次没有 Jira 编号或对应需求文档 |
| 场景覆盖 | 重载委托、跨方法多层委托、参数换序、空 routing key、lambda 内调用、循环委托、普通非 MQ 调用、只传消息体的业务方法、重复汇合分支、旧缓存升级 |
| 交付版本 | 0.2.8 |

**代码锚点：**

- `沿发送封装委托链收集业务调用并保留空路由键-20261023-1125-01`
- `仅将传入MQ目的地的参数沿委托链回溯为发送封装-20261023-1125-01`
- `升级发送封装解析后重建旧端点缓存-20261023-1125-01`

**变更文件：**

| 文件 | 变更 |
| --- | --- |
| `src/main/java/cn/luckyh/mqx/parser/RabbitMqEndpointParser.java` | 识别实际传递 MQ 目的地的委托方法，逐层映射实参并向上收集业务调用；分类时每个方法只展开一次，解析时按方法和实参去重并阻止循环 |
| `src/main/java/cn/luckyh/mqx/navigation/MqNavigationService.java` | 持久化缓存版本 2 → 3，升级后重扫以补齐旧版本未识别的发送端 |
| `src/test/java/cn/luckyh/mqx/parser/RabbitMqEndpointParserTest.java` | 重载、多层参数换序、循环、普通方法与两类 20 层汇合分支回归 |
| `src/test/java/cn/luckyh/mqx/navigation/MqLineMarkerProviderTest.java` | lambda 内两个重载均显示 gutter 图标 |
| `src/test/java/cn/luckyh/mqx/navigation/MqNavigationServiceTest.java` | 两参数封装与 fanout 消费端双向跳转 |
| `src/test/java/cn/luckyh/mqx/navigation/MqNavigationCacheTest.java` | 旧版本空快照升级后必须重扫当前源码 |
| `gradle.properties` | 插件版本 0.2.7 → 0.2.8 |
| `README.md` | 同步可识别的委托封装范围 |
| `docs/MQ识别与跳转问题分析.md` | 记录用户场景及其他已复现、尚未修复的问题 |

**保护边界与风险：**

- 插件验证不修改被分析示例项目的业务源码。
- Kafka、RocketMQ 的识别与匹配规则未修改；未增加类、接口或外部依赖。
- 目的地必须实际传到 Spring AMQP 发送调用；只传递消息体的业务方法不会被当作通用发送封装。
- 保留现有静态表达式解析范围；不新增任意方法返回值、字符串运行时转换或外部 broker 拓扑推断。
- 回滚以上实现及版本变更即可恢复原识别行为。缓存版本不兼容时会从源码重建。

**验证：**

- 修复前：3 个新增定向用例中 2 个失败，分别复现重载漏识别和多层委托漏识别。
- 修复后：运行 parser、gutter、双向导航、持久化缓存和现有导航性能测试。
- 最终执行 `./gradlew.bat test buildPlugin --console=plain`，全量 103 项测试通过，失败/错误/跳过均为 0，插件包构建成功。`buildSearchableOptions` 等打包辅助任务的 SKIPPED 不属于测试跳过。
- 20 层 MQ 汇合图用例耗时 0.153 秒，非 MQ 汇合图用例耗时 0.105 秒（整个测试用例时间，含测试框架开销）。已有 50 模块 / 2000 端点性能回归中，导航 P95 为 0.855 毫秒，刷新 P95 为 145.580 毫秒；这是本机测试平台结果。
- 额外 4 个临时诊断用例确认既有问题；测试源和失败报告保留在 `build/diagnostics/`，未加入正式通过用例集。详情见问题分析文档。

**IDEA 手工验证步骤：**

1. 从磁盘安装 `build/distributions/mqx-plugin-0.2.8.zip`，按 IDEA 提示重启。
2. 等待项目索引完成，打开示例项目中包含两种 `publishMessage` 重载的发送方法。
3. 检查 225 行三参数调用和 228 行两参数调用均有 MQ 图标。
4. 点击两参数调用，预期按 `orders.status` 的 fanout 绑定找到对应消费者。
5. 从对应消费者反向跳转，预期可找到 228 行业务调用，目标不应落在工具类内部委托处。

本次没有自动安装插件或操作用户正在运行的 IDEA，真实项目 UI 验证仍需执行。
