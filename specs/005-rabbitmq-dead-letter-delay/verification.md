# Verification: RabbitMQ 死信与延迟队列拓扑

**Date**: 2026-08-25  
**Branch**: `master`  
**Feature**: `specs/005-rabbitmq-dead-letter-delay`

## Status

- Current phase: complete
- Verified: specification, implementation, focused tests, full regression, performance, broker isolation, cache compatibility, plugin structure and IDEA compatibility
- Unverified: none within the automated acceptance scope
- Environment blocked: none

## Workspace Baseline

- Worktree was already dirty before feature 005. Existing feature 002–004 specifications, refresh/cache/sidebar production changes, performance tests and UI/cache tests were protected and not reverted.
- Allowed production files: `MqDestination.java`, `RabbitMqEndpointParser.java`, `MqNavigationService.java`, `MqTopologyPanel.java`.
- Allowed tests: `RabbitMqEndpointParserTest.java`, `MqNavigationCacheTest.java`, `MqSpecialQueueTopologyPanelTest.java`, Spring AMQP `QueueBuilder` test stub.

### Production SHA-256

| File | Initial | Final |
|------|---------|-------|
| `src/main/java/cn/luckyh/mqx/model/MqDestination.java` | `6366419F50453CA7680FCDD6E96516343BBBF1E20D24F55D9F1F2C9CF00E8E48` | `F7A54627378CDF54DA9350ED71D4F59242A938A2C0041E4A268A325EC1810BA4` |
| `src/main/java/cn/luckyh/mqx/parser/RabbitMqEndpointParser.java` | `CBF33B5B5C216BAE5C4FC3AF4586A4C3F9A0DB68DB5B0BC89B8455449BDAA71B` | `6DCA9C1EBB52B112D92739D0EF69EB29A946D0BF61902BC0D428BCFB2DBB9662` |
| `src/main/java/cn/luckyh/mqx/navigation/MqNavigationService.java` | `91317BE7D3F22FB59CA749380A6ADCD8FF4EFF7C18FAD9CCAC0E99A4D1AEA4EB` | `31F2602EFFCF5B6EF447ABF0068C1D42864AA20AF857C1E3746994468250C18F` |
| `src/main/java/cn/luckyh/mqx/ui/MqTopologyPanel.java` | `4F850CD9D9A8B169BBBD21464429644198D4BFA38CD7AD939870BAF942E4A69D` | `2A95B744ED27F89FDD61A0D3C9480F75F5DCA8E16806B43E93F9F6562D46C0EB` |

## Setup Evidence

- Gradle Wrapper: 9.7.0; Java toolchain: 17.
- IntelliJ Platform baseline: Community 2023.3, minimum build 233; verifier target also includes IDEA 2026.2.1.
- Runtime classpath remains `org.yaml:snakeyaml:2.6`; no RabbitMQ, Kafka or RocketMQ runtime client dependency was added.
- `.gitignore` already covers Gradle/build/IDE outputs. `.specify/extensions.yml` is absent, so no extension hooks ran.

## Baseline and Test-first Evidence

Baseline command:

```powershell
.\gradlew.bat test --tests "cn.luckyh.mqx.parser.RabbitMqEndpointParserTest" --tests "cn.luckyh.mqx.navigation.MqNavigationCacheTest" --tests "cn.luckyh.mqx.ui.MqTopologyPanelTest" --rerun-tasks --no-parallel
```

Baseline result: `BUILD SUCCESSFUL in 41s` before feature 005 production changes.

After adding the special-queue tests and stub first, `compileTestJava` failed as expected because `MqDestination.RabbitQueueSettings` and the cache fields did not exist. Production code was then implemented through the existing model, parser, cache and topology panel.

## Focused Results

Final focused command:

```powershell
.\gradlew.bat test --tests "cn.luckyh.mqx.parser.RabbitMqEndpointParserTest" --tests "cn.luckyh.mqx.navigation.MqNavigationCacheTest" --tests "cn.luckyh.mqx.ui.MqSpecialQueueTopologyPanelTest" --rerun-tasks --no-parallel
```

Result: `BUILD SUCCESSFUL in 24s`, 35 tests, 0 failures/errors/skips.

