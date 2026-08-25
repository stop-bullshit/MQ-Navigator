# Research: MQ 端点持久缓存

## Decision 1: 复用现有运行时快照，只增加首次持久种子

**Decision**: `MqNavigationService` 的 `CachedValue<EndpointSnapshot>` 继续作为唯一运行时快照；持久状态仅在每个服务实例首次 provider 计算时尝试一次。

**Rationale**: 当前服务已经正确处理 PSI、项目根和手动刷新失效。把持久结果重新发布为同一快照可以保留所有现有导航和失效规则，避免第二套缓存查询链。

**Alternatives considered**: 独立缓存服务会形成双状态源；UI 层缓存无法服务导航；每次读取持久状态会压过会话内源码变化。

## Decision 2: 使用平台项目级系统缓存存储

**Decision**: 使用 IntelliJ Platform `PersistentStateComponent` 与项目级 `$CACHE_FILE$` 存储。

**Rationale**: 该位置由平台映射到 IDE system path，按项目服务隔离，不写用户项目；无需新增依赖、目录管理、原子文件替换或清理机制。

**Alternatives considered**: InterfaceX 的 SQLite 需要驱动、schema 和 DAO；自定义 YAML/JSON 重复平台能力；`.idea` 文件违反用户项目只读边界。

## Decision 3: 扁平状态 bean 与版本字段

**Decision**: 在现有服务内定义 `CacheState` 和 `CachedEndpointState` 两个嵌套可变 bean；扁平保存 endpoint、destination、源码 URL 和偏移，并设置格式版本 `1`。

**Rationale**: 平台 XML 序列化需要可变状态对象。扁平字段避免引入持久 DTO 文件、映射框架和自定义序列化器；版本字段允许未来明确拒绝不兼容状态。

**Alternatives considered**: 直接持久化 `MqEndpoint` 会携带运行时智能指针；Java 原生序列化格式脆弱；无版本字段会静默误读旧状态。

## Decision 4: 源码 URL 与偏移恢复智能指针

**Decision**: 长期状态只保存源码 `VirtualFile` URL 和文本偏移；恢复时在 smart read action 内查找当前 `PsiFile`/`PsiElement`，验证属于当前项目源码后创建新的 `SmartPsiElementPointer`。

**Rationale**: 这保留缓存导航能力，同时不长期保存裸 PSI。当前 parser 的 endpoint identity 已基于文件 URL 与偏移，恢复数据与现有模型一致。

**Alternatives considered**: 类名和方法名全局搜索会受重载、匿名类和调用表达式影响；自定义智能指针复制平台生命周期；允许空指针会扩大运行时模型改动。

## Decision 5: 整批恢复、完整扫描后才覆盖

**Decision**: 缓存条目必须全部恢复；数量不一致、字段损坏、版本不匹配或任一源码位置无效都回退完整扫描。只有所有 parser 均正常完成的扫描才替换持久状态；合法空结果可替换。

**Rationale**: 部分旧结果会安静地误导导航，比重新扫描更危险。保留最后完整缓存可以避免一次局部解析故障永久污染后续启动。

**Alternatives considered**: 跳过条目会展示不完整结果；合并旧缓存与部分新结果会混合时间点；空结果视为缺失会复活旧端点。

## Decision 6: 手动刷新与会话失效直接扫描

**Decision**: `requestRefresh()` 禁止当前服务再次恢复持久状态并递增现有 tracker；PSI 或项目根失效后，由于首次恢复机会已经消耗，provider 直接执行扫描。

**Rationale**: 复用现有入口与依赖即可实现明确失效，不需要监听器、时间戳轮询、定时器或新事件总线。

**Alternatives considered**: TTL 不能识别真实源码变化；文件监听器重复 modification tracker；工具窗口打开后总后台刷新会改变现有刷新行为。

## InterfaceX reference findings

- InterfaceX 在无显式刷新时先调用 `ScanResultCacheLoader.loadFromCache()`；缓存缺失时扫描，手动刷新时直接后台扫描。
- 完整扫描由 `ScanResultPersister.persist()` 保存，缓存条目携带类名、方法名和签名以恢复导航。
- 本功能保留其“缓存优先、刷新更新、缓存导航”的行为，但不复制 SQLite、全局用户目录、`Task.Backgroundable`、原子扫描标记或树数据源 UI。
