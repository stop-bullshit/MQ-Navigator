# Verification: 修复侧边栏源码导航线程异常

**Date**: 2026-08-25  
**Branch**: `master`  
**Feature**: `specs/006-fix-sidebar-read-access`

## Status

- Current phase: complete
- Verified: failure stack, root cause, focused UI tests, full regression, structure checks, IDEA 2023.3/2026.2.1 compatibility, deprecated-API delta and source safety
- Unverified: installing the newly built ZIP into the user's currently running IDEA 2026.2 instance and repeating the manual double-click smoke test
- Environment blocked: none

## Failure Evidence

- Log: `<IDE log>/idea.log`
- Time: 2026-08-25 10:43:48
- Thread: `AWT-EventQueue-0`
- Platform path: `LeafPsiElement.navigate` → `getContainingFile` without read access.
- Plugin frame: `cn.luckyh.mqx.ui.MqTopologyPanel.openSelectedSource(MqTopologyPanel.java:585)`.
- Root cause: the old read action returned a PSI-backed `Navigatable`; `navigate(true)` then ran on EDT and re-entered PSI outside read access.

## Workspace Baseline

- Branch: `master`.
- Initial `MqTopologyPanel.java` SHA-256: `2A95B744ED27F89FDD61A0D3C9480F75F5DCA8E16806B43E93F9F6562D46C0EB`.
- Final `MqTopologyPanel.java` SHA-256: `AD0AFECB81485AC891A3FDD44078AFA985AC82A679C0A28B1BDE69272F423F48`.
- Worktree already contained uncommitted feature 002–005 specifications and sidebar/refresh/cache/special-queue code before feature 006. Those changes remain present.
- Feature 006 production scope: `src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java` only.
- Feature 006 test scope: `MqTopologyPanelTest.java` and `MqSpecialQueueTopologyPanelTest.java` only.

Setup evidence:

- Gradle Wrapper 9.7.0; Java 17 toolchain and JVM.
- Existing runtime dependencies unchanged; no MQ client dependency added.
- `.gitignore` already covers Gradle/build/IDE output; no ignore change made.
- `.specify/extensions.yml` is absent, so no extension hook ran.
- No new production class, interface, method layer, thread, lock, cache, retry, notification or dependency.

## Analysis Evidence

- Required spec, plan, research, data model, UI behavior, quickstart and tasks artifacts are present.
- Specification checklist: 16/16 complete.
- Tasks: 16, IDs T001–T016 contiguous and format-valid.
- FR-001–FR-010 and SC-001–SC-006 task coverage: 100%.
- Initial analysis found no CRITICAL issue. Two task-specificity gaps were remediated before implementation: special-node timing assertions and 10 project-closed events.

## Test-first Evidence

- Existing IDEA 2023.3 platform tests passed before production changes: `BUILD SUCCESSFUL in 27s`.
- The same old code failed in IDEA 2026.2 at runtime with the logged `MqTopologyPanel.openSelectedSource` read-access stack, providing the version-specific red reproduction.
- Invalid-source tests were written before completing the safe-degradation branch; stale pointers, non-navigable targets, non-source nodes and disposed projects are now isolated.

## Implementation Evidence

- The mouse event still resolves the exact row from double-click coordinates.
- PSI pointer resolution, validity checks, navigation-element access and descriptor creation run in a platform non-blocking read action.
- Only a stable platform navigation descriptor crosses into the UI callback; no raw PSI element is retained.
- Project disposal, null/invalid pointer, invalid navigation element, unavailable descriptor and invalid-PSI exceptions stop locally.
- Final navigation runs on the UI thread after read access has ended.
- The former `ReadAction.compute` use in `MqTopologyPanel.openSelectedSource` was removed.

## Focused Results

Command:

```powershell
.\gradlew.bat test --tests "cn.luckyh.mqx.ui.MqTopologyPanelTest" --tests "cn.luckyh.mqx.ui.MqSpecialQueueTopologyPanelTest" --rerun-tasks --no-parallel
```

Final result: `BUILD SUCCESSFUL in 30s`; 22 tests, 0 failures/errors.

Automated navigation gates:

- Ordinary endpoint: 20 successful double-clicks.
- Special queue, dead-letter exchange, binding and target relation: 20 successful double-clicks each.
- Total repeated valid navigation samples: 100; every sample asserts completion strictly below 1 second.
- Deleted/invalid source positions: 10 samples, no wrong file opened.
- Non-navigable target: 10 samples, no wrong file opened.
- Module/non-source node: 10 samples, no navigation.
- Disposed project: 10 double-click events, no exception.
- Search and unresolved-filter navigation paths remain covered by existing tests.

## Regression and Compatibility

- Navigation/UI regression command: 66 tests across 7 suites, 0 failures/errors, `BUILD SUCCESSFUL in 1m 17s`.
- Final `clean test --no-parallel`: 95 tests across 12 suites, 0 failures/errors, `BUILD SUCCESSFUL in 1m 15s`.
- `verifyPluginProjectConfiguration verifyPluginStructure --no-parallel`: `BUILD SUCCESSFUL`.
- `verifyPlugin --no-parallel`: `BUILD SUCCESSFUL in 4m 5s`.
- IC-233.11799.241: Compatible.
- IU-262.9437.185: Compatible.
- Deprecated API usages reported by the latest verifier decreased from 3 to 2. The sidebar navigation usage is gone; the remaining line-marker and daemon-restart usages predate feature 006 and are outside its scope.

## Safety Review

- `MqTopologyPanel.openSelectedSource` contains no synchronous `ReadAction.compute` and calls `navigate(true)` only on the stable descriptor in the UI callback.
- The new asynchronous work uses the existing IntelliJ platform executor; no custom thread or executor was introduced.
- Source scan found no lock, runtime broker API, network API or user-project write path in the changed production method.
- `git diff --check` reported no whitespace error; only existing LF-to-CRLF notices appeared.
- Feature 005 still has all 27 tasks checked and its dirty work remains present in `git status`.

## Requirements Trace

| Requirements | Evidence | Status |
|-------------|----------|--------|
| FR-001–FR-002 | Ordinary and special relationship repeated navigation tests | Verified |
| FR-003 | Non-blocking read creates a stable descriptor; latest verifier no longer reports this method's old read API | Verified |
| FR-004–FR-005 | Deleted, invalid, non-navigable and disposed-project tests | Verified |
| FR-006 | Existing coordinate-based double-click tests retained | Verified |
| FR-007 | Every valid navigation sample asserts under 1 second | Verified |
| FR-008 | 95-test full regression | Verified |
| FR-009 | Source/dependency scan, broker isolation in full suite and worktree check | Verified |
| FR-010 | One production method changed; gutter navigation unchanged | Verified |
| SC-001 | Five target categories × 20 valid double-clicks, 100% correct | Verified |
| SC-002 | Four invalid/no-source/lifecycle categories × 10 events, no wrong navigation | Verified |
| SC-003 | Per-navigation strict 1-second assertions | Verified |
| SC-004 | Navigation/UI and full regressions unchanged | Verified |
| SC-005 | Both IDE targets compatible; feature deprecated-API delta is -1 | Verified |
| SC-006 | No user-project write and no broker connection attempt | Verified |

All 16 tasks are complete. The updated plugin ZIP is available under `build/distributions/` for installation or reload in IDEA 2026.2.
