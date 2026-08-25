# Research: 侧边栏源码导航线程异常

## Decision 1: 以完整 IDE 日志确定根因

**Decision**: 以 IDEA 2026.2 `idea.log` 中 `MqTopologyPanel.openSelectedSource` 的完整调用栈作为失败证据。

**Rationale**: 调用栈明确显示 `LeafPsiElement.navigate` 在 EDT 且 read action 外访问 containing file，定位到 `MqTopologyPanel.java:585`，无需猜测刷新、缓存或特殊队列关系逻辑。

**Alternatives considered**:

- 仅依据用户截取的异常头部排查所有 PSI 入口：范围过大且容易误改无关代码。
- 捕获异常并忽略：只隐藏症状，仍违反平台线程规则。

## Decision 2: read action 内生成稳定导航描述

**Decision**: 在 read action 内解析智能指针、获取导航元素并由平台生成不依赖 PSI 的导航描述；read action 结束后执行导航。

**Rationale**: 导航描述携带打开文件所需的稳定位置，不会在 EDT 导航阶段再次调用裸 PSI；同时避免在 read action 内执行编辑器 UI 导航。

**Alternatives considered**:

- read action 外直接调用 PSI 元素的 `navigate`：已由日志证明失败。
- 在 read action 内直接执行 `navigate`：虽然可避免当前异常，但会让 UI 导航持有 read access，扩大锁持有范围。
- 新增通用导航服务：当前只有一个已确认入口，不符合最小修改原则。

## Decision 3: 失效目标继续静默停止

**Decision**: 项目、智能指针、导航元素或描述失效时立即返回，不新增提示。

**Rationale**: 与现有侧边栏行为一致，不改变用户体验，也不会错误打开近似源码。

**Alternatives considered**:

- 新增通知：超出修复范围，且快速源码变化可能造成噪声。
- 自动刷新后重试：引入异步顺序和重复导航风险。

## Decision 4: 复用现有平台 UI 测试

**Decision**: 在 `MqTopologyPanelTest` 和 `MqSpecialQueueTopologyPanelTest` 中扩展重复双击与失效节点验证，并以最新 IDE Plugin Verifier 作为跨版本门禁。

**Rationale**: 现有测试已能真实派发树双击并验证打开文件；无需测试专用注入点。IDEA 2026.2 日志承担严格线程模型的失败证据，Verifier 负责 API 兼容性。

**Alternatives considered**:

- 为测试增加导航回调注入：会给生产构造器增加只为测试服务的扩展点。
- 新增独立 UI 自动化框架：成本远高于单点修复。
