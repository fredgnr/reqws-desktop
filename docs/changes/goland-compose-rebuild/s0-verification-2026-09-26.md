---
title: Compose S0 技术验证记录（2026-09-26）
type: test-report
status: active
updated: 2026-09-27
---

# Compose S0 技术验证记录（2026-09-26）

本记录固定 S0 的实际源码、依赖、功能检查及审查证据。2026-09-27 用户明确取消性能门禁后，现有证据中没有未关闭的 S0 功能问题，验收结论调整为 **G0 pass、S0 completed**；S1–S4 尚未开始。

## 1. 候选与范围

工作分支为 `feat/plugin_rebuild_compose`，实施基线为 `384894dab0e8dcc7c0040b08802eb3bd167a3c85`，开始时工作树干净。可复现源码、工具及验证记录固定于本地提交 `7ad3387`，独立 Reviewer 审查该提交；审查记录提交为 `0c27483`；本次仅按用户新要求调整验收范围、报告与索引，源码、测试 harness 及候选 ZIP 均未改变。用户在本轮授权执行 S0，取代初始文档 PR 的“不构建、不启动测试 IDE”限制；没有授权日常 IDE 安装、发布或提高最低版本。

生产工作树仍使用原有 Swing UI。最小 Compose 候选在独立临时源码副本构建：固定 Git 基线加[验证补丁](../../../tests/fixtures/goland-compose-s0/probe.patch)，由[暂存脚本](../../../scripts/stage_compose_probe.py)重建。补丁是可复现的实验源码，不是已合入生产的 UI，也不是运行时回退开关。正式构建不应用补丁，测试控制代理只在 `integrationTest` 中。

最小候选包含 `DefaultButton`、同一份被语义测试调用的 `ReqwsComposeProbeLabel`、手动同步回调、内容级 listener 及 composition 计数日志。计数只观察这个探针的注册/关闭，不代表整个项目所有监听器或原生资源均已排除泄漏。候选未签名，未安装到日常 IDE。早期仅按钮候选的 Verifier 结果不能代替加入生命周期观察后的候选。

最低版本影响：**不变**，仍为整个 262 系列，无 `until-build` 或 `strict-until-build`。未改变 `compatibility.properties`、GoLand 产品限制、CI、签名、资源 catalog 或领域同步代码。没有 i18n delta；探针复用既有按钮资源，数字为临时测量反馈。

## 2. 固定工具链与模块

| 项目 | 实际输入 |
|---|---|
| OS / 架构 | macOS 15.7.3 / 24G419 / aarch64 |
| 编译 SDK | GO 2026.2 / 262.8665.270；通过 `reqwsGoLandSdkPath` 只读复用缓存 |
| 真实宿主 | GO 2026.2.1.1 / 262.9437.286；既有专用 profile，独立 fixture/system/plugins/log |
| JBR / JVM | JBR 25.0.3+9-508.16-nomod，Java 25；编译与本机试验使用相同 JBR |
| Gradle / IntelliJ Gradle plugin | Wrapper 9.3.0 / 2.18.1 |
| Kotlin JVM / Compose compiler | 同一 `kotlinVersion` 来源，均为 2.3.20 |
| SDK 中运行时 | Compose Multiplatform / Runtime 1.11.0，Skiko 0.144.5，Kotlin stdlib 2.4.0；来自 SDK `license/third-party-libraries.json`，不是按网页最新版本选取 |
| 独立组件测试 | `ui-test-junit4-desktop` / `ui-test-desktop` 1.11.0，coroutines-test-jvm 1.10.2，Skiko macOS arm64 runtime 0.144.5；与生产 ZIP 隔离 |
| Starter / Driver / Verifier | 262.9437.185 / 262.9437.185 / 1.410 |

`composeUI()` 在该 SDK 上实际提供以下七个模块，不需要改用手动 `bundledModule`：

- `intellij.libraries.skiko`
- `intellij.platform.compose`
- `intellij.libraries.compose.foundation.desktop`
- `intellij.libraries.compose.runtime.desktop`
- `intellij.platform.jewel.foundation`
- `intellij.platform.jewel.ui`
- `intellij.platform.jewel.ideLafBridge`

