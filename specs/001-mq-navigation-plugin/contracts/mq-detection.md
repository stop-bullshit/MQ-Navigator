# MQ Detection and Matching Rules

本文定义首版能够识别的 Java 源码形式和发送端/接收端匹配规则。未列出的形式不能被近似识别为确定关系。

## 1. Common Recognition Rules

- 仅扫描当前打开项目的 Java 源码范围；库源码和编译产物只用于 resolve 类型，不生成导航端点。
- 发送方法必须 resolve 到下表指定的完整限定类型或其可确认子类型；不能只按 `send`、`convertAndSend` 等方法名判断。
- 接收注解必须 resolve 到指定的完整限定注解；同名自定义注解不识别。
- 当前源码存在语法错误时，只跳过无法 resolve 的局部候选，不让单个文件终止整个快照。
- 字符串字面量和 Java 编译期常量为 `STATIC`；注解占位符得到唯一项目配置标量时为 `CONFIG_RESOLVED`；其余为 `DYNAMIC`。
- `DYNAMIC` 端点显示 marker 和原因，但不参与确定连线。

## 2. Value Resolution

| Expression | Result | Matching behavior |
|------------|--------|-------------------|
| `"orders"` | STATIC | 参与匹配 |
| `static final String TOPIC = "orders"` | STATIC | 参与匹配 |
| 编译期常量拼接 | STATIC | 使用折叠后的字符串参与匹配 |
| 注解中的 `${mq.topic}` | CONFIG_RESOLVED 或 DYNAMIC | 项目配置只有一个不同标量值时匹配，否则提示歧义 |
| 注解中的 `${mq.topic:orders}` | CONFIG_RESOLVED 或 DYNAMIC | key 缺失且 default 静态时使用 default；多值仍歧义 |
| producer 参数直接引用 `topic` / `this.topic`，字段为合格 `@Value("${mq.topic}")` | CONFIG_RESOLVED 或 DYNAMIC | 按同模块项目配置解析；只有唯一标量值时匹配 |
| `SomeEnum.VALUE.getCode()` 等枚举常量无参 String getter | STATIC 或 DYNAMIC | getter 字段可明确映射到枚举常量构造参数且参数为静态字符串时匹配，否则保持动态 |
| 注解中的 `#{...}` | DYNAMIC | 不执行 SpEL，不建立确定连线 |
| 其他普通字段、参数、方法返回、StringBuilder、String.format | DYNAMIC | 不做普通对象 getter、别名或跨方法数据流 |
| producer 参数字面量 `"${mq.topic}"` | STATIC literal | 模板发送方法不会自动解析 Spring annotation placeholder |

配置查找限定在端点所属模块资源目录的 `application*` 和 `bootstrap*` `.properties/.yml/.yaml` 文件。同一 key 的多个文件解析出相同标量值时仍可确定；出现不同值时标记歧义。

producer destination 参数必须先 resolve 到受支持的实际方法/重载，再解析对应参数位置。直接字段引用仅在以下条件全部成立时受支持：

- 表达式是无 qualifier 的 `topic` 或 `this.topic`，直接 resolve 到当前类的 `PsiField`。
- 字段类型为 `String`，是普通非 static、非 final 实例字段。
- 字段直接标注完整限定名 `org.springframework.beans.factory.annotation.Value`，注解值恰好是单个 `${key}` 或 `${key:default}` 占位符。
- 不支持 `Outer.this.topic`、`other.topic`、static/final `@Value` 字段、普通对象 getter、构造参数流、局部变量别名、方法返回、跨方法传递、混合文本、嵌套占位符或 SpEL。枚举常量 getter 仅限字段到构造参数映射可由源码证明的静态 String。

解析值时使用固定顺序：字面量 → Java 编译期常量 → 合格的直接 `@Value` 字段。任一步不能唯一证明静态值时保持端点并记录 `DYNAMIC` 原因，不继续猜测数据流。

## 3. RabbitMQ

### 3.1 Producer Recognition

**Types**:

- `org.springframework.amqp.rabbit.core.RabbitTemplate`
- `org.springframework.amqp.core.AmqpTemplate`
- 可由 PSI 确认继承/实现上述类型的项目类

