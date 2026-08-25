# Verification: MQ 端点持久缓存

## Status Legend

- **verified**: 已由本次命令、测试报告、哈希或差异直接证明。
- **unverified**: 当前环境无法自动审计，未将计划或推断写成完成事实。
- **environment blocked**: 已执行但被外部环境阻断。

## 1. Pre-change Baseline

**Status**: verified

- Date: 2026-08-24
- Git branch: `master`
- Feature directory: `specs/004-persistent-endpoint-cache/`
- Existing feature 002/003 worktree changes were present before feature 004 and are protected from modification.
- Feature 004 production scope: `src/main/java/cn/luckyh/mqx/navigation/MqNavigationService.java` only.
- Feature 004 test scope: new `src/test/java/cn/luckyh/mqx/navigation/MqNavigationCacheTest.java` only.

### Protected Baseline SHA-256

| File | SHA-256 |
|------|---------|
| `src/main/java/cn/luckyh/mqx/navigation/MqNavigationService.java` | `D6F05AA0088A69F2CA5D9C1CB815C38D2490302B75E8564543A3B958391DF033` |
| `src/main/java/cn/luckyh/mqx/action/RefreshMqIndexAction.java` | `E17DF47C7E65190F0C9233F24EC0FAF6FA592AD0EDFDAFDF177269D4989896B6` |
| `src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java` | `C9C7AEF3994DF91E7A9CE62D1F0F5538BE93A5402CDE9FF291ACAD3E7233E701` |
| `src/test/java/cn/luckyh/mqx/navigation/MqNavigationPerformanceTest.java` | `F9BEFA48501BD5E1E222A835010E8C007EF7A192F1F74FBC7AECB8000C6B309D` |

### Project Setup

**Status**: verified

- Existing `.gitignore` covers Gradle output, IntelliJ Platform output and IDE metadata; no ignore-file change is required.
- No Docker, Node, ESLint, Prettier, Terraform or Helm setup is present.
- `build.gradle.kts` uses Java 17, IntelliJ Platform Gradle Plugin 2.18.1, IDEA Community 2023.3 and JUnit 4.13.2.
- No production dependency change is planned; SQLite, MQ clients and custom persistence libraries remain prohibited.

## 2. Existing Navigation Baseline

**Status**: verified

Command:

```powershell
.\gradlew.bat test --tests "cn.luckyh.mqx.navigation.MqNavigationServiceTest" --rerun-tasks --no-parallel
```

Result: `BUILD SUCCESSFUL` in 17 seconds. The build emitted only pre-existing feature 003 `JBProgressBar` removal warnings from `MqTopologyPanel` and its tests.

2026-08-25 follow-up: the progress component was replaced with `JProgressBar`; focused compilation and Plugin Verifier no longer report this planned-removal API.

### Existing Service Chain

**Status**: verified

- `currentEndpoints()` and `findTargets()` read one immutable `CachedValue<EndpointSnapshot>`.
- PSI modification count, project-root modification tracker and manual refresh tracker invalidate that snapshot.
- `requestRefresh()` only increments the existing manual tracker.
- Each parser is isolated from invalid PSI and illegal-argument failures; successful results are de-duplicated by endpoint id and stably sorted.
- PSI access remains owned by existing smart read actions in callers; no custom thread, listener, lock or second runtime cache is present.

## 3. Focused Red/Green

### US1 Red

**Status**: verified

`MqNavigationCacheTest` initially failed during test compilation with 18 missing-symbol errors for `CacheState`, `CachedEndpointState`, `getState()` and `loadState()`. This directly exposed the absent persistent-cache behavior before production changes.

### US1 Green

**Status**: verified

Command:

```powershell
.\gradlew.bat test --tests "cn.luckyh.mqx.navigation.MqNavigationCacheTest" --rerun-tasks --no-parallel
```

Result: `BUILD SUCCESSFUL`; XML reports 5 tests, 0 failures, 0 errors and 0 skipped.

### US2 Red

**Status**: verified

