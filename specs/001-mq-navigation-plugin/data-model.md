# Data Model: MQ 发送接收双向导航插件

本插件不保存业务数据。以下模型全部存在于当前 IDEA 项目生命周期内，并随 PSI 或项目根变化重建。

## 1. MqEndpoint

表示一个可识别的消息发送位置、接收位置或 RabbitMQ 静态队列声明位置。

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `endpointId` | String | Yes | 由消息系统、角色、文件 URL 和锚点位置组成的快照内唯一标识 |
| `system` | Enum | Yes | `RABBITMQ`、`KAFKA`、`ROCKETMQ` |
| `role` | Enum | Yes | `PRODUCER`、`CONSUMER` 或 `DECLARATION` |
| `destination` | MqDestination | Yes | 已提取的系统特定目的地信息 |
| `resolutionState` | Enum | Yes | `STATIC`、`CONFIG_RESOLVED`、`DYNAMIC`、`INVALID` |
| `resolutionMessage` | String | Conditional | 动态、歧义或无效时的用户可读原因 |
| `sourcePointer` | Smart pointer | Yes | 可跨 PSI 重解析恢复的源码锚点；禁止保存裸 PSI 作为长期值 |
| `moduleName` | String | Yes | 多候选列表中的模块信息 |
| `fileName` | String | Yes | 多候选列表中的文件信息 |
| `className` | String | Yes | 所属类；发送调用位于方法体时取外围类 |
| `memberName` | String | Yes | 方法名或类级接收端名称 |

### Validation Rules

- 端点必须来自当前项目的源码范围，库源码、编译产物和外部仓库不进入快照。
- `STATIC` 与 `CONFIG_RESOLVED` 必须满足对应消息系统的必填目的地字段，否则降为 `DYNAMIC` 或 `INVALID`。
- `DYNAMIC` 端点保留 gutter 和诊断，但不得进入确定匹配集合。
- `sourcePointer.getElement()` 返回空、失效或已离开项目源码范围时，该端点在查询结果中过滤掉。
- `endpointId` 只用于一次快照内去重；文件移动或文本改变后由新快照生成新标识，不作为持久化 ID。

## 2. MqDestination

一个具体、不可变的目的地值对象。使用明确字段而不是 `Map<String, String>`，避免拼错字段；不建立三套目的地类层次。

### Common Fields

| Field | Applies To | Meaning |
|-------|------------|---------|
| `topic` | Kafka, RocketMQ | 发送或订阅的 topic |
| `exchange` | RabbitMQ | 发送 exchange 或监听 binding exchange；默认 exchange 为 `""` |
| `queue` | RabbitMQ consumer/declaration | 最终消费或声明的 queue |
| `routingKey` | RabbitMQ producer | 发布 routing key |
| `bindingKeys` | RabbitMQ consumer | 一个 QueueBinding 中的零到多个 binding key |
| `exchangeType` | RabbitMQ consumer | `direct`、`topic`、`fanout`、`headers` 或 custom |
| `topicPattern` | Kafka consumer | Java 正则 topic pattern；与 `topic` 列表互斥 |
| `tag` | RocketMQ producer | destination 中第一个 `:` 后的 tag；没有时为空字符串 |
| `selectorType` | RocketMQ consumer | `TAG` 或 `SQL92` |
| `selectorExpression` | RocketMQ consumer | `*`、tag 表达式或 SQL92 表达式 |
| `consumerGroup` | Kafka, RocketMQ consumer | 仅供候选区分和展示，不作为 producer 目标的一部分 |

### System-Specific Validation

#### RabbitMQ Producer

- `exchange` 与 `routingKey` 必须为 `STATIC` 或 `CONFIG_RESOLVED`。
- 只有 routing key 的模板重载将 exchange 标准化为默认值 `""`；若模板 bean 的默认 exchange 被外部配置覆盖且无法证明，则端点状态为 `DYNAMIC`。

#### RabbitMQ Consumer

- 默认 exchange 监听必须具有 `queue`。
- 命名 exchange 的确定匹配必须来自显式 binding，并具有 `exchange`、`exchangeType` 和 queue。
- direct/topic binding 至少有一个 key；fanout 可以没有 key。
- headers、custom、x-delayed 保留模型但标记不能确定匹配。

#### RabbitMQ Queue Declaration

- 仅识别 resolve 到 `org.springframework.amqp.core.QueueBuilder.durable(String)` 的调用。
- 参数必须是字符串字面量或 Java 编译期常量；链式 `quorum()`、`withArgument(...)`、`build()` 和后续 `RabbitAdmin.declareQueue(...)` 不改变队列名。
- declaration 与同名 listener 可双向匹配；producer/consumer 直接目标存在时优先直接关系，declaration 只作缺失方向的兜底目标。
- 对 `BindingBuilder.bind(queueMethod()).to(exchangeMethod()).with(routingKey)`，快照保留 queue、exchange、exchangeType 和 routingKey 的一对多关系；同一队列的每个 binding 形成独立可匹配目的地。
- `exchangeMethod()` 支持 `new FanoutExchange/TopicExchange/DirectExchange(String, ...)` 和 `ExchangeBuilder.*Exchange(String)` 的静态名称解析。