**Methods**:

| Method shape | Normalized target | Status |
|--------------|-------------------|--------|
| `send(routingKey, message, ...)` | exchange=`""`, routingKey | Determinate when routingKey is STATIC or CONFIG_RESOLVED |
| `send(exchange, routingKey, message, ...)` | exchange, routingKey | Determinate when both are STATIC or CONFIG_RESOLVED |
| `convertAndSend(routingKey, payload, ...)` | exchange=`""`, routingKey | Determinate when routingKey is STATIC or CONFIG_RESOLVED |
| `convertAndSend(exchange, routingKey, payload, ...)` | exchange, routingKey | Determinate when both are STATIC or CONFIG_RESOLVED |
| overload with no destination argument | unknown template defaults | Dynamic; no determinate link |

尾部 `MessagePostProcessor`、`CorrelationData` 等参数不改变目的地参数位置。只写 routing key 的重载以 RabbitTemplate 标准默认 exchange `""` 处理；如果代码明确配置了其他模板默认 exchange 而首版无法追踪 bean 状态，则不承诺该调用的运行时关系。

### 3.2 Consumer Recognition

**Annotations**:

- `org.springframework.amqp.rabbit.annotation.RabbitListener`
- nested `QueueBinding`、`Queue`、`Exchange`

| Annotation shape | Normalized binding |
|------------------|--------------------|
| `@RabbitListener(queues = {...})` | 每个 queue → default exchange, binding key=queue |
| `@RabbitListener(queuesToDeclare = @Queue(...))` | 每个 named queue → default exchange, binding key=queue |
| `@RabbitListener(bindings = @QueueBinding(...))` | 每个 queue × 每个 key → explicit exchange binding |
| anonymous/broker-named queue | queue unknown | Dynamic; no determinate source link |

`@Exchange.type` 缺失时标准化为 `direct`。一个 `QueueBinding.key` 数组中的每个值都是独立 binding。

### 3.3 Static Queue Declarations and Programmatic Topology

识别 resolve 到 `org.springframework.amqp.core.QueueBuilder.durable(String)` 的方法调用。首参数按普通 producer 表达式规则解析字符串字面量或 Java 编译期常量，生成 `DECLARATION` 端点；`quorum()`、`withArgument(...)`、`build()` 与 `RabbitAdmin.declareQueue(...)` 仅为链式/注册细节，不执行也不影响静态队列名。

- declaration 与 `@RabbitListener(queues=...)`、`queuesToDeclare` 或 binding 中的同名 queue 可双向导航。
- consumer 没有项目内 producer 时，匹配 declaration 作为来源定位兜底；存在 producer 时优先 producer，保持原来的直接反向跳转。
- producer 没有已识别 consumer 时，可将 default exchange 可达的同名 declaration 作为兜底；存在 consumer 时优先 consumer。
- declaration 作为源时返回同名 producer/consumer；不把两个 declaration 互相匹配。
- 支持从 `BindingBuilder.bind(queueMethod()).to(exchangeMethod()).with(routingKey)` 解析队列与交换机之间的静态关系；`queueMethod()` 内的 `QueueBuilder.durable(String)`、`exchangeMethod()` 内的 `new FanoutExchange/TopicExchange/DirectExchange(...)` 或 `ExchangeBuilder.*Exchange(String)` 会被解析。
- 同一个队列的多个 binding 全部保留，监听端和声明端会分别展开为多个交换机/路由键候选。
- 对项目源码中直接委托到 `RabbitTemplate`/`AmqpTemplate` `send` 或 `convertAndSend` 的发送封装，按被委托方法的参数语义映射调用方参数；一 String 重载表示 routing key，两 String 重载表示 exchange 与 routing key。无法解析实现或参数映射的包装方法保持动态。
- headers/custom exchange、无法静态解析的 bean 方法、局部变量别名和运行时 Spring Bean 图仍不支持。

### 3.4 Matching

先要求 producer exchange 与 consumer binding exchange 相同，再执行：

