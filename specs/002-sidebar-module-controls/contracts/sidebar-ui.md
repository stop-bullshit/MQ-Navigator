# Sidebar UI Interaction: 侧边栏模块浏览与刷新优化

## 1. Tree Structure

正常结果按以下层级展示：

```text
RabbitMQ (visible/total)
└── module-name (visible count)
    ├── Consumers
    ├── Producers
    └── Topology
        ├── Queues
        ├── Bindings
        └── Exchanges
```

- 根节点始终可见并展开。
- 首次加载和新模块默认折叠。
- 模块展开后沿用现有分类和 endpoint 标签，不改变关系节点和双击导航目标。
- 模块身份只使用稳定模块名，展示数量变化不得重置状态。

## 2. Module Interaction Matrix

| Initial State | User Action | Required Result |
|---------------|-------------|-----------------|
| 模块折叠、无筛选 | 展开模块 | 显示该模块分类；其他模块状态不变；记录为会话展开状态 |
| 模块展开、无筛选 | 折叠模块 | 隐藏该模块内容；其他模块状态不变；从会话展开状态移除 |
| 任意状态、筛选启用 | 展开或折叠模块 | 只影响当前临时筛选视图；清除筛选后恢复筛选前状态 |
| 模块仍存在 | 刷新成功 | 恢复刷新前正常浏览状态 |
| 模块首次出现 | 刷新成功 | 新模块默认折叠 |
| 模块删除或重命名 | 刷新成功 | 删除旧节点；重命名后的新节点默认折叠 |

## 3. Search and Type Filter

- 文本搜索和 endpoint 类型筛选共用现有匹配逻辑与数量口径。
- 任一条件启用时，所有包含匹配 endpoint 的模块和分类层临时展开。
- endpoint 下的 Bindings、Related consumers、Related producers 等关系子树不自动展开。
- 无匹配时显示“当前条件无匹配结果”，刷新入口仍可使用。
- 清除文本且类型恢复 All 后，恢复用户正常浏览状态。

## 4. Refresh Action

- 刷新入口位于现有侧边栏工具栏，使用平台标准刷新图标和明确 tooltip。
- 点击后继续失效现有项目 endpoint 快照、刷新侧边栏并重启代码分析。
- 刷新期间旧树保持可浏览，状态区域显示“等待项目索引完成”或“正在刷新 RabbitMQ 端点”。
- 成功结果按最新源码替换树；合法空结果显示“未发现 RabbitMQ 端点”。
- 失败结果显示“刷新失败，已保留上次结果”，不得替换旧树。
- 多次点击产生多个请求时，仅最后一次请求允许更新树或最终状态；较早回调直接丢弃。

## 5. Source Navigation

- endpoint 双击继续使用现有源码智能指针和导航入口。
- 普通浏览、搜索结果和类型筛选结果的导航行为必须一致。
- 模块折叠、刷新和状态恢复不得缓存或重写源码位置。
- 失效源码位置继续安全忽略，不跳转到错误位置。

## 6. Empty and Failure States

| Condition | Tree Handling | Visible Feedback |
|-----------|---------------|------------------|
| 项目没有 RabbitMQ endpoint | 展示 RabbitMQ 根，模块为空 | 未发现 RabbitMQ 端点 |
| 当前筛选无匹配 | 当前可见模块为空 | 当前条件无匹配结果 |
| 正在索引 | 保留旧树 | 等待项目索引完成 |
| 正在刷新 | 保留旧树 | 正在刷新 RabbitMQ 端点 |
| 刷新失败 | 保留旧树 | 刷新失败，已保留上次结果 |
| 项目关闭取消 | 不再更新 UI | 不产生误导性失败提示 |

## 7. Safety Boundaries

- endpoint 扫描在平台非阻塞 read action 和 smart mode 下执行。
- tree model、展开路径和状态文本只在 EDT 修改。
- 不使用自定义线程、锁、原子状态、第二套缓存或持久化。
- 不修改用户项目，不连接或查询 broker。
- 不修改 `D:\github\interfacex`，只参考其可见交互。

## 8. Requirement Mapping

| Interaction Area | Requirements |
|------------------|--------------|
| Tree structure and module toggles | FR-001–FR-005 |
| Refresh visibility and result handling | FR-006–FR-011 |
| Search/filter temporary expansion | FR-012–FR-013 |
| Existing navigation compatibility | FR-014 |
| Read-only and broker isolation | FR-015 |