#### Kafka Producer and Consumer

- Producer 必须具有单个 `STATIC` 或 `CONFIG_RESOLVED` `topic`。
- Consumer 必须具有至少一个静态 topic，或一个可编译的 `topicPattern`。
- 无效正则保留端点并记录原因，不参与确定匹配。
- `consumerGroup` 允许未知。

#### RocketMQ Producer and Consumer

- Producer 必须具有 `STATIC` 或 `CONFIG_RESOLVED` topic；tag 可以为空。
- Consumer 必须具有 topic；未声明 selector 时标准化为 `selectorType=TAG`、`selectorExpression=*`。
- SQL92 保留 selector 原文，但只能形成不确定的同 topic 结果。

## 3. EndpointSnapshot

`MqNavigationService` 内部的不可变缓存结果，不对其他插件公开。

| Field | Type | Meaning |
|-------|------|---------|
| `endpointsById` | Map | 快照内去重后的所有端点 |
| `producersBySystem` | Map<System, List<Endpoint>> | 反向查询发送端的候选集 |
| `consumersBySystem` | Map<System, List<Endpoint>> | 查询接收端的候选集 |
| `createdAtModificationCount` | Long | 调试和测试用的 PSI 修改计数，不用于业务判断 |

### Validation Rules

- 所有集合在发布后不可修改。
- 列表按 `moduleName/fileName/className/memberName/endpointId` 固定排序，保证多候选顺序稳定。
- 同一个 parser 多次发现同一源码锚点时按 `endpointId` 去重，不合并不同 queue、topic 或 binding。
- `EndpointSnapshot` 不保存裸 `PsiFile` 或其他短生命周期计算依赖。
- 一次缓存计算检查过的全部配置 PSI 文件由局部依赖集合收集，并与物理 PSI 修改计数、项目根/VFS 结构变化和主动刷新计数一起传给 `CachedValueProvider.Result`；依赖集合不随快照发布。

## 4. NavigationOutcome

一次 gutter 查询的瞬时结果；可以作为方法返回值或服务内部 record，不需要持久化。

| Field | Type | Meaning |
|-------|------|---------|
| `source` | MqEndpoint | 用户当前触发导航的端点 |
| `direction` | Enum | `TO_CONSUMER` 或 `TO_PRODUCER` |
| `status` | Enum | `UNRESOLVED`、`NO_MATCH`、`ONE_MATCH`、`MULTIPLE_MATCHES`、`UNCERTAIN` |
| `targets` | List<MqEndpoint> | 经过规则匹配、失效过滤和稳定排序后的目标 |
| `message` | String | 零目标、动态值或不确定结果的说明 |

### Outcome Rules

- `UNRESOLVED`: source 本身为 `DYNAMIC` 或 `INVALID`，targets 为空。
- `NO_MATCH`: source 可确定，但没有任何确定或不确定候选。
- `ONE_MATCH`: 恰好一个确定目标，直接跳转。
- `MULTIPLE_MATCHES`: 两个及以上确定目标，交给平台候选列表。
- `UNCERTAIN`: 存在同 topic 等粗粒度候选，但因 SQL92、Rabbit headers/custom topology 等原因无法证明；只展示说明，不执行确定跳转。

## 5. Relationships

```text
EndpointSnapshot 1 ── contains ── * MqEndpoint
MqEndpoint      1 ── owns ────── 1 MqDestination
MqEndpoint      1 ── source of ─ 1 NavigationOutcome
NavigationOutcome 1 ── targets ─ 0..* MqEndpoint
```

- producer 与 consumer 不直接互相引用；关系每次由 `MqEndpointMatcher` 从当前快照推导。
- 相同 topic 下的 consumer group 是展示维度，不改变 Kafka/RocketMQ producer 的目标集合。
- RabbitMQ queue 是最终消费位置，exchange/routing key/binding 决定 producer 是否能到达该 queue。

## 6. State Transitions

### Endpoint Resolution

```text
DISCOVERED
  ├─ literal / compile-time constant ─→ STATIC
  ├─ annotation placeholder or direct @Value field
  │    + unique project config scalar ─→ CONFIG_RESOLVED
  ├─ runtime / ambiguous expression ─→ DYNAMIC
  └─ malformed supported syntax ─────→ INVALID
```

状态在单次扫描中确定，不原地升级或降级。源码或配置变化会使整个旧快照失效并创建新端点对象。

### Snapshot Lifecycle

```text
MISSING → COMPUTED → VALID
                    ├─ PSI/config/root change → INVALIDATED → discarded
                    └─ manual refresh         → INVALIDATED → discarded
```

不存在后台持久化或跨 IDE 会话恢复。缓存从首个 MVP 故事开始使用；重新计算只能通过平台 non-blocking read action，在文档已提交、smart mode、非 EDT 的代码分析路径执行。

## 7. Matching Reference

完整的识别入口、解析边界和匹配表见 [mq-detection.md](contracts/mq-detection.md)。用户交互状态见 [navigation-ui.md](contracts/navigation-ui.md)。
