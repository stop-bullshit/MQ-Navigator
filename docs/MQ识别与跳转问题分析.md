# MQ 识别与跳转问题分析

日期：2026-10-23。范围：用户指出的 RabbitMQ 发送封装及相邻的静态值解析、路由匹配、持久化缓存。依据为当前源码和 IntelliJ 平台测试；未连接 broker，未操作用户正在运行的 IDEA。

## 本次已修复的重载漏识别

公开文档使用脱敏后的订单示例，保留原问题的调用结构：`OrderService` 在同一方法中调用 `MessagePublisher.publishMessage(exchange, routingKey, body)` 和 `MessagePublisher.publishMessage(exchange, body)`。

三参数重载直接调用 Spring `convertAndSend(exchange, routingKey, message)`；两参数重载调用 `this.publishMessage(exchange, "", message)`。

原解析只识别直接委托到 Spring 的一层封装，因而漏掉第二个调用。本次支持逐层识别与参数映射，并同步项目索引，覆盖图标和双向跳转。示例的 `orders.status` 声明为 fanout，因此双向跳转回归使用 fanout 路由规则。

插件 0.2.8 同步将缓存版本升为 3，让旧版本漏掉发送端的快照在升级后重建。这只解决升级失效，不代表已解决下述所有缓存新鲜度问题。

## 后续诊断及当前状态

以下 4 个问题此前已分别通过临时诊断测试复现；第 2 项已在 0.2.10 修复，其余问题仍待处理。诊断源文件及当时的原始 XML 位于 `build/diagnostics/MqKnownIssuesDiagnosticTest.java` 和 `build/diagnostics/TEST-MqKnownIssuesDiagnosticTest.xml`；这些是构建目录下的本地证据，清理 build 后会消失。

### 1. 重开项目可能恢复陈旧目的地（优先级 P1）

位置：`src/main/java/cn/luckyh/mqx/navigation/MqNavigationService.java:231`、`:300`。

缓存恢复仅校验缓存版本、字段及源文件/offset 存在；恢复成功后直接返回，没有检查当前源码常量、配置内容是否仍与快照一致。

复现：先扫描 `template.send("before", body)` 并保存状态，再将同一文件同一位置的 topic 改为等长的 `after!`，新建服务并加载旧快照。实际返回 `before`，预期为 `after!`。这模拟关闭 IDE 后由外部修改源码再重开的情况。

影响：重开项目后的第一次导航和拓扑可能沿用旧关系；本次升级后重扫一次也无法保证之后每次离线修改都有效。

建议：恢复前校验源码和配置的有效性；不满足时重扫。当前临时处理是执行 MQ Navigator 的刷新/Rescan Endpoints。

### 2. topic exchange 的 `#` 漏匹配（0.2.10 已修复）

位置：`src/main/java/cn/luckyh/mqx/match/MqEndpointMatcher.java:86`。

| binding key | routing key | 预期 | 实际 |
| --- | --- | --- | --- |
| `#.created` | `created` | EXACT | NONE |
| `#.created` | `order.created` | EXACT | NONE |
| `order.#` | `order` | EXACT | NONE |

原因：正则对非首段统一添加点分隔符，和 `#` 自身包含的分隔符重复，同时没有覆盖 `#` 匹配零段的情况。

RabbitMQ 官方规则明确 `#` 代表零个或多个单词：[RabbitMQ Topics 教程](https://www.rabbitmq.com/tutorials/tutorial-five-spring-amqp)。

0.2.10 改为按路由段匹配，覆盖 `#` 在首、中、尾以及零段、多段。该缺陷此前直接导致 `billing.events` 的 `calc.billing.init` 发送调用无法匹配 `#.init` 绑定，消费者错误地回跳到队列声明；同版修正了声明作为跳转目标的角色边界。

### 3. 条件赋值丢失初始路由（优先级 P2）

位置：`src/main/java/cn/luckyh/mqx/parser/MqValueResolver.java:264`、`:328`。

```java
String key = "created";
if (changed) {
    key = "updated";
}
template.convertAndSend("events", key, body);
```

预期包含 `created` 和 `updated`；实际仅解析到 `updated`。测试中因模板重载的引用收集出现重复条目，但去重后的结果仍仅有 `updated`。

原因：只要发送前存在赋值，就返回赋值右值集合并跳过 initializer；收集赋值时只比较文本位置，没有判断该赋值是否必经。

影响：合法初始路由对应的消费者找不到。反过来，多次无条件覆盖的历史赋值也可能被错误保留。建议按到达发送位置的可达赋值判断；不能证明时保留动态诊断，不将文本出现过的值都当作确定路由。

### 4. direct binding 的空 routing key 被丢弃（优先级 P2）

位置：`src/main/java/cn/luckyh/mqx/parser/RabbitMqEndpointParser.java` 的 `bindingEndpoint`、`applyBindings`、`parseBinding` 中 `isBlank()` 判断。

复现：声明 direct exchange 的 `@QueueBinding(key = "")`。预期保留一个空字符串绑定键，即 `[""]`；实际得到零个绑定键，即 `[]`。

原因：把空字符串等同于不存在绑定键；随后 direct 匹配使用 `bindingKeys.contains(producer.routingKey())`，空 key 发送无法命中。

影响：direct exchange 的空 key 正反向跳转失败。fanout 匹配不检查 key，因此不影响本次用户提供的 `orders.status` 场景。

建议：区分无法解析/不存在与明确解析到 `""`，分别覆盖注解绑定和程序化 BindingBuilder 声明。

## 验证边界

- 上述诊断记录的是修复前状态；第 2 项已处理，第 1、3、4 项仍是现存问题。
- 主修复的正式回归集与临时诊断集分开执行，避免把“已发现的问题”误报成“已经修复”。
- 当前结论来自本地 IntelliJ 2023.3 测试平台及当前源码，不构成真实业务项目中所有目标消费者均可导航的证明。
