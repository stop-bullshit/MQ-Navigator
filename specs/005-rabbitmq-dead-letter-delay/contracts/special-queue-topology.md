# RabbitMQ Special Queue Topology Behavior

## Purpose

定义 MQ Navigator 对队列参数型死信和 TTL 延迟队列的可观察行为，供 parser、缓存、侧边栏和测试共同核对。

## Supported Inputs

### Single Argument Form

- `x-dead-letter-exchange`
- `x-dead-letter-routing-key`
- `x-message-ttl`

同一 builder 链可出现多个单参数设置，后出现的同名线性设置生效。

### Argument Map Form

- 当前方法内局部 Map 的线性 `put`
- `Map.of` 中的键值对
- Map 中无关参数允许存在并忽略

条件分支、循环或外部方法返回的值不执行；已知特殊 key 保留为动态诊断。

## Queue Classification

| DLX configured | TTL configured | Visible classification |
|----------------|----------------|------------------------|
| No | No | Standard queue |
| Yes | No | Dead-letter source |
| No | Yes | Queue with TTL; no routed delay path |
| Yes | Yes | Delay queue |

分类只依赖源码参数，不依赖方法名、常量名或队列名。

## Visible Queue Information

队列节点摘要必须包含所有已知项：

- queue 名称；
- `dead-letter` 或 `delay` 类型；
- TTL 毫秒值或未知提示；
- dead-letter exchange 值或未知提示；
- 显式 dead-letter routing key，或“沿用原路由键”；
- 特殊参数诊断。

现有模块、类、方法和动态 endpoint 标识保持不变。

## Relationship Categories

带静态 DLX 的 Queue declaration 可以出现以下一层子分类：

- `Dead-letter exchanges`
- `Dead-letter bindings`
- `Dead-letter queues`

每个子节点引用真实现有 endpoint，并使用现有双击源码导航。子节点不继续递归展开关系。

## Matching Rules

1. Exchange 名称必须与静态 DLX 完全相等。
2. 显式 dead-letter routing key 使用现有 direct/topic/fanout 规则匹配 Binding。
3. fanout exchange 不要求 routing key。
4. 未配置专用 dead-letter routing key 时，direct/topic/headers 不宣称精确目标；fanout 可以展示全部匹配 binding。
5. 默认 exchange 且存在静态 routing key 时，名称相同的 Queue declaration 是目标。
6. 多个匹配 Binding 或 Queue 全部展示。
7. 动态 DLX、动态 key、headers/未知 exchange type 或缺失声明不产生虚构精确目标。

## Search and Filter

- 现有文本搜索应命中 queue、DLX、dead-letter key、`dead-letter`、`delay` 和 TTL 摘要。
- 现有 RabbitMQ/Topology 筛选继续生效；不新增筛选选项。
- 搜索和筛选不得改变关系匹配，只改变可见树。

## Refresh and Cache

- 手动刷新重新扫描特殊参数和关系。
- 成功扫描保存 cache v2；重新打开项目恢复相同设置和源码位置。
- cache v1 或损坏设置整批失效并回退扫描。
- 刷新失败继续保留旧树，不用部分新设置覆盖。

## Failure and Safety

- 单个声明解析失败不影响其他 endpoint。
- 环形 dead-letter 路径只展示一层，不无限展开。
- 不执行用户配置，不连接 broker，不写用户项目。
- Kafka、RocketMQ 和普通 RabbitMQ 行为不变。
