# Navigation UI Rules

本文定义 gutter 入口、单/多目标导航、无匹配提示和刷新行为。

## 1. Marker Placement

| Endpoint | Anchor leaf | Icon/tooltip direction |
|----------|-------------|------------------------|
| Rabbit/Kafka/Rocket producer method call | 被调用方法名的 `PsiIdentifier` | “查找消息接收端” |
| Rabbit/Kafka listener method | listener 方法名 `PsiIdentifier` | “查找消息发送端” |
| Rabbit class-level listener | 类名 `PsiIdentifier` | “查找消息发送端” |
| RocketMQ listener class | 类名 `PsiIdentifier` | “查找消息发送端” |

- 一个源码锚点只显示一个 MQ Navigator marker。
- producer 和 consumer 使用不同图标或清晰 tooltip 区分方向。
- marker 只挂最小叶子元素，不能挂整段注解、方法或类。
- marker 只保存 project、源锚点智能指针和查询键，不保存创建 marker 时的目标列表；同一个仍可见 marker 每次点击必须查询当前快照。

## 2. Navigation Outcomes

| Status | Marker | Click behavior |
|--------|--------|----------------|
| `ONE_MATCH` | Show | 直接打开并定位到唯一目标 |
| `MULTIPLE_MATCHES` | Show | 使用 IntelliJ 标准选择弹窗展示全部有效目标 |
| `NO_MATCH` | Show | 不移动光标，提示“当前项目未找到匹配的接收端/发送端” |
| `UNRESOLVED` | Show | 不跳转，展示具体动态原因，例如“topic 来自运行时变量” |
| `UNCERTAIN` | Show | 不声明确定投递；提示同 topic 候选存在但过滤条件无法静态确认 |
| dumb/indexing | Hide temporarily | 不访问索引、不报错；smart mode 后由 daemon 恢复 marker |

零目标、动态和不确定状态使用非阻塞 IDE 通知，不弹模态对话框。

### Click-time query rules

1. 点击处理先在 EDT 捕获标准弹窗的显示位置。
2. 通过 IntelliJ 平台 non-blocking read action 等待文档提交和 smart mode，并绑定 project 生命周期；不创建自定义线程、监听器或任务队列。
3. 查询当前 `MqNavigationService` 的 `CachedValue`，过滤失效 pointer 并稳定排序后生成本次 `NavigationOutcome`。
4. 回到 EDT 处理结果：零目标显示通知；单目标确认 `canNavigate()` 后导航；多目标使用 `NavigationUtil` 标准 PSI 目标弹窗和 `MqTargetRenderer`。

marker 创建后若目标被新增、删除、移动、重命名或修改 destination，即使 daemon 尚未重建 marker，下一次点击也必须反映提交后的最新状态。

## 3. Multiple Target Presentation

每个候选至少显示：

```text
[MQ system] module / file : class#method — destination summary
```

Examples:

```text
[Kafka] order-consumer / OrderListener.java : OrderListener#consume — topic=orders, group=order-group
[RabbitMQ] billing / BillingListener.java : BillingListener#handle — exchange=invoice, key=created, queue=billing.invoice
[RocketMQ] notify / SmsListener.java : SmsListener — topic=notify, selector=SMS||ALL
```

排序键固定为：

1. module name
2. file name
3. containing class name
4. member name
5. endpoint id

失效 pointer 在构建列表前过滤。排序后不因 Map 遍历顺序变化而抖动。

## 4. Refresh Behavior

- Java 源码、配置 PSI 或项目根变化后，旧快照失效；下一次 daemon 分析使用新快照。
- 删除、移动、重命名或移除注解后，旧目标不能继续出现在候选列表；已显示 marker 的下一次点击也不能继续使用旧结果。
- `Tools > MQ Navigator > Rescan Endpoints` 主动增加刷新计数并重启当前项目代码分析。
- 主动刷新不扫描外部仓库、不连接 broker、不修改项目文件。
- 刷新正在等待 smart mode 时，最多显示一次项目级“等待索引完成”提示，不为每个 marker 排队。

## 4.1 RabbitMQ Tool Window

- Tool Window 使用 RabbitMQ SVG 图标，图标资源按 16px 工具栏尺寸渲染。
- 树的固定层级为 `模块 → Consumers / Producers / Topology`；Topology 下继续分 Queue、Binding 和 Exchange 声明。
- 文本筛选覆盖模块、文件、类、成员、队列、交换机、routing key、角色和动态诊断；类型筛选支持 All、Consumers、Producers、Topology 和 Unresolved。
- Consumer 节点可展开查看 Binding 和关联 Producer；Producer 节点可展开查看关联 Consumer；双击任何带源码端点的节点均在 read action 中安全导航。
- Tool Window 直接读取 `MqNavigationService` 的同一快照，不维护第二套扫描结果或缓存；原 gutter 双向导航保持不变。

## 5. Error Isolation

- 单个文件或端点解析失败时记录调试信息并跳过该候选；其他端点继续参与导航。
- 不捕获 `IndexNotReadyException` 作为正常分支；provider 在 dumb mode 进入前即停止索引访问。
- 用户可见提示只包含消息系统、方向、目的地摘要和原因，不显示堆栈或内部类名。
- 插件错误不得修改编辑器内容、移动用户光标或打开无关文件。

## 6. Performance Measurement Boundary

- 导航样本从后台 read action 调用查询服务前开始，到 `NavigationOutcome` 已完成、失效 pointer 已过滤且目标已稳定排序时停止；不包含 gutter 绘制、popup paint 或编辑器动画。
- 刷新样本从 write action 修改并由 `PsiDocumentManager` 提交后开始，到索引重新可用、缓存重新计算且查询返回新 destination 时停止。
- 两类查询都不得在 EDT 执行；计时后的正确性断言必须确认新结果存在且旧结果消失。

## 7. Acceptance Matrix

| Scenario | Expected result |
|----------|-----------------|
| 一个 producer 对应一个 consumer | 点击后直接定位 consumer |
| 一个 consumer 对应一个 producer | 点击后直接定位 producer |
| 一个 producer 对应多个 consumer | 弹出全部稳定排序的 consumer 候选 |
| 一个 consumer 对应多个 producer | 弹出全部稳定排序的 producer 候选 |
| 无匹配 | 保留当前位置并显示方向明确的提示 |
| 动态 destination | 保留 marker，提示无法静态定位 |
| 目标删除或移动 | 旧目标消失；移动后使用新位置 |
| marker 显示后目标发生变化 | 不等待 marker 重建，再次点击同一 marker 即使用已提交的最新结果 |
| IDE indexing | 不冻结、不报索引异常；smart 后自动出现结果 |
| 主动 Rescan | 失效缓存并得到与当前源码一致的结果 |