| Exchange type | Rule |
|---------------|------|
| default `""` | producer routing key 必须等于 queue name |
| direct | routing key 必须与任一 binding key 完全相等 |
| topic | `*` 匹配一个 `.` 分段，`#` 匹配零到多个分段 |
| fanout | exchange 相同即匹配，忽略 routing key |
| headers/custom/x-delayed | 只形成不确定诊断，不标记确定目标 |

只有 `queues=...` 的 listener 在没有可解析 binding 时不包含命名 exchange 拓扑；当项目中存在上述静态 `BindingBuilder` 关系时，会通过队列名补齐 exchange 和 binding key 后再与 producer 匹配。

## 4. Kafka

### 4.1 Producer Recognition

**Type**: `org.springframework.kafka.core.KafkaTemplate` 或可确认子类型。

**Included**: `send(String topic, ...)` 中首参数为 `STATIC` 或 `CONFIG_RESOLVED` topic 的重载。

**Excluded in v1**:

- `sendDefault(...)`
- `send(ProducerRecord)`
- `send(Message<?>)` 以及 header 中的 topic
- 包装方法或跨方法传递 topic

### 4.2 Consumer Recognition

**Annotation**: `org.springframework.kafka.annotation.KafkaListener`

| Attribute | Normalization |
|-----------|---------------|
| `topics = {...}` | 每个静态 topic 形成一个订阅 |
| `topicPattern = ...` | 编译为 Java Pattern；无效正则记录 INVALID |
| `groupId` | 展示字段，不参与 producer 匹配 |
| `id` | groupId 未显式提供时可作为展示信息；不证明运行时 factory group |
| `topicPartitions` | 首版不识别 |

### 4.3 Matching

- static topic：完全相等。
- topicPattern：使用 `pattern.matcher(producerTopic).matches()` 的完整匹配。
- 返回所有匹配 listener；不同 group 均保留，同 group 的多个成员也不在静态层合并。

## 5. RocketMQ

### 5.1 Producer Recognition

**Type**: `org.apache.rocketmq.spring.core.RocketMQTemplate` 或可确认子类型。

**Methods**:

- `syncSend(String destination, ...)`
- `asyncSend(String destination, ...)`
- `sendOneWay(String destination, ...)`

destination 为 `STATIC` 或 `CONFIG_RESOLVED` 时按第一个 `:` 拆分：左侧为 topic，右侧为 tag；没有 `:` 时 tag 为空。首版不识别 orderly、transaction、request/reply 方法族。

### 5.2 Consumer Recognition

**Annotation**: `org.apache.rocketmq.spring.annotation.RocketMQMessageListener`

| Attribute | Normalization |
|-----------|---------------|
| `topic` | 必填静态 topic |
| `consumerGroup` | 展示字段，不参与 producer 匹配 |
| `selectorType` | 缺失时为 `TAG` |
| `selectorExpression` | 缺失时为 `*` |

### 5.3 Matching

1. producer 与 consumer topic 必须完全相等。
2. `selectorType=TAG`：
   - `*` 匹配该 topic 的任何 tag，包括空 tag；
   - 单 tag 完全相等；
   - `TagA||TagB` 拆分后按 OR 精确匹配。
3. `selectorType=SQL92`：首版不能静态取得消息属性，只产生 `UNCERTAIN` 诊断，不标记确定目标。
4. consumer group 只用于列表区分，不过滤 producer 关系。

## 6. Reverse Navigation

- Consumer → Producer 使用与 Producer → Consumer 完全相同的匹配函数，不另写一套反向规则。
- 一个 consumer 可以得到多个 producer，一个 producer 也可以得到多个 consumer。
- 反向结果不得因为候选数量大而静默截断；失效 pointer 过滤后再决定零、单或多目标状态。

## 7. Explicit Non-Matches

以下情况必须提示原因，不能给出近似跳转：

- 相同字符串位于不同 MQ system。
- RabbitMQ exchange 不同，或 direct/topic key 不匹配。
- Kafka topic 不满足 exact 或 pattern。
- RocketMQ topic 不同，或静态 tag 不满足 TAG selector。
- 配置值多 profile 歧义、SpEL、运行时变量或无法 resolve 的方法。
- 目标只存在于外部仓库、编译依赖或 broker 运行拓扑。
