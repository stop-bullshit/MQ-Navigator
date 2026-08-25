# Data Model: RabbitMQ 死信与延迟队列拓扑

## 1. MqDestination 扩展

现有 `MqDestination` 继续表示跨 RabbitMQ、Kafka、RocketMQ 的目的地。新增一个可空 RabbitMQ 队列设置值：

| Field | Type | Rules |
|-------|------|-------|
| `rabbitQueueSettings` | `RabbitQueueSettings?` | 仅 Queue declaration 使用；普通 producer/consumer、Binding、Exchange、Kafka、RocketMQ 为 null |

现有 topic、exchange、queue、routingKey、bindingKeys、exchangeType 等字段及匹配含义不变。

## 2. RabbitQueueSettings

`MqDestination` 内部不可变值，描述源码中出现的特殊队列参数。

| Field | Type | Rules |
|-------|------|-------|
| `deadLetterExchangeConfigured` | boolean | 源码出现 `x-dead-letter-exchange` 时为 true，值动态时也保留 true |
| `deadLetterExchange` | String? | 静态值；空字符串表示默认交换机；动态或未配置时为 null |
| `deadLetterRoutingKeyConfigured` | boolean | 源码出现 `x-dead-letter-routing-key` 时为 true |
| `deadLetterRoutingKey` | String? | 静态值；显式空字符串有效；动态或未配置时为 null |
| `messageTtlConfigured` | boolean | 源码出现 `x-message-ttl` 时为 true |
| `messageTtlMillis` | Long? | 静态非负毫秒值；`0` 有效；动态、无效或未配置时为 null |
| `diagnostics` | List<String> | 不可修改；记录已知特殊参数无法静态确定或无效的原因；不得包含 null |

### Validation Rules

1. 未配置字段不得保存对应静态值。
2. `messageTtlMillis` 不得为负数。
3. 静态空 dead-letter exchange 与未配置通过 configured 标记区分。
4. 静态空 routing key 与“沿用原路由键”通过 configured 标记区分。
5. diagnostics 只描述特殊参数，不覆盖 endpoint 现有 destination 解析状态。

### Derived Queue Kind

| Condition | Kind | Display Meaning |
|-----------|------|-----------------|
| `messageTtlConfigured && deadLetterExchangeConfigured` | DELAY | TTL 到期后通过 DLX 转发 |
| `deadLetterExchangeConfigured` | DEAD_LETTER_SOURCE | 消息失败、拒绝或过期时具有 DLX |
| 其他 | STANDARD | 普通队列；如仅 TTL，仍展示 TTL 属性但无可追踪后续路径 |

队列种类不单独持久化，始终由 configured 标记派生。

## 3. Dead-letter Path

UI 从当前不可变 endpoint 快照临时推导，不新增持久实体。

| Part | Source | Match Rule |
|------|--------|------------|
| Source queue | 带 RabbitQueueSettings 的 DECLARATION | dead-letter exchange 已配置 |
| Exchange | EXCHANGE_DECLARATION | exchange 名称等于静态 dead-letter exchange |
| Binding | BINDING | exchange 相同，且显式 dead-letter key 满足现有 RabbitMQ 路由匹配；fanout 无需 key |
| Target queue | DECLARATION | queue 名称等于匹配 binding 的 queue；默认交换机时 queue 名称等于静态 dead-letter key |

关系节点引用原 endpoint，不复制源码位置。多个匹配全部保留，缺失部分不补造。

## 4. Parser Intermediate State

仅在一次 QueueBuilder 声明解析期间存在：

- 特殊参数 key 到 value expression 的有序映射；
- 处于条件分支或循环的特殊 key 集合；
- 后出现的线性静态设置覆盖前值；
- 完成后立即转换为 RabbitQueueSettings，不跨 read action 保存 PSI expression。

## 5. Cache State v2

`MqNavigationService.CachedEndpointState` 增加扁平字段：

- `rabbitQueueSettingsPresent`
- `deadLetterExchangeConfigured`
- `deadLetterExchange`
- `deadLetterRoutingKeyConfigured`
- `deadLetterRoutingKey`
- `messageTtlConfigured`
- `messageTtlMillis`
- `rabbitQueueDiagnostics`

### Restore Rules

1. 缓存版本必须等于 2。
2. settings 不存在时，所有配置标记为 false、值为 null、诊断为空。
3. settings 存在时按 RabbitQueueSettings validation rules 校验。
4. 任一 endpoint 字段无效时整批缓存失效并执行当前项目扫描。
5. 缓存恢复后重新创建现有 SmartPsiElementPointer，设置本身不保存 PSI。

## 6. State Transitions

```text
普通 Queue declaration
    ├─ 发现 DLX → DEAD_LETTER_SOURCE
    ├─ 发现 TTL → STANDARD + TTL 属性
    └─ 同时发现 DLX + TTL → DELAY

任一特殊值动态/无效
    → 保留 configured 标记
    → 静态值为 null
    → 增加 diagnostic
    → Queue declaration 与普通 Binding 关系继续有效

源码刷新/项目变化
    → 现有快照失效
    → 重新解析设置和关系
    → 完整成功后写入 cache v2
```
