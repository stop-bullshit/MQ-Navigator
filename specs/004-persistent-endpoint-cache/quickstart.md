# Quickstart Validation Guide: MQ 端点持久缓存

## 1. Prerequisites

- Windows PowerShell
- JDK 17
- Working directory: `<workspace>`
- Current feature: `specs/004-persistent-endpoint-cache`

```powershell
Set-Location '<workspace>'
java -version
Get-Content '.specify\feature.json'
git status --short
```

Preserve all pre-existing feature 002 and 003 worktree changes.

## 2. Test-First Cache Validation

Before production code changes, add cache tests and run:

```powershell
.\gradlew.bat test --tests "cn.luckyh.mqx.navigation.MqNavigationCacheTest" --rerun-tasks --no-parallel
```

Expected red: a new service instance cannot restore the supplied persistent endpoint state and instead returns the current source scan.

After implementation, the same command must prove valid and valid-empty restore, field and source fidelity, manual refresh replacement, corrupt-cache fallback, project isolation, and 5 warmups plus 20 restores of 2,000 endpoints with p95 at most 500ms.

## 3. Existing Behavior Regression

```powershell
.\gradlew.bat test --tests "cn.luckyh.mqx.navigation.MqNavigationServiceTest" --rerun-tasks --no-parallel
.\gradlew.bat test --tests "cn.luckyh.mqx.ui.MqTopologyPanelTest" --rerun-tasks --no-parallel
.\gradlew.bat test --tests "cn.luckyh.mqx.navigation.MqNavigationPerformanceTest" --rerun-tasks --no-parallel
.\gradlew.bat clean test --no-parallel
```

Expected: existing navigation and sidebar behavior remains unchanged; 50 modules, 2,000 endpoints and 40 visible refresh samples keep nearest-rank p95 strictly below 3 seconds; all tests pass without modifying the heavy fixture or thresholds.

## 4. Structure and Compatibility

```powershell
.\gradlew.bat verifyPluginProjectConfiguration verifyPluginStructure
.\gradlew.bat verifyPlugin
```

Record IDEA 2023.3 and 2026.2.1 separately. Existing deprecation warnings must not be reported as new cache compatibility errors.

## 5. Broker and Dependency Isolation

```powershell
.\gradlew.bat test --tests "*MqBrokerIsolationTest" --rerun-tasks --no-parallel
.\gradlew.bat dependencies --configuration runtimeClasspath
rg -n "java\.net|Socket|URLConnection|HttpClient|com\.rabbitmq|org\.apache\.kafka|org\.apache\.rocketmq|sqlite" src/main/java
```

Expected: broker connection attempts remain 0; no MQ client or database driver is added; broad class-name strings used by parsers are distinguished from direct network calls.

## 6. Sandbox Source Safety

Create a one-time copy of `src/test/testData/manual-project` under `build/`. Before and after opening MQ Navigator, loading cache and pressing refresh, hash all files except IDE/build output.

Expected: protected source-file SHA-256 differences are 0; no cache file appears under the copied project or `.idea`; unauditable GUI steps are marked unverified rather than inferred from automation.

## 7. Result Recording

Write implementation evidence to `specs/004-persistent-endpoint-cache/verification.md` with separate sections for pre-change hashes, focused red/green, field fidelity, corruption fallback, project isolation, 2,000 endpoint timing, regression, broker/source safety, Plugin Verifier, sandbox and FR/SC traceability.

Use **verified**, **unverified**, or **environment blocked** for every gate.
