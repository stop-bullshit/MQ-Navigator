# Data Model: 侧边栏模块浏览与刷新优化

本功能不新增持久化数据或业务数据库表。以下实体是侧边栏会话中的概念状态，实际实现优先使用 `MqTopologyPanel` 现有字段、私有 record 和树节点，不要求新增生产模型类。

## 1. Module Group

代表侧边栏中的一个 RabbitMQ 模块分组。

### Fields

- `moduleKey`: 稳定模块名；空模块名归一为 `(project)`。
- `displayLabel`: 模块名与当前可见 endpoint 数量组成的展示文本。
- `visibleEndpointCount`: 当前搜索和类型筛选条件下的 endpoint 数量。
- `categories`: 当前模块可展示的 Consumers、Producers、Topology 及其子分类。
- `expanded`: 正常浏览状态下用户是否展开该模块。

### Validation Rules

- `moduleKey` 在当前树快照中必须唯一，不能包含数量等易变展示信息。
- `visibleEndpointCount` 必须等于该模块当前实际展示的 endpoint 数量。
- 数量为 0 的模块不创建节点；项目完全无结果时由根与状态文本表达空状态。
- 新模块或重命名后的模块没有已有状态时，`expanded=false`。

### Relationships

- 一个 Module Group 包含零到多个分类节点。
- 一个 Module Group 的正常展开状态由 Module Expansion State 管理。

## 2. Sidebar Endpoint Snapshot

代表某次成功刷新后侧边栏消费的 RabbitMQ endpoint 集合。

### Fields

- `endpoints`: 从现有 `MqNavigationService` 快照筛选出的 RabbitMQ endpoint 有序列表。
- `totalCount`: 未应用搜索或类型筛选前的 endpoint 数量。
- `visibleCount`: 应用当前条件后的 endpoint 数量。
- `moduleGroups`: 按 `moduleKey` 排序后的模块分组。
- `refreshRequestId`: 产生该可见结果的刷新请求序号。

### Validation Rules

- 只包含 `system=RABBITMQ` 的 endpoint。
- endpoint 原有排序、角色、解析状态、目标摘要和源码智能指针保持不变。
- 只有最新成功请求可以替换当前可见快照。
- 失败或过期请求不得修改当前快照。

## 3. Module Expansion State

代表本次面板生命周期内用户选择的模块展开集合。

### Fields

- `expandedModuleNames`: 用户在正常浏览状态下展开的稳定模块名集合。
- `applyingTreeState`: 面板是否正在程序化设置模型或恢复路径。
- `filterActive`: 文本搜索非空或类型筛选不是 All。

### Validation Rules

- 只有用户在 `applyingTreeState=false` 且 `filterActive=false` 时触发的模块展开/折叠才更新集合。
- 刷新后删除当前树不存在的模块 key。
- 筛选期间的临时展开不得写入 `expandedModuleNames`。
- 该状态只存活于 `MqTopologyPanel` 实例，不跨 IDE 重启持久化。

### State Transitions

```text
首次出现模块
    → COLLAPSED

COLLAPSED --用户展开且无筛选--> EXPANDED
EXPANDED  --用户折叠且无筛选--> COLLAPSED

COLLAPSED/EXPANDED --启用筛选--> TEMPORARILY_EXPANDED_IF_MATCHED
TEMPORARILY_EXPANDED_IF_MATCHED --清除筛选--> 原 COLLAPSED/EXPANDED

任意状态 --模块删除--> ABSENT
任意状态 --模块重命名--> 旧 key ABSENT + 新 key COLLAPSED
```

## 4. Refresh Attempt

代表一次面板刷新尝试的可见生命周期。

### Fields

- `requestId`: 在 EDT 单调递增的面板请求序号。
- `trigger`: 工具窗口首次加载、侧边栏刷新或现有服务转发刷新。
- `phase`: `WAITING_FOR_INDEX`、`RUNNING`、`SUCCEEDED`、`FAILED`、`OBSOLETE` 或 `CANCELLED_BY_PROJECT`。
- `result`: 成功时的 endpoint 列表；失败和取消时为空。
- `message`: 用户可见的短状态文本。

### Validation Rules

- `requestId` 只在 EDT 创建和比较。
- 只有 `requestId` 等于面板当前最新请求时，`SUCCEEDED` 或 `FAILED` 才能更新可见状态。
- `FAILED` 保留当前 Sidebar Endpoint Snapshot。
- `SUCCEEDED` 且 endpoint 为空是合法业务空结果，必须替换旧快照。
- 项目关闭导致的取消不显示误导性失败，也不访问已释放 UI。

### State Transitions

```text
CREATED
    → WAITING_FOR_INDEX（项目处于索引状态）
    → RUNNING（项目可分析）

WAITING_FOR_INDEX → RUNNING
RUNNING → SUCCEEDED
RUNNING → FAILED
WAITING_FOR_INDEX/RUNNING → OBSOLETE（出现更大的 requestId）
任意未完成状态 → CANCELLED_BY_PROJECT（Project disposed）
```

## 5. Filter View

代表现有文本搜索与 endpoint 类型筛选的组合视图。

### Fields

- `query`: 去除首尾空格后的大小写无关文本。
- `endpointType`: All、Consumers、Producers、Topology 或 Unresolved。
- `active`: `query` 非空或 `endpointType != All`。
- `matchedModuleKeys`: 至少包含一个可见 endpoint 的模块名集合。

### Validation Rules

- active 时只临时展开 matched modules 和分类层。
- endpoint 关系子树不因筛选自动展开。
- visible count 与模块数量使用筛选后的 endpoint 集合计算，总数保留筛选前值。
- 清除全部条件后恢复 Module Expansion State。

## Invariants

- 用户源码、配置、构建文件和现有 endpoint 模型均只读。
- 模块展开状态不影响 endpoint 扫描、匹配、解析和源码导航结果。
- 任何失败或过期刷新都不能把已成功展示的树替换成空树或旧树。
- UI 组件和 tree model 只在 EDT 修改；项目级 endpoint 扫描不在 EDT。
