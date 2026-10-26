# Quickstart: 验证侧边栏源码导航线程修复

## Prerequisites

- Windows PowerShell
- Repository root: `<workspace>`
- Gradle Wrapper bundled with the repository
- Existing dirty work from features 002–005 preserved

## 1. Focused UI Tests

```powershell
.\gradlew.bat test --tests "cn.luckyh.mqx.ui.MqTopologyPanelTest" --tests "cn.luckyh.mqx.ui.MqSpecialQueueTopologyPanelTest" --rerun-tasks --no-parallel
```

Expected:

- Ordinary and special relationship nodes open the expected source file.
- Repeated valid navigation completes without failures.
- Invalid and non-source nodes do not navigate or throw.

## 2. Navigation and Full Regression

```powershell
.\gradlew.bat test --tests "cn.luckyh.mqx.navigation.*Test" --tests "cn.luckyh.mqx.ui.*Test" --rerun-tasks --no-parallel
.\gradlew.bat clean test --no-parallel
```

Expected: all tests pass with no behavior regression.

## 3. Plugin Compatibility

```powershell
.\gradlew.bat verifyPluginProjectConfiguration verifyPluginStructure --no-parallel
.\gradlew.bat verifyPlugin --no-parallel
```

Expected: IDEA 2023.3 and IDEA 2026.2.1 are compatible; the fix adds no deprecated API usage.

## 4. Manual Latest-IDE Smoke Test

1. Install the newly built plugin in IDEA 2026.2.1.
2. Open a project containing ordinary RabbitMQ endpoints and dead-letter relationships.
3. Double-click queue, exchange, binding and target queue nodes repeatedly.
4. Confirm the correct source opens and the IDE log has no new `Read access is allowed from inside read-action only` entry from `MqTopologyPanel`.

## 5. Safety Check

```powershell
git diff --check
git status --short
```

Expected: no whitespace error, no user-project file change and no unrelated dirty work removed.