After adding refresh lifecycle tests, 10 tests ran and only `testIncompleteScanKeepsLastCompletePersistentState` failed. The failing assertion proved that a parser failure incorrectly replaced the last complete persistent state with a partial empty result.

### US2 Green

**Status**: verified

After gating persistent-state replacement on all parser collections completing, `MqNavigationCacheTest` reported 10 tests, 0 failures, 0 errors and 0 skipped.

### US3 Red

**Status**: verified

- The first US3 run exposed a test-fixture editor/listener cleanup issue; the fixture was replaced with a real second project and index-free static test parser before assessing production behavior.
- With the fixture corrected, a corrupted `bindingKeys` list containing a null element produced an unhandled `NullPointerException`, directly exposing the remaining production validation gap.

### US3 Green

**Status**: verified

After validating binding-key elements before model construction, `MqNavigationCacheTest` reported 13 tests, 0 failures, 0 errors and 0 skipped.

## 4. Field Fidelity and Restore Performance

**Status**: verified

- Valid non-empty cache restored instead of the conflicting source scan result.
- Valid empty cache restored as empty while a source endpoint existed.
- Missing cache scanned current source and created version 1 state.
- Restored endpoint list rejected mutation and retained stable sorting.
- 45 cached samples covered RabbitMQ producer, consumer and three topology roles plus Kafka/RocketMQ producer and consumer, five samples per role; all endpoint, destination, display and source-pointer fields matched.
- Performance: `MQ_CACHE_RESTORE endpoints=2000 warmups=5 n=20 p50=3239100ns p95=5399700ns max=13024500ns`; p95 was 5.40ms against the 500ms limit.

## 5. Refresh and Session Invalidation

**Status**: verified

- Manual refresh bypassed a restored stale cache and persisted current source output in 5 consecutive runs.
- Legal empty refresh replaced stale non-empty state and the next service restored empty in 5 consecutive runs.
- Five successive PSI source changes invalidated the runtime snapshot, rescanned and became the next service's restored result.
- A project content-root change invalidated the restored snapshot and forced a current-source scan.
- A forced parser collection failure returned the existing isolated runtime result but preserved the last complete persistent state.

## 6. Corruption Fallback and Project Isolation

**Status**: verified

- Unsupported version, missing required field, unknown enum, deleted source file, out-of-range offset, partially valid state, duplicate endpoint id and null binding-key element each fell back as a whole in 5 consecutive runs.
- An existing source file outside the current project's source content was rejected in 5 consecutive runs.
- Fallback results contained only the current source scan and repaired the persistent state; no partial cached endpoint or unhandled exception remained.
- Two simultaneously live projects each completed 10 own-cache restores and manual refreshes; each also rejected the other project's state and returned only its own static source endpoint. Cross-project leakage count was 0.
- Final focused performance sample: `MQ_CACHE_RESTORE endpoints=2000 warmups=5 n=20 p50=3230300ns p95=4714200ns max=5583200ns`.

## 7. Regression and Compatibility

### Focused Regression

**Status**: verified

- `MqNavigationServiceTest`: 16 tests, 0 failures/errors/skips.
- `MqTopologyPanelTest`: 14 tests, 0 failures/errors/skips.
- Existing search, match, navigation, module grouping, refresh progress and latest-result tests remained green.

### Heavy Performance

**Status**: verified

- `MqNavigationPerformanceTest`: 2 tests, 0 failures/errors/skips.
- 50 modules / 2,000 endpoints / 40 sidebar refreshes: p95 `299,946,100ns` (300ms), below 3 seconds.
- 50 modules / 2,000 endpoints / 40 service refreshes: p95 `147,649,600ns` (148ms), below 3 seconds.
- 200 navigation samples: p95 `1,168,800ns` (1.17ms), below 1 second.

### Complete Test

**Status**: verified

Command: `.\gradlew.bat clean test --no-parallel`

Result: `BUILD SUCCESSFUL` in 1m 7s; 11 suites, 75 tests, 0 failures, 0 errors, 0 skipped. Final cache suite: 14 tests; 2,000 endpoint restore p95 `3,752,700ns` (3.75ms).