ProductInfo 将库标记为 `productModuleV2`；`com.intellij.modules.compose` 是 plugin alias。SDK 的 `intellij.platform.compose.xml` 声明该 alias，候选 descriptor 增加必需的 `<depends>com.intellij.modules.compose</depends>`。源码与实际编译、ZIP、Verifier 一起核验，没有把 Gradle classpath 可见等同于运行依赖已声明。[官方依赖说明](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html#compose-ui)

独立 Starter 编译暴露一项实际问题：Compose compiler 同时作用于 `compileIntegrationTestKotlin`，原本不含 Compose 的宿主编译以 `IncompatibleComposeRuntimeVersionException` 失败。探针只给它的 **compile classpath** 加入 SDK 的 `intellij.libraries.compose.runtime.desktop.jar`，不扩展其 runtime classpath，也不将 Starter 依赖加入生产。修复后的宿主编译通过。最终实际 classpath 记录为 compile 269 项、生产 runtime 0 项、独立组件测试 runtime 281 项、Starter runtime 348 项；后者没有 Compose/Jewel/Skiko 渲染 JAR。

## 3. 当前 SDK 的精确 API 结论

通过 `javap -v -c` 核对实际 SDK 字节码，结论仅适用于上述固定输入：

| 符号 | 结论及约束 |
|---|---|
| `ToolWindow.addComposeTab(tabDisplayName, isLockable, isCloseable, focusOnClickInside, content)` | 可用，返回 `Unit`，该重载没有 JetBrains Internal/Experimental 注解；探针显式传入 `focusOnClickInside = true`。 |
| `JewelComposePanel(focusOnClickInside, config, content)` | 公开的另一入口；没有直接使用它替换已验证的 helper。 |
| `JewelComposePanelWrapper` | Internal；只读调查其行为，没有在 ReqWS 生产源码中引用、强转或复制实现。 |
| `JewelComposeNoThemePanel` / `composeWithoutTheme` | Experimental；没有采用。 |
| `addComposeTab` 的内容资源管理 | 创建并添加 Content，但不返回 Content，也不设置 disposer。探针在 EDT 上用新增 Content 的身份绑定 disposer，关闭 listener；主题由桥接提供，不再包第二层主题。 |
| Driver `ComposeXpathDataModelExtension` | 代表 IDE 的 performanceTesting 模块已提供 Compose semantics 节点及 `testtag`、`text`、`focused` 属性。它属于测试设施，不进入插件 ZIP。 |

wrapper 的 `addNotify/removeNotify` 管理 AWT focus listener；这只能解释桥接实现，不能代替隐藏、重建、卸载和原生资源回收的运行证据。项目关闭时观察到探针 `composition active=0` 和 `listener active=0`，2026-09-27 已完成 20 次内容重建，详见第 5 节。

## 4. 已执行的检查

下列命令中 `$PROBE` 为暂存脚本输出的目录；`$SDK` 为 GO 2026.2 的已核对 SDK；`JAVA_HOME` 指向代表 IDE 的 JBR。所有 Gradle 命令使用 `--no-configuration-cache -PreqwsGoLandSdkPath="$SDK"`。这些本地变量不改变版本政策。

| 检查 | 实际结果 |
|---|---|
| 原工作树 `compileKotlin compileTestKotlin test --tests 'com.reqws.goland.ui.*' --tests 'com.reqws.goland.project.TerminalStatePublisherTest' exportPluginArchivePath` | pass；6 类、58 用例，0 failure / error / skipped；Swing 基线编译、禁用符号扫描、ZIP 构建通过。不是性能基线。 |
| 早期 Compose 候选 `compileKotlin compileTestKotlin exportPluginArchivePath` | pass；49 个生产文件、309 个 JAR class 的禁用符号检查通过。 |
| 早期候选 `verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin` | pass；GO-262.8665.270 和 GO-262.9437.286 均 Compatible。 |
| 独立 `composeUiTest` | pass；`ReqwsComposeProbeLabelTest.preservesLiteralUnicodeLabel`、`observesUpdatedState`，2 用例，0 failure / error / skipped；实际 JBR/Skiko 渲染运行，未启动完整 IDE。 |
| 最小宿主 `compileIntegrationTestKotlin` | 初次 fail：compiler 缺 runtime；修复 compile classpath 后 pass。 |
| `python3 -m unittest discover -s tests/workflows -p 'test_compose_*.py' -v` | pass；11 用例、0 skipped。覆盖产物受控反例、已知 S0 控制类、两种源码暂存和实际 descriptor 遗漏的重建回归。 |
| `python3 scripts/check_compose_artifact.py --archive <确切 ZIP> --version 0.1.7` | pass；观察候选含 310 个自身 class，唯一插件 JAR；未嵌入 Compose/Jewel/Skiko/stdlib/coroutines/test runtime。 |
| 2026-09-26 观察候选 `npm run check:goland` | pass；37 类、366 用例、0 failure / error / skipped，7 个稳定 API 目标通过。 |
| 2026-09-27 修正后的最终候选 `npm run check:goland` | pass；37 类、366 用例，0 failure / error / skipped；编译、禁用符号、结构/descriptor、确切 ZIP 的 7 个 API 目标通过。与已通过真实输入的 ZIP 字节完全相同。 |
| 2026-09-27 Swing 计数基线直接回归 | pass；6 类、58 用例，0 failure / error / skipped；生产及宿主编译、禁用符号、ZIP 构建通过。 |

本轮稳定 API 矩阵冻结于 2026-09-27，实际通过的目标为 `GO-262.8665.270`、`GO-262.8665.336`、`GO-262.9437.195`、`GO-262.9437.286`、`GO-262.10315.135`、`GO-262.10315.160`、`GO-262.10968.67`。无上限声明不等于未来版本已验证。

[产物检查](../../../scripts/check_compose_artifact.py)是显式使用的 S0 工具，没有偷偷改变当前非 Compose 插件的发布门禁。它沿用原 ZIP 身份、路径和大小校验，按 JAR 内 class namespace 检查宿主库/已知测试库及本轮 S0 控制类，覆盖改名 JAR 与 multi-release 目录；不声称可以识别任意恶意重定位代码或未知控制端点。探针测试代理未被打包还须结合源码和实际 ZIP 清单核对。

## 5. 真实宿主与历史失败

本机入口消费 `build/release/plugin-archive.txt` 指定的同一个 0.1.7 ZIP，不重新构建生产插件；报告中的摘要只留在私有日志，本文不复制摘要。专用 profile 为 `~/.reqws-ide-tests/goland-2026.2.1.1`，没有复制日常授权、配置或用户项目。

| 本地运行目录后缀 | 结果 |
|---|---|
| `ssrvqeb3` | 主动中止于 Gradle runtime 依赖解析，未发出 IDE 启动请求。离线诊断指出缺少 `rhizomedb-transactor-rebase`、`codeowners`、`codeowners-monorepo-resolver` 的 262.9437.185 缓存；官方 POM 均 HTTP 200。 |
| `1awbtl0j` | 使用仓库已有的 `-Porg.jetbrains.intellij.platform.useCacheRedirector=false` 直连官方源后启动成功。按 Compose `testtag` 定位，Driver `.click()` 后日志和实际文本变为 1；随后 Space 激活未变为 2，1 个用例失败、0 skipped。 |
| `mv2c_4su` | 对同一 ZIP 重跑：请求 IDE 激活，检查 frame active，再执行 Driver `.strictClick()`；等待回调超时，1 个用例失败、0 skipped。尚未进入键盘断言及重建循环。 |
| `rf_e4jlc` | 2026-09-26 用户留出桌面后重跑仍失败；失败画面有另一个 ReqWS/Electron 自动化窗口遮挡。未停止其他任务，未进入重建循环。 |
| `iszzkws9` | 2026-09-27 从保存补丁重建后，在打开内容时 `NoClassDefFoundError: ToolWindowScope`。补丁漏保存 descriptor 依赖，编译/API 检查未能替代宿主类加载验证。该候选不采用。 |
| `s8ak6igm` | 修复 descriptor 重建及挂载失败时 listener 注册顺序后，真实输入通过：1 用例，0 failure / error / skipped，1 个正常退出的进程。 |
| `1sih9ai7` | 最终 V2 截图协议与最终测试代码下再次通过完整输入场景，1 用例、0 failure / error / skipped；listener 21/21、composition 41/41、最大活动数 1，进程正常退出。 |

失败时的完整屏幕截图显示前台为其他应用。该证据提示共享桌面的焦点竞争，但不足以确定事件发送瞬间的前台状态，也不能排除 Driver synthetic component 的坐标/输入路径问题。没有使用业务 service 直调来冒充点击，没有把首轮回调或静态截图记为 CMP-V03 pass。没有继续向不稳定的前台发送键盘/鼠标事件。

2026-09-26 三轮实际 IDE 进程分别正常退出，`processes.tsv` 均为 `started → exited`，没有 `passed` 或 `forced-kill`。`active-session.json` 已由原会话清理条件移除；项目关闭的探针 listener/composition 日志均归零。这些旧失败不构成内容重建证据。

2026-09-27 两次成功输入轮在发送输入前输出测试侧握手文件；Computer Use 读取到本轮 fixture 的 ReqWS 内容并对该测试窗口执行 Raise，随后确认握手。握手只协调前台，不执行按钮/action/service。Driver `.strictClick()` 产生计数 1，语义焦点与活动 frame 断言通过，Space 产生计数 2。之后完成 20 次隐藏/显示、20 次 Content 删除/重建，重建后实际点击产生计数 1。listener 创建/关闭 21/21，composition 创建/关闭 41/41，各自最大活动数 1，关闭后均为 0；进程为 `started → passed → exited`。隐藏/显示期间 listener 保留，而本 SDK 桥接会销毁并重建 composition；因此后续 Presenter/订阅仍须由 Content 生命周期拥有，不能把 `remember` 或 composable 生命周期等同于 Content 生命周期。这证明最小宿主路径，不代替 S3 的动态卸载、VoiceOver 或长期泄漏检查。

重建缺陷修复保存在 `probe.patch`：补全 descriptor；挂载与 Content 获取成功后才注册观察 listener。宿主入口在 Compose 模式先执行产物检查，缺声明的实际失败 ZIP 已被拒绝。暂存回归同时核对 Compose 依赖必需且唯一、Swing 基线不含该依赖，避免再次遗漏实验源文件。

## 6. 验收范围调整与历史观察

2026-09-27 用户明确要求“不再考虑性能相关的门禁”，并在不存在功能问题时将验收转为通过。[技术方案](technical-design.md)、[测试方案](test-plan.md)及 S0/S4 任务已同步：首开耗时、CPU、内存采样和预算不参与 G0–G4 判定，不再要求性能基线、对比、归因或复测。内容隐藏/重建、监听与 composition 释放、项目关闭和进程退出仍按功能正确性验收。

旧门禁下，两批 Compose 测量各有一次循环后 CPU 超限，Main 与 Reviewer 当时据此将 G0 记为 blocked；完整数表与当时结论保存在 Git 提交 `0c27483`，原始本地日志不改写。该项现在移出验收范围，不再是待关闭问题；本次没有将旧数值比较改成 pass，也没有据此认定性能改善或证明原因在 Compose。

测量调试暴露的宿主问题已经修复并经过最终功能复核：在 EDT 使用公开 `getContentManagerIfCreated()`；按真实 Driver 节点修正 Swing 选择器；采样改用目标 IDE 自身公开 MXBean。测试插件周期截图的离屏 `paint` 曾触发 Metal 图形异常；V2 仅清除测试诊断选项 `ide.performance.screenshot`，改用公开 `TakeScreenshotCommandKt.takeFullScreenshot` 获取实际屏幕像素，仍执行 IDE 错误失败检查。最终输入运行 `1sih9ai7` 使用该 V2 协议并通过，没有修改生产渲染设置或抑制异常。

## 7. G0 验收结论

| 项目 | 状态 | 结论 |
|---|---|---|
| S0.1 | pass | 工具链、模块、公开 API 与旧测试意图已核对，最低版本仍为整个 262 系列。 |
| CMP-V01 / V02 / S0.2 | pass（最小探针范围） | 编译、模块/descriptor、隔离产物及受控负例有效；不代表完整迁移候选。 |
| CMP-V03 / S0.3 | pass（最小探针范围） | 2 个组件用例、真实点击/焦点/键盘、重建后实际点击通过。 |
| CMP-V11 | pass（最小宿主范围） | 20 次隐藏/显示和 20 次内容重建完成；listener 21/21、composition 41/41，各自最大活动数 1，关闭后归零且进程正常退出。 |
| S0.4 / 独立 Reviewer | completed | 固定提交 `7ad3387` 的只读独立审查已完成，无代码或功能阻塞项；原性能阻塞按用户新要求移出验收范围。 |
| G0 / S0 | pass / completed | 当前必需的 S0 功能、依赖、兼容与生命周期验证成立，满足进入 S1 的前置条件。S1–S4 仍 planned。 |

本次由 Main 按更新后的验收范围核对已有同候选证据并调整结论，未重新运行 IDE，也未声称 Reviewer 在新规则下进行第二次审查。S0 仅证明最小宿主可行性，正式生产界面仍是 Swing；完整界面功能、动态卸载、VoiceOver、CI 图形环境等仍由后续阶段验证，不能借用本次 G0 通过将其标为完成。

### 独立审查记录

用户明确授权的只读 Reviewer `/root/s0_review` 于 2026-09-27 审查 `7ad3387` 的 13 个变更文件、相关规范及限定的本地证据；未修改文件、执行测试、启动 IDE 或访问远端。请求 profile 为 `astra`，继承 Main 的实际 `gpt-6-astra` / `xhigh`，委派前已通过 Main 当前会话的可信 `turn_context` 核验，状态为 `verified`；配置摘要保存在忽略目录的 `review-model.json`，不是依据子代理自述认定模型。

Reviewer 在内存将两份补丁逐 hunk 应用到固定基线，确认精确匹配、两种候选的宿主测试方法一致，正式生产构建和最低 262 政策未变。审查核对了依赖隔离、实际 API 字节码与 disposer 接线、真实输入日志、V2 截图适配仍保留 IDE 错误失败检查，以及 58 个旧测试方法的迁移清单。最终输入计数为 1 → 2 → 重建后 1，listener 21/21、composition 41/41，最大活动数均为 1，进程正常退出。

Reviewer 原结论为无代码阻塞项，仅在当时规则下将重复 CPU 超限列为 P1 验收阻塞；该历史结论保留在 `0c27483`，不冒充新规则下的审查结果。审查限制：366 个保留测试及 2 个组件测试核对到构建日志与 JUnit 汇总，未逐个重读原始 XML；未访问允许范围外的 ZIP、完整截图、用户配置或会话。

## 8. 本地证据与复现

忽略目录 `integrations/goland/build/reports/compose-s0/` 保存本轮命令日志、API 注解/字节码摘录、JUnit 汇总及专用宿主报告；旧性能预算、比较结果与原始 TSV 仅为历史诊断材料，不参与当前验收。原完整宿主日志留在各 `reqws-compose-host-*` 私有目录。报告/截图可能含本机路径和其他应用画面，不将其加入 Git。临时文件日后不可用时应重新执行，不由本文推断仍有原始证据。

```bash
python3 scripts/stage_compose_probe.py
# 将输出目录设为 PROBE，并按上表设置 JAVA_HOME 和 SDK。
export ORG_GRADLE_PROJECT_reqwsGoLandSdkPath="$SDK"
"$PROBE/integrations/goland/gradlew" -p "$PROBE/integrations/goland" \
  --no-configuration-cache -PreqwsGoLandSdkPath="$SDK" \
  compileKotlin compileTestKotlin compileIntegrationTestKotlin composeUiTest exportPluginArchivePath
python3 scripts/check_compose_artifact.py --archive <plugin-archive.txt中的绝对路径> --version 0.1.7
# 仅在前台会话可供本轮测试独占时运行；不消费日常 IDE profile。
python3 "$PROBE/scripts/run_compose_s0.py" \
  --profile "$HOME/.reqws-ide-tests/goland-2026.2.1.1" \
  --archive <plugin-archive.txt中的绝对路径> --version 0.1.7 --foreground-handshake
# 等待 ready-for-input，实际确认并置前该测试窗口后，在私有运行目录创建 foreground-ready。
```

该入口默认覆盖 S0 的输入与内容生命周期，每次执行一个用例，不接受其报告替代既有三组/九进程 V10 集成报告。补丁中保留的性能采样场景仅供可选诊断，不是必需验收步骤。有关组件测试和真实输入边界，分别参照[官方 Compose 测试说明](https://kotlinlang.org/docs/multiplatform/compose-desktop-ui-testing.html)和[Driver UI 测试说明](https://plugins.jetbrains.com/docs/intellij/integration-tests-ui.html)。

## 9. 旧用例逐项迁移清单

以下 58 个用例均在原 Swing 基线实际执行。`保留`表示继续保护原契约；`planned` 的新类/方法尚未实现或运行，不能据表删除旧测试。新方法默认延续表中的同名行为，必要的状态/语义拆分在 S1/S2 再固定。

### ReqwsToolWindowPanelTest

[实际源码](https://github.com/fredgnr/reqws-desktop/blob/d56d8f7c5ec3429e9a518cd89b8c86390228914f/integrations/goland/src/test/kotlin/com/reqws/goland/ui/ReqwsToolWindowPanelTest.kt)。文本、布局与可访问行为；只替换 Swing 类型断言。

| 旧方法 / 保护意图 | 新类 / 方法及状态 | V-ID |
|---|---|---|
| `long unbroken tooltips wrap within a bounded real Swing layout without losing text` | ReqwsScreenTest（同名行为，planned） | CMP-V08/V09 |
| `wrapped tooltips preserve literal markup user spaces and whole Unicode graphemes` | ReqwsScreenTest（同名行为，planned） | CMP-V08/V09 |
| `summary and repository groups use the shared card treatment` | ReqwsScreenTest（同名行为，planned） | CMP-V08/V09 |
| `repository header keeps title and count separate with the count right aligned` | ReqwsScreenTest（同名行为，planned） | CMP-V07/V08 |
| `manifest text labels disable Swing automatic HTML rendering` | ReqwsScreenTest（同名行为，planned） | CMP-V08/V09 |
| `empty manifest labels omit redundant tooltips` | ReqwsScreenTest（同名行为，planned） | CMP-V08/V09 |
| `long manifest labels remain horizontally compressible with complete accessible text` | ReqwsScreenTest（同名行为，planned） | CMP-V08/V09 |
| `status and detail labels remain horizontally compressible` | ReqwsScreenTest（同名行为，planned） | CMP-V08/V09 |
| `copy feedback preserves failure details and their accessible text` | ReqwsScreenTest（同名行为，planned） | CMP-V08/V09 |
| `repeated copy feedback remains a single separate line` | ReqwsScreenTest（同名行为，planned） | CMP-V08/V09 |
| `new state details clear previous copy feedback and accessible hints` | ReqwsScreenTest（同名行为，planned） | CMP-V08/V09 |
| `diagnostics and copy feedback fit a narrow summary without losing complete text` | ReqwsScreenTest（同名行为，planned） | CMP-V08/V09 |
| `repository lists remain horizontally compressible for long names` | ReqwsScreenTest（同名行为，planned） | CMP-V08/V09 |
| `repository viewport constrains long rows to the visible width` | ReqwsScreenTest（同名行为，planned） | CMP-V08/V09 |
| `repository viewport follows content height and caps itself at six rows` | ReqwsScreenTest（同名行为，planned） | CMP-V07/V08 |
| `repository rows stay compact separated and preserve text status` | ReqwsScreenTest（同名行为，planned） | CMP-V07/V08 |
| `status pill keeps semantic text and its intrinsic compact width` | ReqwsScreenTest（同名行为，planned） | CMP-V08/V09 |
| `longest production status pill remains fully visible inside the padded narrow body` | ReqwsScreenTest（同名行为，planned） | CMP-V08/V09 |
| `long untrusted repository names fit a narrow row without losing safe accessible text` | ReqwsScreenTest（同名行为，planned） | CMP-V08/V09 |
| `manual Git Root status remains visible in a narrow repository row` | ReqwsScreenTest（同名行为，planned） | CMP-V08/V09 |
| `user root coverage preserves repo3 name and complete explanation in narrow rows` | ReqwsScreenTest（同名行为，planned） | CMP-V08/V09 |
| `scrolling viewport bounds long status and untrusted name without losing accessible text` | ReqwsScreenTest（同名行为，planned） | CMP-V08/V09 |
| `primary and secondary actions preserve hierarchy in a narrow vertical layout` | ReqwsScreenTest（同名行为，planned） | CMP-V08/V09 |

### ReqwsToolWindowViewModelTest

[实际源码](https://github.com/fredgnr/reqws-desktop/blob/d56d8f7c5ec3429e9a518cd89b8c86390228914f/integrations/goland/src/test/kotlin/com/reqws/goland/ui/ReqwsToolWindowViewModelTest.kt)。保留业务状态、投影证明及错误优先级。

| 旧方法 / 保护意图 | 新类 / 方法及状态 | V-ID |
|---|---|---|
| `user root coverage exposes a compact status and separate explanation only for covered repositories` | ReqwsUiStateMapperTest（同名行为，planned） | CMP-V04 |
| `maps synchronized snapshots to localized resource keys` | ReqwsUiStateMapperTest（同名行为，planned） | CMP-V04 |
| `keeps the previous snapshot visible for an invalid manifest` | ReqwsUiStateMapperTest（同名行为，planned） | CMP-V04 |
| `preserves long html shaped manifest values as unmodified display data` | ReqwsUiStateMapperTest（同名行为，planned） | CMP-V04/V08 |
| `hides an initial read before a ReqWS manifest is found` | ReqwsUiStateMapperTest（同名行为，planned） | CMP-V04 |
| `keeps a previous snapshot visible while rereading the manifest` | ReqwsUiStateMapperTest（同名行为，planned） | CMP-V04 |
| `does not trust a persisted digest as cold-service live projection proof` | ReqwsUiStateMapperTest（同名行为，planned） | CMP-V04 |
| `does not render a stable lifecycle as synced without current-service projection proof` | ReqwsUiStateMapperTest（同名行为，planned） | CMP-V04 |
| `hides a previously validated projection while a new read or apply is in progress` | ReqwsUiStateMapperTest（同名行为，planned） | CMP-V04 |
| `disables every action after disposal` | ReqwsUiStateMapperTest（同名行为，planned） | CMP-V04 |
| `maps progress warning and terminal lifecycles to accessible status tones` | ReqwsUiStateMapperTest（同名行为，planned） | CMP-V04 |
| `keeps the Safe Mode status compact and exposes a separate recovery hint` | ReqwsUiStateMapperTest（同名行为，planned） | CMP-V04 |
| `shows actionable read-only Git Root diagnostics without claiming the model was preserved` | ReqwsUiStateMapperTest（同名行为，planned） | CMP-V04 |
| `does not label repositories active when the live project content failed to converge` | ReqwsUiStateMapperTest（同名行为，planned） | CMP-V04 |
| `does not invent a project-content layer for an unknown failure field` | ReqwsUiStateMapperTest（同名行为，planned） | CMP-V04 |
| `does not label repositories active when project-model ownership or apply fails` | ReqwsUiStateMapperTest（同名行为，planned） | CMP-V04 |
| `shows a missing repository when it disappears during the VCS inspection` | ReqwsUiStateMapperTest（同名行为，planned） | CMP-V04 |
| `shows inspection failures as unavailable instead of active or unconfigured` | ReqwsUiStateMapperTest（同名行为，planned） | CMP-V04 |
| `fails closed when an inspection omits a present repository index` | ReqwsUiStateMapperTest（同名行为，planned） | CMP-V04 |
| `keeps Safe Mode guidance ahead of read-only Git Root diagnostics` | ReqwsUiStateMapperTest（同名行为，planned） | CMP-V04 |

### LatestOnlyEdtDispatcherTest

[实际源码](https://github.com/fredgnr/reqws-desktop/blob/d56d8f7c5ec3429e9a518cd89b8c86390228914f/integrations/goland/src/test/kotlin/com/reqws/goland/ui/LatestOnlyEdtDispatcherTest.kt)。后到值优先、有序投递及终态拒绝。

| 旧方法 / 保护意图 | 新类 / 方法及状态 | V-ID |
|---|---|---|
| `older queued action cannot overwrite a newer action delivered on EDT` | ReqwsPresenterTest（同名行为，planned） | CMP-V05 |
| `only newest queued action runs even when queued callbacks run out of order` | ReqwsPresenterTest（同名行为，planned） | CMP-V05 |
| `terminal Tool Window state invalidates queued rendering and rejects later states` | ReqwsPresenterTest（同名行为，planned） | CMP-V05 |
| `dispose invalidates queued action and rejects later submissions` | ReqwsPresenterTest（同名行为，planned） | CMP-V05 |

### ReqwsToolWindowAvailabilityControllerTest

[实际源码](../../../integrations/goland/src/test/kotlin/com/reqws/goland/ui/ReqwsToolWindowAvailabilityControllerTest.kt)。内容懒创建前的可用性与销毁保护。

| 旧方法 / 保护意图 | 新类 / 方法及状态 | V-ID |
|---|---|---|
| `updates availability from service states before content creation` | 原类/原方法，保留 | CMP-V06 |
| `drops queued availability updates after disposal` | 原类/原方法，保留 | CMP-V06 |
| `drops availability updates after the tool window is disposed` | 原类/原方法，保留 | CMP-V06 |
| `ignores the terminal disposed state` | 原类/原方法，保留 | CMP-V06 |

### ReqwsToolWindowFactoryTest

[实际源码](../../../integrations/goland/src/test/kotlin/com/reqws/goland/ui/ReqwsToolWindowFactoryTest.kt)。入口检测与普通项目边界。

| 旧方法 / 保护意图 | 新类 / 方法及状态 | V-ID |
|---|---|---|
| `testRemainsApplicableToAFileBasedProjectBeforeManifestCreation` | 原类/原方法，保留 | CMP-V06 |
| `testOrdinaryWorkspaceManifestDoesNotActivateTheDedicatedEntryPlugin` | 原类/原方法，保留 | CMP-V06 |

### TerminalStatePublisherTest

[实际源码](../../../integrations/goland/src/test/kotlin/com/reqws/goland/project/TerminalStatePublisherTest.kt)。领域有序发布与版本/终态保护。

| 旧方法 / 保护意图 | 新类 / 方法及状态 | V-ID |
|---|---|---|
| `serializes reentrant publications and rejects values after terminal state` | 原类/原方法，保留 | CMP-V05 |
| `orders a concurrent terminal notification after an in-flight publication` | 原类/原方法，保留 | CMP-V05 |
| `a listener added after termination observes only the terminal state` | 原类/原方法，保留 | CMP-V05 |
| `versioned transient publication carries its stable baseline and defers listeners` | 原类/原方法，保留 | CMP-V05 |
| `stale publication token cannot overwrite a newer stable state` | 原类/原方法，保留 | CMP-V05 |

信任、路径 containment、ownership、VCS 只读、Project Model 和领域并发用例不在 UI 替换删除清单中；保留全部现有门禁。独立组件新增的 2 个标签用例仅验证 S0 技术路径，不承担上表 58 个意图的迁移完成证明。
