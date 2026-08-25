# Data Model: MQ 端点持久缓存

本功能不新增业务数据库或用户项目文件。以下状态由项目级端点服务保存到 IDE 系统缓存，用于下一次服务启动时恢复现有运行时快照。

## 1. Project Endpoint Cache Snapshot

### Fields

- `version`: 缓存格式版本，首版固定为 `1`。
- `savedAt`: 最近一次完整成功扫描的保存时间；大于零表示快照存在，即使 endpoint 列表为空。
- `endpoints`: 零个或多个 Cache Endpoint Entry，按现有稳定顺序保存。

### Validation Rules

- `version` 必须等于当前支持版本。
- `savedAt` 必须大于零才能作为有效缓存。
- `endpoints` 为空是合法快照，不等同于缓存缺失。
- 所有条目必须有效并全部恢复；部分恢复不形成有效快照。
- 快照只属于创建它的项目级服务，不接受其他项目源码位置。

### State Transitions

```text
MISSING --完整扫描成功--> VALID
VALID --新完整扫描成功--> VALID（整体替换）
VALID --手动刷新或会话内失效--> SCAN_REQUIRED（持久状态暂不删除）
VALID --版本/字段/源码位置无效--> INVALID
INVALID --完整扫描成功--> VALID（修复）
SCAN_REQUIRED --完整扫描成功--> VALID（整体替换）
SCAN_REQUIRED --扫描不完整--> VALID（保留上次持久状态）
```

## 2. Cache Endpoint Entry

### Identity and Classification

- `endpointId`
- `system`
- `role`
- `resolutionState`
- `resolutionMessage`

### Destination Fields

- `topic`
- `exchange`
- `queue`
- `routingKey`
- `bindingKeys`
- `exchangeType`
- `topicPattern`
- `tag`
- `selectorType`
- `selectorExpression`
- `consumerGroup`

### Display Fields

- `moduleName`
- `fileName`
- `className`
- `memberName`

### Source Location

- `sourceUrl`: 当前项目源码文件 URL。
- `sourceOffset`: 文件内文本偏移。

### Validation Rules

- `endpointId`、`system`、`role`、`resolutionState`、`sourceUrl` 必须存在且可识别。
- `sourceOffset` 必须位于当前 PSI 文件有效范围内并能得到有效源码元素。
- 源码文件必须属于当前项目 source content。
- `bindingKeys` 缺失时按空列表处理；恢复后必须为不可修改副本。
- destination、显示和解析字段恢复后必须与扫描前值逐项一致。
- 恢复 endpoint 必须重新创建当前项目的智能源码指针。

## 3. Runtime Endpoint Snapshot

沿用现有 `EndpointSnapshot`，不新增平行运行时模型。

### Fields

- `endpoints`: 排序、去重后的不可修改 `MqEndpoint` 列表。
- `modificationCount`: 发布时当前项目 PSI 修改计数。

### Source

- 首次 provider 计算可来自完整有效的 Project Endpoint Cache Snapshot。
- 缓存未命中或首次机会已使用时来自现有 parser 扫描。
- 无论来源如何，调用者只读取同一个 Runtime Endpoint Snapshot。

## Invariants

- 持久状态不是运行时第二真相源，服务实例只尝试恢复一次。
- 手动刷新后不得再次使用该实例加载时持有的旧持久状态。
- 会话内 PSI 和项目根变化继续失效 Runtime Endpoint Snapshot。
- 缓存恢复和扫描都不改变 endpoint 排序、匹配与导航规则。
- 持久状态不包含 `Project`、裸 PSI、智能指针、锁、线程或运行时连接。
- 用户项目和三个消息系统始终保持只读、零连接。
