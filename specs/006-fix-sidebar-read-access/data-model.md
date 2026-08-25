# Data Model: 侧边栏源码导航

本功能不新增持久数据，只约束一次双击导航中已有对象的生命周期。

## Entities

### Selected Tree Node

- **node type**: 模块、分类、诊断、端点或关系目标。
- **endpoint reference**: 仅端点和关系目标存在。
- **selection source**: 当前鼠标双击坐标，而不是历史选中项。

Validation:

- 空白位置、模块、分类和诊断节点没有源码目标，直接结束。
- 端点必须来自当前树模型。

### Smart Source Location

- **project**: 当前侧边栏所属项目。
- **element state**: 有效或失效。
- **navigation element**: 源码声明实际导航位置。

Validation:

- 项目已释放时不得解析。
- 元素和导航元素必须有效。
- 解析和有效性检查只能发生在允许读取源码模型的范围内。

### Stable Navigation Descriptor

- **target file/location**: 打开编辑器所需的稳定位置。
- **navigable state**: 可导航或不可导航。
- **PSI ownership**: read action 结束后不依赖裸 PSI。

Validation:

- 描述无法生成或不可导航时停止。
- 只在 UI 事件线程执行最终打开动作。

## State Transitions

```text
Double click
  -> resolve node by coordinates
  -> no endpoint: stop
  -> project disposed: stop
  -> resolve smart source under read access
  -> invalid/unavailable: stop
  -> build stable descriptor
  -> descriptor unavailable: stop
  -> open source on EDT
```

任何停止状态都不得打开其他文件、修改用户项目或触发重试。
