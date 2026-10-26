# MQ 跳转弹窗变更日志

## [CHANGE-更新: 多目标弹窗突出方法和队列差异-20261023-1145-01]

| 字段 | 内容 |
| --- | --- |
| 日期 | 2026-10-23 |
| 模块 | MQ gutter 多目标跳转弹窗 |
| 触发需求 | 多消费者选择弹窗把系统、模块、文件、类方法、交换机和队列全部放在一行，宽度和重复内容影响辨认 |
| 场景覆盖 | 多个 RabbitMQ 消费者共享队列前缀；同类中不同方法；Kafka 的 topic 和 group；无共同队列前缀时显示完整队列；反向跳转多个发送端 |
| 版本 | 0.2.9 |

**变更文件：**

| 文件 | 说明 |
| --- | --- |
| `src/main/java/cn/luckyh/mqx/navigation/MqTargetRenderer.java` | 主信息仅显示类和方法；次要信息显示精简模块和目的地；共同队列前缀仅在同一候选列表中折叠 |
| `src/main/java/cn/luckyh/mqx/navigation/MqLineMarkerProvider.java` | 标题显示待选择目标的角色和数量 |
| `src/test/java/cn/luckyh/mqx/navigation/MqLineMarkerProviderTest.java` | 断言主次信息的展示与共同队列前缀折叠 |
| `gradle.properties` | 版本由 0.2.8 升至 0.2.9 |

**代码锚点：** `多目标弹窗突出类方法并折叠重复队列前缀-20261023-1145-01`。

**边界与风险：** 使用 IDEA 内置目标选择弹窗与主题色、图标和键盘导航。缩短的队列名称仅在同一弹窗的多个候选之间共享前缀时用于展示；端点原始数据及跳转匹配不变。目标类方法和模块负责区分实际跳转位置。回滚渲染与标题代码可恢复旧展示。

**验证：**

- 目标渲染器定向测试验证 `EventListener#orderStatusChanged` 与 `order-processing · queue data.process` 等可辨认标签；没有共同前缀时保留完整队列名。
- `MqLineMarkerProviderTest` 共 10 项通过，失败/错误/跳过均为 0；`buildPlugin` 成功，包内 `plugin.xml` 版本为 0.2.9。
- 安装 `build/distributions/mqx-plugin-0.2.9.zip` 并重启 IDEA 后，在用户提供的两个发送端附近点击图标：多消费者弹窗应为简短列表；选择任一项仍应打开原目标。还应检查消费者反向跳多个发送端时的列表标题和定位。

本次没有在用户运行的 IDEA 中替换插件或获取新 UI 截图。
