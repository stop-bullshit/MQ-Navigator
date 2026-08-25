# Implementation Plan: 修复侧边栏源码导航线程异常

**Branch**: `master` | **Date**: 2026-08-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/006-fix-sidebar-read-access/spec.md`

## Summary

IDEA 2026.2 日志确认侧边栏双击导航把 PSI 元素带出 read action 后直接执行导航，导致 EDT 上发生 read-access 异常。最小修复是在现有 read action 内校验智能指针并生成脱离 PSI 的平台导航描述，在 read action 结束后于当前 EDT 执行描述导航；仅修改现有侧边栏导航方法并扩展现有 UI 测试。

## Technical Context

**Language/Version**: Java 17  
**Primary Dependencies**: IntelliJ Platform 2023.3 Java plugin APIs; IDEA 2026.2.1 compatibility target  
**Storage**: N/A; 继续使用现有不可变 endpoint 快照和智能源码位置  
**Testing**: JUnit 4, IntelliJ Platform `BasePlatformTestCase`, Gradle test, Plugin Verifier  
**Target Platform**: IntelliJ IDEA 2023.3 through 2026.2.1 desktop IDE  
**Project Type**: IntelliJ Platform desktop plugin  
**Performance Goals**: 有效双击导航严格小于 1 秒；不增加后台扫描或等待  
**Constraints**: PSI 只在 read action 内读取；EDT 不执行项目搜索；不保存裸 PSI；用户项目只读；broker 零连接  
**Scale/Scope**: 一个生产方法、两个既有 UI 测试文件、至少 100 次有效导航和 40 次失效场景验证

## Constitution Check

*GATE: Passed before Phase 0 and re-checked after Phase 1.*

- **最小范围与直接实现**: 仅修改 `MqTopologyPanel.openSelectedSource`，不新增生产类、接口、方法层或通用导航封装。
- **平台优先**: 使用平台智能指针、read action 和平台导航描述；不创建自定义导航对象、线程池或弹窗。
- **PSI、索引与并发安全**: 智能指针解析、有效性和导航元素读取全部留在 read action；read action 外只保留不依赖 PSI 的导航描述。
- **用户项目只读与运行时隔离**: 变更只读取源码位置并打开编辑器，不写用户文件、不调用 broker。
- **测试先行与量化证据**: IDEA 2026.2 `idea.log` 已提供失败调用栈；先扩展重复导航和失效场景测试，再修改生产代码；执行 full test 和双版本 verifier。
- **可追踪交付**: spec、plan、tasks 和 verification 使用 FR/SC 与任务 ID 对应。

Post-design re-check: 全部原则继续满足，无 Complexity Tracking 项。

## Project Structure

### Documentation (this feature)

```text
specs/006-fix-sidebar-read-access/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── sidebar-source-navigation.md
├── checklists/
│   └── requirements.md
├── tasks.md
└── verification.md
```

### Source Code (repository root)

```text
src/main/java/cn/luckyh/mqx/ui/
└── MqTopologyPanel.java

src/test/java/cn/luckyh/mqx/ui/
├── MqTopologyPanelTest.java
└── MqSpecialQueueTopologyPanelTest.java
```

**Structure Decision**: 延续现有单插件结构。生产修复保留在拥有树双击事件和源码导航职责的 `MqTopologyPanel`；测试复用现有普通节点与特殊关系节点平台测试，不新增测试基类或测试工具类。

## Change Boundary

- **Production modification**: `src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java` only.
- **Test modification**: `MqTopologyPanelTest.java`, `MqSpecialQueueTopologyPanelTest.java` only.
- **New production classes/interfaces/abstractions**: none.
- **Protected dirty work**: feature 002–005 specifications and all existing sidebar, refresh, cache and special-queue changes remain intact.
- **Excluded alternatives**: navigating a PSI element after read action, performing UI navigation while holding read access, adding retries, adding a navigation service wrapper, or changing gutter navigation.

## Verification Strategy

1. Record the latest IDEA log stack and target source hash before implementation.
2. Extend existing UI tests to repeat valid navigation across ordinary and special relation nodes and assert stale/non-source nodes are harmless.
3. Apply the single-method production fix.
4. Run focused UI tests, all navigation/sidebar tests, `clean test`, structure checks and Plugin Verifier.
5. Re-scan source for PSI navigation outside read access and confirm `git diff --check`, user-project writes and broker access remain zero.

## Complexity Tracking

No Constitution violations or complexity exceptions.