### Plugin Compatibility

**Status**: verified

- `verifyPluginProjectConfiguration`: passed.
- `verifyPluginStructure`: passed.
- Plugin Verifier against IC-233.11799.241: compatible.
- Plugin Verifier against IU-262.9437.185: compatible.
- 2026-08-25 final rerun reports IDEA 2023.3 fully compatible without deprecated APIs; IDEA 2026.2.1 remains compatible with 3 unrelated existing deprecated method usages and no `JBProgressBar` reference.

## 8. Broker and Source Safety

### Broker and Dependency Isolation

**Status**: verified

- `MqBrokerIsolationTest`: 1 test, 0 failures/errors/skips.
- `runtimeClasspath` contains only `org.yaml:snakeyaml:2.6`; no MQ client, SQLite or database driver was added.
- Production source scan found no network, socket, HTTP client or SQLite implementation. RocketMQ hits were existing parser class-name strings only.

### Sandbox Hash

**Status**: verified

- Copied 21 files from `src/test/testData/manual-project` to `build/feature004-sandbox-final`.
- Ran the manual-refresh cache test between SHA-256 snapshots.
- Result: `files=21 changed=0 added=0`.
- Protected feature 002/003 hashes for `RefreshMqIndexAction`, `MqTopologyPanel` and `MqNavigationPerformanceTest` remained identical to the pre-change baseline.

### GUI Operation

**Status**: unverified

No automated tool-window session physically opened the copied sandbox and clicked refresh. Automated panel, cache lifecycle and source-hash evidence passed; no manual GUI result is inferred.

### Production Environment

**Status**: unverified

No production IDE project or live broker was used. This is intentionally separate from automated plugin tests and compatibility verification.

## 9. FR/SC Traceability

| Scope | Requirements | Evidence | Status |
|-------|--------------|----------|--------|
| Valid and empty restore | FR-001, FR-004, FR-005, FR-008 | Cache hit, missing cache, legal empty, immutable/sorted snapshot tests | verified |
| Field and source fidelity | FR-006 | 45 multi-system/role samples plus platform XML round-trip | verified |
| Whole-cache validation | FR-007, FR-013, FR-017 | Eight invalid categories and external source, each repeated 5 times | verified |
| Refresh and invalidation | FR-009–FR-012, FR-014 | Manual, empty, PSI, project-root and incomplete-parser tests | verified |
| Isolation and read-only behavior | FR-002, FR-003, FR-015, FR-016 | Two projects x10, broker test, dependency scan, sandbox hashes and regressions | verified |
| Cache restore performance | SC-001 | 5 warmups + 20 samples, 2,000 endpoints, final p95 3.75ms | verified |
| Field fidelity | SC-002 | 45 samples, 100% compared fields and source pointers | verified |
| Latest refresh persistence | SC-003 | Successful, empty and PSI changes repeated at least 5 times | verified |
| Invalid fallback | SC-004 | Required invalid categories repeated 5 times, zero partial results/exceptions | verified |
| Existing performance | SC-005 | Sidebar p95 300ms and service refresh p95 148ms | verified |
| Source and broker safety | SC-006 | 21-file hash delta 0; broker attempt test green | verified |
| Project isolation | SC-007 | Two live projects, 10 restore/refresh cycles each, leakage 0 | verified |

## 10. Final Scope Review

**Status**: verified

- Production changed only `src/main/java/cn/luckyh/mqx/navigation/MqNavigationService.java`.
- Added only `src/test/java/cn/luckyh/mqx/navigation/MqNavigationCacheTest.java` for executable coverage.
- No dependency, parser, matcher, action, UI, runtime model, thread, timer, lock, listener, database, broker connection or project-file persistence was added.
- Persistent state seeds the existing immutable `CachedValue` once per service instance; it is not a second runtime snapshot.
- Source restoration is a named external-resource step with early returns; no unnecessary interface, top-level class or forwarding layer was introduced.
- `git diff --check` passed; only repository line-ending conversion warnings were printed.
