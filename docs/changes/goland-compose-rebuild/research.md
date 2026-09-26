---
title: Compose 重构调研依据
type: technical-design
status: draft
updated: 2026-09-26
---

# Compose 重构调研依据

本文件记录方案依赖的证据及其适用范围，避免将上游 master 文档或已有验证报告当作新插件的运行证明。

## 仓库证据

读取基线为 `b50a6b15d50658e067d3dd9283ea062e06aff559`；以下相对链接用于导航，实施时以实际固定 head 核对。

| 证据 | 核对结果 |
|---|---|
| [Panel 源码](../../../integrations/goland/src/main/kotlin/com/reqws/goland/ui/ReqwsToolWindowPanel.kt) | Swing 内容集中在单个文件，包含布局、自绘、列表及操作适配。 |
| [ViewModel 源码](../../../integrations/goland/src/main/kotlin/com/reqws/goland/ui/ReqwsToolWindowViewModel.kt) | 已有独立状态映射和投影确认逻辑，可保留语义而非整体重写。 |
| [Publisher 源码](../../../integrations/goland/src/main/kotlin/com/reqws/goland/project/TerminalStatePublisher.kt) | 初始快照、有序投递和终态保护已经实现。 |
| [构建配置](../../../integrations/goland/build.gradle.kts)与[工具链配置](../../../integrations/goland/settings.gradle.kts) | 当前 Kotlin 2.3.20、JVM 25、Gradle plugin 2.18.1；生产 forbidden-symbol/Verifier 检查存在。 |
| [兼容政策](../../../integrations/goland/compatibility.properties)与[描述符](../../../integrations/goland/src/main/resources/META-INF/plugin.xml) | 当前编译 2026.2、GUI 代表 2026.2.1.1、最低 262，不代表 Compose 已获验证。 |
| [现有 UI 测试](../../../integrations/goland/src/test/kotlin/com/reqws/goland/ui/ReqwsToolWindowPanelTest.kt) | 同时包含 Swing 细节断言与必须保留的长文本、安全和可访问性意图。 |
| [现有自动化入口](../ide-plugin-compatibility-automation/README.md) | 平台/API 检查与本机完整 IDE 场景分离，现有未完成边界不能被本计划清零。 |

## 官方依据

以下资料于 2026-09-26 核对。链接可能持续变化；S0 记录实际 SDK 中存在的符号和依赖解析结果，不按网页最新示例直接选版。

| 来源 | 对方案的影响 |
|---|---|
| [Jewel README](https://github.com/JetBrains/intellij-community/blob/master/platform/jewel/README.md) | 使用宿主库和主题桥接；明确第三方插件正式支持仍有限，不能因此宣称已可发行。 |
| [Gradle dependencies extension](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html) | `composeUI()` 是按 IDE build 添加 bundled modules 的 incubating helper；验证编译与打包两条 classpath。 |
| [Compose 桌面 JUnit 测试](https://kotlinlang.org/docs/multiplatform/compose-desktop-ui-testing.html) | 可用语义测试验证自身组件；其独立应用示例不是插件生产依赖模板。 |
| [IntelliJ UI integration tests](https://plugins.jetbrains.com/docs/intellij/integration-tests-ui.html) | 现有 Driver 定位不能未经验证照搬到 Compose；真实输入路径须在 S0 打通。 |
| [Coroutine scopes](https://plugins.jetbrains.com/docs/intellij/coroutine-scopes.html) | scope 的生命周期必须与平台对象关联，内容和项目分别管理。 |
| [Coroutine dispatchers](https://plugins.jetbrains.com/docs/intellij/coroutine-dispatchers.html) | 平台模型访问仍须遵守线程、modality 和锁规则。 |

## 工程决策与待验证项

单 Compose 根、纯 Mapper、稳定仓库 ID、最多两个并行 Worker、阶段验收条件与候选记录是本项目的工程决策，不是上游保证。

以下均未实际验证：当前 SDK 的完整模块组合；目标桥接的生命周期与公开 API；真实 Driver/可访问性输入；隔离 UI 测试在 CI 的 JBR/图形环境；动态卸载资源回收；Compose 相对 Swing 的性能。这些分别进入 S0 和 CMP-V01–V12，而不是以“应当支持”标成通过。
