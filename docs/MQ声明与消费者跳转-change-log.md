# MQ 声明与消费者跳转变更日志

## [CHANGE-更新: 队列声明不再充当收发端并修复井号路由-20261023-1204-01]

| 字段 | 内容 |
| --- | --- |
| 日期 | 2026-10-23 |
| 模块 | RabbitMQ gutter 与 topic 路由匹配 |
| 触发需求 | 队列声明跳到 listener，listener 又跳回声明，实际发送端未被定位 |
| 业务规则 | 已脱敏的 `BillingQueueConfiguration` / `BillingBindingConfiguration` / `BillingListener` / `BillingPublishAspect` 复现链路；本仓库 master 分支没有 Jira 编号 |
| 场景覆盖 | 队列声明和绑定保留拓扑；队列声明无 gutter；没有生产者时明确无匹配；`#.init` 与 `calc.billing.init` 匹配；消费者可列出同一方法中的两个真实发送调用；`#` 在首、中、尾匹配零段或多段；`*` 仍只匹配一段 |
| 版本 | 0.2.10 |

**代码锚点：**

- `队列声明只参与拓扑计算不充当发送或消费跳转目标-20261023-1204-01`
- `topic井号按零个或多个完整路由段匹配-20261023-1204-01`
- `队列声明保留在拓扑侧栏且不显示收发端跳转图标-20261023-1204-01`

**变更文件：**

| 文件 | 用途 |
| --- | --- |
| `src/main/java/cn/luckyh/mqx/match/MqEndpointMatcher.java` | 排除声明等拓扑角色作为收发端目标，修正 topic `#` 匹配 |
| `src/main/java/cn/luckyh/mqx/navigation/MqLineMarkerProvider.java` | 队列声明不生成收发端 gutter |
| `src/main/java/cn/luckyh/mqx/navigation/MqNavigationService.java` | 未找到目标时只提示真正的发送端或消费者 |
| `src/test/java/cn/luckyh/mqx/match/MqEndpointMatcherTest.java` | `#` 零段、多段和 `*` 回归 |
| `src/test/java/cn/luckyh/mqx/navigation/MqNavigationServiceTest.java` | 队列声明不充当目标、billing 示例绑定与发送链双向跳转 |
| `src/test/java/cn/luckyh/mqx/navigation/MqLineMarkerProviderTest.java` | 队列声明无 gutter |
| `README.md`、`specs/001-mq-navigation-plugin/spec.md`、`specs/001-mq-navigation-plugin/contracts/navigation-ui.md` | 同步用户更正后的导航规则 |
| `docs/MQ识别与跳转问题分析.md` | 标记此前的 `#` 缺陷现已修复 |
| `gradle.properties` | 版本 0.2.9 → 0.2.10 |

**保护边界与风险：** RabbitMQ 队列、绑定、交换机仍被扫描并显示在 Topology 侧栏，继续用于推导 listener 的 exchange/routing key；不把配置声明当成发送端。无需触碰清关项目源码。缺少当前项目内发送调用时，listener 点击图标将提示未找到，而不会跳到队列声明。

**验证：**

- 修改前 4 个定向用例全部失败，分别复现声明互跳、声明 gutter 和 `#.init` 漏匹配。
- 修改后 matcher、导航服务与 gutter 定向测试通过；`test buildPlugin` 全量检查成功，且追加的同方法两发送点定向用例通过。
- 安装新插件包并重启 IDEA 后，从 `BillingListener#processData` 的 gutter 应显示 `BillingPublishAspect#publishBilling` 内两个发送调用供选择；从任一发送调用应反向到 listener。`BillingQueueConfiguration#billingInitQueue` 的 `durable` 行无 MQ 收发端图标，队列与绑定仍显示在 Topology 侧栏。

以上是静态源码与测试平台验证；未连接生产 RabbitMQ，也未替换用户正在运行的 IDEA 插件。
