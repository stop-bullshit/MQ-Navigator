# 开源发布准备变更日志

## [CHANGE-更新: 支持IDEA2026.3安装-20261110-1221-01]

| 字段 | 内容 |
| --- | --- |
| 日期 | 2026-11-10 |
| 版本 | 0.2.14 |
| 触发需求 | 用户在IU-263.7307.200安装0.2.13时被262.*兼容上限拦截 |

**修改范围：**

- `build.gradle.kts`：安装上限设为 `263.*`，并将IDEA2026.3加入兼容性校验目标。
- `gradle.properties`：修复版本递增为 `0.2.14`。
- `README.md`、`CHANGELOG.md`：同步当前支持范围与修复说明。

**边界：** 保留IDEA2023.3/Java17编译基线、原插件ID和消息导航逻辑；历史版本兼容范围记录保持不变。

**验证：** 构建、项目配置和插件结构检查通过；安装包实际声明为 `since-build=233`、`until-build=263.*`、版本 `0.2.14`。Plugin Verifier 1.411离线校验 `IC-233.11799.241`（随附JBR17）结果为Compatible；校验本机 `IU-263.7307.200`（随附JBR25）结果为Compatible，保留ReadAction.compute和DaemonCodeAnalyzer.restart两条既有弃用API提示。未修改业务代码，未实机安装或重启用户IDE。

## [CHANGE-更新: 重设计MQ Navigator标志-20261110-1204-01]

| 字段 | 内容 |
| --- | --- |
| 日期 | 2026-11-10 |
| 版本 | 0.2.13 |
| 触发需求 | 插件Logo重新设计，并在插件列表、工具窗口和README中保持一致 |

**修改范围：**

- `META-INF/pluginIcon.svg`、`icons/mq-navigator-plugin-logo.svg`：使用N形消息导航路径和两个端点，四周保留透明留白。
- `icons/mq-navigator-toolwindow*.svg`：使用同一轮廓，提供16px/20px及浅色/深色单色版本。
- `icons/mq-navigator-logo-lockup.svg`：同步新标志与产品名称组合。
- `gradle.properties`、`CHANGELOG.md`：版本递增为 `0.2.13` 并记录图标调整。

**边界：** 不修改消息识别、导航逻辑或gutter方向图标；保留已有名称统一改动。Marketplace已提交的 `0.2.12` 不受本地资源修改影响。

**验证：** 使用IDE自身SVG渲染器核对40px/80px主图标与16px/20px浅深色工具窗口图标；7个SVG的尺寸、大小、纯矢量内容和品牌一致性检查通过。`verifyPluginProjectConfiguration verifyPluginStructure buildPlugin --offline` 通过，实际 `MQ Navigator-0.2.13.zip` 中7个图标资源与已检查源码一致。未实机重装；发布时从源码提交重新构建0.2.13，并核对实际上传文件的版本与图标。

## [CHANGE-更新: 统一MQ Navigator发布名称-20261110-1134-01]

| 字段 | 内容 |
| --- | --- |
| 日期 | 2026-11-10 |
| 版本 | 0.2.12 |
| 触发需求 | 插件构建产物与IDEA展示名称统一，并将GitHub仓库改为MQ-Navigator |

**修改范围：**

- `settings.gradle.kts`：根项目名使用 `MQ Navigator`，同步ZIP、包内目录和插件JAR名称。
- `gradle.properties`：发布版本递增为 `0.2.12`。
- `.github/workflows/release.yml`：GitHub附件使用 `MQ-Navigator-<version>.zip`，重命名后生成SHA-256并上传同名附件。
- `README.md`、`CONTRIBUTING.md`、`src/main/resources/META-INF/plugin.xml`：更新当前安装说明和仓库链接。
- `CHANGELOG.md`：记录0.2.12的名称调整和分发文件名。

**边界：** 插件ID `cn.luckyh.mqx` 和Java包名继续用于已有安装识别；保留旧版本产物及历史名称记录。

**仓库改名：** GitHub仓库已改为 `stop-bullshit/MQ-Navigator`，本地 `origin` 同步为 `https://github.com/stop-bullshit/MQ-Navigator.git`。

**验证：** `verifyPluginProjectConfiguration verifyPluginStructure buildPlugin` 通过。实际ZIP根目录为 `MQ Navigator/`，插件JAR为 `MQ Navigator/lib/MQ Navigator-0.2.12.jar`；包内名称、版本、插件ID与新仓库链接均已核对。GitHub上传脚本JavaScript语法检查通过。未运行单元测试、全IDE版本兼容性检查或实机重装；未提交、推送代码或提交Marketplace审核。

## [CHANGE-更新: 补齐开源资料与发布门禁-20261026-1743-01]

| 字段 | 内容 |
| --- | --- |
| 日期 | 2026-10-26 |
| 版本 | 0.2.11 |
| 触发需求 | 补齐公开发布所需资料，并发布 GitHub Release 插件包 |
| 授权选择 | 用户确认 MIT 许可证 |
| 代码锚点 | 本地正式构建先通过测试结构与兼容性检查-20261026-1743-01 |

**修改范围：**

- 增加 MIT 许可证、Apache-2.0 第三方许可文本和说明，随插件 JAR 分发。
- 补齐 README、安装步骤、平台范围、通用示例、已知限制和贡献说明。
- 测试、规格和变更资料中的组织名称、业务标识和本机绝对路径替换为通用示例，保留回归语义。
- 将已跟踪的 15 个 Gradle 缓存/报告移出 Git 跟踪，保留本地文件；调整忽略规则以纳入真正的测试夹具。
- Unix Gradle wrapper 标记为可执行；固定脚本换行。
- 使用自有纯 SVG 消息流图标替换第三方商标资源和内嵌图片。
- 将 IDE 兼容范围设为 233–262.*；本地正式构建增加测试、结构及兼容性门禁。
- 增加 Windows/Linux CI 与按版本 tag 触发的 GitHub Release。发布工作流仅在全部验证通过后上传 ZIP、SHA-256，并从 draft 转为正式发布。

**发布与回退边界：**

- GitHub Release 的 tag 与 `pluginVersion` 必须一致；tag 工作流不自动递增版本。
- 保留已有公开 Git 历史，没有强制改写历史；此前提交中的历史资料不会因新版本脱敏自动消失。
- 缓存清理仅改变跟踪状态，可从旧提交恢复。
- 此次不向 JetBrains Marketplace 提交，不调用 broker，不修改用户应用源码。

**验证：** 本地结构检查和 IC-233.11799.241 / IU-262.9437.185 二进制兼容性检查通过；后者报告两处已知弃用 API。测试结果及远端 CI/Release 状态见本次交付说明；校验值随 Release 提供。

**CI 校准：** 首次远端 Windows 检查中侧栏已成功打开目标，但 UI 功能用例把编辑器冷启动也算进单次 1 秒断言而失败。移除普通和特殊队列 UI 功能用例中与既定计时边界不符的耗时断言，保留 20 轮实际导航及每次 10 秒超时；后台导航的 P95 < 1 秒和刷新 P95 < 3 秒仍由独立性能用例验证。
