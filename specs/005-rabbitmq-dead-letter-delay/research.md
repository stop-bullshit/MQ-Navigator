# Research: RabbitMQ 死信与延迟队列拓扑

## Decision 1: 只支持队列参数型死信与 TTL 延迟

**Decision**: 本次识别 `x-dead-letter-exchange`、`x-dead-letter-routing-key`、`x-message-ttl`，覆盖用户示例中的 `withArguments(map)` 和等价 `withArgument(key, value)`；延迟队列定义为 TTL 与 DLX 同时配置。

**Rationale**: 用户指定方法正是 TTL + DLX 模式。它可以完全通过当前项目源码静态确定，并与现有 Queue/Exchange/Binding 拓扑直接组合。

**Alternatives considered**:

- 同时支持 `x-delayed-message` 和消息头：需要新增 CustomExchange、消息后处理器和发送参数分析，超出当前示例。
- 仅按方法名或队列名识别 dead/delay：存在大量误判，无法证明真实路由。

## Decision 2: 在 MqDestination 内保存 RabbitMQ 队列设置

**Decision**: 增加内部不可变 `RabbitQueueSettings`，保存配置存在性、静态值和诊断；不新增 endpoint 角色或独立关系模型。

**Rationale**: 设置属于目的地声明，必须随现有快照、搜索和缓存流转。内部值类型隔离 RabbitMQ 特有字段，又避免新增顶层类或服务。

**Alternatives considered**:

- 给 `MqEndpoint` 增加多个 RabbitMQ 字段：污染所有消息系统 endpoint，字段归属不清。
- 新建特殊队列服务和关系仓库：形成第二套拓扑状态，增加生命周期与同步风险。
- 仅拼接 resolution message：无法可靠缓存、搜索和推导目标关系。

## Decision 3: 队列名称状态与参数诊断分离

**Decision**: endpoint 的既有 resolution state 继续表示核心目的地是否可解析；动态或无效特殊参数写入 `RabbitQueueSettings.diagnostics`。

**Rationale**: 一个静态队列的 TTL 动态时，队列本身及普通 Binding 仍然有效。把整个 endpoint 改为 dynamic 会错误隐藏既有精确关系。

**Alternatives considered**:

- 任一参数动态就把 endpoint 标记 dynamic：破坏普通 Queue/Binding 与筛选结果。
- 忽略动态参数：用户无法知道声明存在但值未知。

## Decision 4: 参数集合采用窄范围静态追踪

**Decision**: 支持当前方法中的局部 Map、声明前线性 `put` 和 `Map.of`；条件分支、循环、外部方法返回和无法解析 key 不执行。

**Rationale**: 该范围覆盖用户示例和项目常见写法，执行路径明确。更通用的数据流分析需要额外控制流模型，不符合最小范围原则。

**Alternatives considered**:

- 执行配置方法得到 Map：违反用户项目只读和运行时隔离。
- 完整控制流/数据流引擎：当前无性能与覆盖证据支持该复杂度。
- 只支持 `withArgument`：无法覆盖用户指定 `withArguments(args)`。

## Decision 5: 从现有 endpoint 快照推导死信目标

**Decision**: UI 使用已解析的 Exchange、Binding、Queue endpoint 和现有 RabbitMQ 匹配入口计算一层关系，不创建新的持久关系 endpoint。

**Rationale**: 现有 parser 已保存真实源码指针和模块信息。按需推导可以直接导航，也不会产生需要单独失效的关系缓存。

**Alternatives considered**:

- parser 生成新的 DEAD_LETTER_RELATION 角色：需要修改角色枚举、matcher、缓存、筛选和导航分支，改动过大。
- 单独维护关系图：重复当前 endpoint 快照并增加同步风险。

## Decision 6: 缓存版本升级并整批失效旧数据

**Decision**: `MqNavigationService` 缓存版本升级为 2，保存全部 RabbitQueueSettings 字段；版本 1 缓存按现有规则回退完整扫描。

**Rationale**: 版本 1 无法区分普通队列、死信来源和延迟队列。近似恢复会让旧项目看似完整但缺失关键拓扑。

**Alternatives considered**:

- 兼容读取版本 1 并给所有 settings 置空：可能长期展示错误普通队列。
- 增量迁移版本 1：旧数据没有源参数值，只能重新扫描，迁移无信息可补。

## Decision 7: 不修改 matcher 核心规则

**Decision**: 死信 binding 判断调用现有 `match(SystemType, producerDestination, consumerDestination)`；默认交换机和未配置专用 key 的边界在 UI 关系推导处明确处理。

**Rationale**: `MqEndpointMatcher` 当前服务 producer-consumer/gutter 导航。死信关系是 Queue declaration 的附加展示，修改核心 matcher 容易扩大行为影响。

**Alternatives considered**:

- 把死信来源当 producer 加入 matcher：会改变 gutter 目标优先级和现有导航结果。

## Resolved Questions

- 不需要新增依赖、数据库、线程、锁、监听器或网络访问。
- 不需要新增工具窗口、按钮、筛选项或 endpoint 角色。
- 不支持 `x-delayed-message` 插件模式。
- 无剩余 `NEEDS CLARIFICATION`。