| Suite | Tests | Verified behavior |
|------|------:|-------------------|
| `RabbitMqEndpointParserTest` | 13 | `withArgument`, local Map `put`, `Map.of`, constants, TTL=0, dynamic/invalid values, branch degradation, malformed declaration isolation and three queue types |
| `MqNavigationCacheTest` | 17 | cache v2 full-field round trip, v1/invalid whole-cache fallback, and 10 refresh plus 10 reopen cycles for each special queue type |
| `MqSpecialQueueTopologyPanelTest` | 5 | single/multiple targets, missing binding/queue, original route key, default exchange, cross-module navigation, one-level loop, tooltip and 20-queue search |

Automated sample gates:

- Static Map and single-argument declarations: 10 each.
- TTL-only, DLX-only and TTL+DLX classifications: 10 each.
- Dynamic exchange, dynamic route key, dynamic TTL, negative TTL, nonnumeric TTL and branch-modified Map: 5 each.
- Complete single target, complete multiple targets, missing binding, missing target queue and original route key: 5 each.
- TTL=0: 5 `Map.of` declarations; malformed special declarations: 5 isolated queues.
- Special queue refresh and persisted restore: all three types checked in 10 cycles each.

## Regression and Safety

- Existing topology/navigation/matcher/all-parser regression command passed: `BUILD SUCCESSFUL in 37s`.
- Final `clean test --no-parallel`: `BUILD SUCCESSFUL in 1m 17s`; 92 tests across 12 suites, 0 failures/errors.
- `MqBrokerIsolationTest`: 1 test, 0 failures; no broker connection was attempted.
- Source scan found only MQ framework class-name string literals used for PSI matching, not runtime broker/network API calls.
- `git diff --check` reported no whitespace error; only the repository's existing LF-to-CRLF notices appeared.
- Static review found no feature-specific dependency, top-level production class, interface, thread, lock, listener, recursive relationship node or user-project write path.

Performance evidence:

```text
MQ_SIDEBAR_PERF modules=50 endpoints=2000 refresh[n=40 p50=234322600 p95=336045800 max=400766000] firstSnapshot=1400128600 firstTree=462635700
MQ_PERF modules=50 endpoints=2000 nav[n=200 p50=805200 p95=1859100 max=2717800] refresh[n=40 p50=143643100 p95=167914000 max=169267400]
```

The user-visible refresh p95 is 0.336 seconds, strictly below the 3-second limit. The loop test confirms dead-letter targets are leaves and cannot grow recursively.

## Compatibility

- `verifyPluginProjectConfiguration verifyPluginStructure --no-parallel`: `BUILD SUCCESSFUL`.
- `verifyPlugin --no-parallel`: `BUILD SUCCESSFUL in 4m 21s`.
- IC-233.11799.241: Compatible.
- IU-262.9437.185: Compatible.
- The verifier still reports three pre-existing deprecated IntelliJ API usages in sidebar navigation, line-marker presentation and refresh daemon restart; none is introduced by feature 005 and none is a compatibility error.

## Requirements Trace

| Requirements | Evidence | Status |
|-------------|----------|--------|
| FR-001–FR-006 | Queue settings model and parser static/dynamic/classification matrices | Verified |
| FR-007–FR-013 | Summary, tooltip, search, module-preserving dead-letter exchange/binding/queue relation tests | Verified |
| FR-014–FR-016 | Refresh, cache v2/v1 fallback, invalid cache and malformed declaration isolation tests | Verified |
| FR-017 | One-level loop target test | Verified |
| FR-018 | Runtime dependency scan, network/broker API scan and broker isolation test | Verified |
| FR-019 | Existing RabbitMQ/Kafka/RocketMQ regression plus full 92-test suite | Verified |
| SC-001 | 10 Map + 10 single-argument static samples, all assertions passed | Verified |
| SC-002 | Five samples for each of five path states, no fabricated target assertion failure | Verified |
| SC-003 | 10 samples for each of three queue classifications | Verified |
| SC-004 | Five samples for each dynamic/invalid category; queue visibility and diagnostics asserted | Verified |
| SC-005 | 20 special queues; search and detail assertion completed within 30 seconds | Verified |
| SC-006 | Three special types, 10 refreshes and 10 persisted restores each | Verified |
| SC-007 | 2,000 endpoints, 40 refreshes, p95 0.336 seconds; loop stays one level | Verified |
| SC-008 | Full regression unchanged, no user source write, no RabbitMQ connection | Verified |

All 27 tasks are complete. Existing feature 002–004 dirty work remains present in `git status` and was not reverted or overwritten outside the four shared production files already declared in the baseline.
