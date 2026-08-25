# Data Model: RabbitMQ 刷新进度提示

本功能不新增持久化数据、数据库表或独立生产模型类。以下内容描述 `MqTopologyPanel` 内已有刷新状态与新增进度组件之间的关系。

## 1. Refresh Progress View

代表侧边栏状态行中的进度提示。

### Fields

- `visible`: 用户当前是否能看到进度条。
- `indeterminate`: 进度条是否处于持续活动状态。
- `componentName`: 稳定测试标识，计划值为 `mqTopologyRefreshProgress`。
- `statusText`: 与进度提示并列的现有状态文字。

### Validation Rules

- `statusText = 正在刷新 RabbitMQ 端点` 时，`visible=true` 且 `indeterminate=true`。
- `statusText` 为等待索引、失败、空结果、无匹配或普通成功状态时，`visible=false` 且 `indeterminate=false`。
- 不允许 `visible=false` 且 `indeterminate=true`，避免隐藏组件继续动画。
- 不显示完成百分比、剩余时间、阶段数量或自定义字符串。
- 出现和消失不得替换或清空 tree model。

### State Transitions

```text
HIDDEN
    --最新刷新开始且项目可分析--> ACTIVE_INDETERMINATE

ACTIVE_INDETERMINATE
    --最新刷新成功--> HIDDEN
    --最新刷新失败--> HIDDEN
    --出现更新的刷新请求--> ACTIVE_INDETERMINATE

HIDDEN
    --刷新开始但等待索引--> HIDDEN
    --项目关闭或请求过期--> HIDDEN
```

## 2. Refresh Attempt

沿用上一功能中一次侧边栏刷新尝试的概念状态。

### Fields

- `requestId`: 在 EDT 单调递增的现有刷新请求序号。
- `isLatest`: 当前请求是否仍等于面板最新请求序号。
- `refreshing`: 面板是否仍等待最新结果。
- `phase`: `WAITING_FOR_INDEX`、`RUNNING`、`SUCCEEDED`、`FAILED` 或 `OBSOLETE`。
- `result`: 成功时的 RabbitMQ endpoint 列表；失败时不替换旧结果。

### Validation Rules

- 只有 `isLatest=true` 的成功或失败回调可以结束 Refresh Progress View。
- `OBSOLETE` 回调不得修改进度条、状态文字或 tree model。
- `RUNNING` 对应活动进度条；`WAITING_FOR_INDEX` 使用文字反馈但不显示进度条。
- `SUCCEEDED` 和 `FAILED` 都必须先结束进度条，再更新最终内容或文字。
- 项目关闭后不访问已释放 UI，该生命周期保护不形成新的进度结果状态。

## 3. Sidebar Status Row

代表始终位于树滚动区下方的状态展示区域。

### Fields

- `statusLabel`: 现有状态文字组件。
- `refreshProgress`: 新增 Refresh Progress View。
- `layout`: 文字占主要空间，进度条位于同一行的非遮挡区域。

### Validation Rules

- 状态行始终存在，进度条不可覆盖树、搜索框、类型筛选或刷新入口。
- 进度条占位区始终保留平台首选宽度，高度取进度条首选高度与状态文字字体高度的较大值；进度条出现或消失时状态行和树滚动区高度不变。
- 状态文字保持现有精确内容和优先级。
- 刷新期间搜索或类型筛选触发树重建时，状态行仍保持刷新状态。
- 进度条切换不得改变模块展开集合、endpoint 数量或导航目标。

## State Matrix

| Refresh Phase | Status Text | Progress Visible | Indeterminate | Tree Handling |
|---------------|-------------|------------------|---------------|---------------|
| `WAITING_FOR_INDEX` | 等待项目索引完成 | No | No | 保留旧树 |
| `RUNNING` | 正在刷新 RabbitMQ 端点 | Yes | Yes | 保留旧树并允许浏览 |
| `SUCCEEDED` with endpoints | 空字符串或现有筛选反馈 | No | No | 最新结果替换树 |
| `SUCCEEDED` empty | 未发现 RabbitMQ 端点 | No | No | 合法空结果替换树 |
| `FAILED` | 刷新失败，已保留上次结果 | No | No | 保留旧树 |
| `OBSOLETE` | 不改变当前文字 | 不改变 | 不改变 | 不改变 |
| 项目关闭 | 不再更新 | 不再更新 | 不再更新 | 不再更新 |

## Invariants

- 进度提示只反映最新刷新请求，不形成第二套请求状态。
- 用户源码、配置、构建文件和 endpoint 模型保持只读。
- endpoint 扫描、匹配、排序、缓存和导航结果不受进度提示影响。
- UI 状态只在 EDT 修改；后台 read action 不直接操作 Swing 组件。
- 进度提示不连接 RabbitMQ、Kafka、RocketMQ 或其他运行时服务。
