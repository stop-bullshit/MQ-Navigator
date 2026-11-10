# MQ 发送封装识别变更日志

## [CHANGE-更新: Spring Bean补充拓扑定位-20261110-1520-01]

| 字段 | 内容 |
| --- | --- |
| 日期 | 2026-11-10 |
| 版本 | 0.4.0 |
| 触发需求 | 根据IDEA已识别的Spring Bean定义补充Queue、Exchange注入引用的拓扑解析 |

**修改范围：**

- `SpringBeanSource`和`IdeaSpringBeanSource`：通过可选扩展点隔离Spring类加载，使用公开模型按明确名称/别名或类型查询定义；保留库候选、未知来源和歧义。
- `plugin.xml`及独立Spring描述文件：Spring支持启用时注册适配，未启用时保留普通Java解析。
- `RabbitMqEndpointParser`：解析字段和Bean参数注入，按实际返回对象求MQ名及交换机类型；Bean注册名不替代MQ名，不将直接Java工厂参数误当容器注入。
- `MqNavigationService`和缓存回归：缓存版本4升级为5，Bean依赖结果跟踪模型变化且不跨会话直接恢复旧Profile结果。
- `build.gradle.kts`：以IU2023.3公开Spring API编译，保留Java17；核心单测使用Java-only插件集和受控Bean来源，兼容性目标同时包含CE和Ultimate。
- 新增Bean/注入注解类型桩及Bean定位回归，不创建真实SpringContext或broker连接。

**保护边界：** 首批覆盖Java@Bean工厂和标量Queue/Exchange注入；多上下文、多候选、自定义限定符、XML/不可定位源码及运行时计算保持动态。未增加new Binding构造式收集或完整Spring依赖注入求值。

**验证：** 初次基线受SDK未分发的Spring Boot测试专用服务和测试应用未注册自定义EP阻断；核心单测限定Java插件并注册受控SPI后，全量132项测试通过，失败、错误、跳过均为0，其中新增Bean定位用例14项。项目配置、插件结构检查和0.4.0构建通过。Plugin Verifier 1.411分别使用各IDE自带运行时检查IC-233.11799.241、IU-233.11799.241和IU-263.7307.200，均为Compatible；263版本保留两条既有弃用API提示。安装包ID、233至263.*兼容范围、可选描述文件及Spring适配类已核对，未混入Spring或IDE SDK。Bean行为回归使用受控模型来源，真实Spring适配已编译及二进制检查，未在业务IDE现场验证Spring上下文和跳转，未启动应用或连接broker。

## [CHANGE-更新: 内部发送位置解析枚举路由-20261110-1349-01]

| 字段 | 内容 |
| --- | --- |
| 日期 | 2026-11-10 |
| 版本 | 0.3.0 |
| 触发需求 | 内部发送封装已有图标，但由通知方法参数查枚举得到的routing key无法跳转 |

**修改范围：**

- `MqValueResolver.java`：识别源码可证明的枚举code查找与getter，复用字段及构造实参映射；不执行任意方法或猜测全部枚举路由。
- `RabbitMqEndpointParser.java`：从具体通知方法及唯一接口实现对应的项目调用点带入静态实参，在原内部发送锚点保留候选路由；运行时重赋值、未知调用和歧义实现保持动态。
- `MqNavigationService.java`、`MqNavigationCacheTest.java`：端点缓存版本3升级为4，旧快照重扫当前源码。
- `RabbitMqComputedRoutingTest.java`：覆盖内部发送锚点、双向导航、Lombok查找及动态边界，样例使用通用业务名称。
- `gradle.properties`、`README.md`、`CHANGELOG.md`：版本递增为0.3.0并记录新增识别范围。

**保护边界：** 不修改被分析业务仓库，不增加方法声明旁的producer图标，不放宽未知code、任意方法返回或多实现接口的静态推断。

**验证：** 修改前5个定向用例中3个失败，复现枚举查找、内部发送解析和消费者跳转问题。修改后全量117项测试通过，失败、错误、跳过均为0；新增10项回归包含唯一接口调用上下文、内部锚点多路由、消费者跳转、Lombok映射、动态重赋值、构造参数改写、循环表达式及20层逆向汇合调用链。汇合调用链测试总耗时0.203秒。项目配置、插件结构检查及0.3.0安装包构建通过；Plugin Verifier 1.411检查IC-233.11799.241为Compatible，检查IU-263.7307.200为Compatible并保留两条既有弃用API提示。安装包ID和233至263.*兼容范围已核对。业务配置的QueueBuilder/ExchangeBuilder/BindingBuilder工厂写法属于现有支持范围；未连接broker，未在业务IDE现场执行跳转或修改业务源码。

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
