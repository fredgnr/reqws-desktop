---
title: Compose S0 技术验证记录（2026-09-26）
type: test-report
status: active
updated: 2026-09-27
---

# Compose S0 技术验证记录（2026-09-26）

本记录固定 S0 的实际源码、依赖、检查及失败证据。2026-09-27 重新验证已打通真实输入和内容循环，并取得同机性能基线与冻结预算。独立审查确认两批 Compose 对照均出现一次循环后 CPU 超限；该问题尚未解释，G0 当前为 **blocked**，不进入 S1。

## 1. 候选与范围

工作分支为 `feat/plugin_rebuild_compose`，实施基线为 `384894dab0e8dcc7c0040b08802eb3bd167a3c85`，开始时工作树干净。可复现源码、工具及验证记录固定于本地提交 `7ad3387`，独立 Reviewer 审查该提交；随后只补充审查结论与索引。用户在本轮授权执行 S0，取代初始文档 PR 的“不构建、不启动测试 IDE”限制；没有授权日常 IDE 安装、发布或提高最低版本。

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

## 6. Swing 性能方法与预算状态

CMP-V11 的完整 IDE Swing 基线已于 2026-09-27 通过，运行 `2l_nqtbw`，1 个用例覆盖 3 个新进程，0 skipped；每进程 listener 创建/释放 21/21、最大活动数 1，均正常退出。首开从发出打开命令到 Driver 找到 enabled 控件；不是像素首次呈现或点击往返时间。

| 轮次 | 首开 ms | CPU 启动后 / 循环后（单核 %） | RSS 启动后 / 循环后 MiB | 堆启动后 / 循环后 MiB |
|---|---:|---:|---:|---:|
| 1 | 1499 | 20.50 / 11.60 | 2063.88 / 2054.36 | 511.22 / 684.28 |
| 2 | 1488 | 14.29 / 10.26 | 2278.86 / 2167.64 | 558.63 / 364.65 |
| 3 | 1464 | 18.76 / 11.74 | 2286.92 / 2309.81 | 462.00 / 407.00 |

窗口均为 1400×918、2× 显示变换，LookAndFeel ID 为 `LookAndFeelThemeAdapter`，期间没有切换主题。CPU 为整个 IDE 的观察窗平均值；RSS/堆为 13 个样本的中位数。新 system 目录会触发 IDE 自身索引和启动后台任务，原始索引日志单独保留，不将语言工具链或索引就绪作为 ReqWS 同步成功门槛。观察窗没有用户交互，不表示 IDE 所有后台工作为零。

预算冻结时间为 `2026-09-27T00:34:10.068083+00:00`，在 Compose 性能运行之前。以下规则用于相同代表环境、fixture 和三轮协议；它们是后续回归上限，不是性能改善声明。完整精度留在本地 `performance-budget.json`，表中显示两位小数。

| 指标 | 冻结上限 | 计算方法 |
|---|---:|---|
| 首开 | 5000 ms | max(5000 ms, Swing 最慢轮 × 2) |
| 启动后 CPU | 22.50% | max(3%, 该窗 Swing 最大值 + 2 个百分点) |
| 循环后 CPU | 13.74% | max(3%, 该窗 Swing 最大值 + 2 个百分点) |
| 循环前后 RSS 中位数增长 | 128.00 MiB | max(128 MiB, Swing 最大增长 × 1.5) |
| 循环前后堆中位数增长 | 259.59 MiB | max(64 MiB, Swing 最大增长 × 1.5) |
| 循环后 RSS 中位数 | 2565.81 MiB | Swing 该窗最大值 + 256 MiB |
| 循环后堆中位数 | 812.28 MiB | Swing 该窗最大值 + 128 MiB |

首开 5 秒为冷载入工程上限；CPU 留出 2 个百分点的测量与宿主波动，内存保留有界的缓存/原生渲染空间。一次堆增长不能证明泄漏；重复 listener、活动 composition 累积、持续异常负载、关闭后悬挂进程仍是独立硬失败。S4 必须用完整 Compose UI 重新对照，不能借用最小探针结果。

2026-09-27 已实现独立 `S0PerformanceProbeTest.performanceBaseline`，Swing/Compose 各 3 个新进程；每轮预热 30 秒、5 秒间隔采样 60 秒，完成独立的 20 次隐藏/显示与 20 次重建，再预热 30 秒、采样 60 秒。通过测试 IDE 自身的公开 MXBean 获取 PID、累计进程 CPU 和堆使用/已提交字节，以该 PID 用 `ps` 读取 RSS；记录每次循环及一个初始样本，不强制 GC。窗口尺寸、显示变换缩放和 LookAndFeel ID 同时记录。CPU 为进程累计 CPU 时间除以单调时钟的经过时间，100% 表示一个核心；它包括 IDE 后台活动和测量开销，不等于插件单独的 CPU。Swing 只加 listener 计数日志，不改业务行为。上述 V1 基线与预算已经冻结。首轮 Compose 性能运行 `h1b0pgm0` 在约 60 秒处由测试插件的周期截图触发 Skiko/Metal `Unsupported graphics configuration: BufferedImageGraphicsConfig`，即使收集了首轮样本也判为 fail，不计入成功对照。实际 SDK 字节码证实 `ide.performance.screenshot` 存在即每分钟调用 `captureComponent`，把整个窗口 `paint` 到 BufferedImage。

修正后的 V2 协议只在 S0 测试启动参数中清除此诊断选项，改用测试侧公开 `TakeScreenshotCommandKt.takeFullScreenshot` 获取实际屏幕像素；没有修改生产渲染设置，也没有抑制 IDE 错误。Swing 与 Compose 均须重新用 V2 运行；V1 预算文件保留，新预算按相同公式计算后逐项取旧上限与新值的较小者，禁止放宽。V2 匹配基线 `_v74cwmt` 已通过：1 用例、3 新进程、201 个采样点、6 张实际屏幕诊断图；各轮 listener 均 21/21 配对，最大活动数 1。V2 预算冻结于 `2026-09-27T00:55:59.154188+00:00`，在 V2 Compose 运行之前。启动后与循环后分别统计 CPU，不用启动尾部负载放宽后一个观察窗。

性能入口调试中以下失败均正常退出、未计入基线：`mvl_oaoh` 的懒创建检查误用 `getContentManager()` 且不在 EDT，已改用 EDT 上的公开 `getContentManagerIfCreated()`；`eu0d3pc3` 的 Swing 类型选择器不匹配真实 Driver 节点，已按实际 class/accessiblename 修正；`09xsc6aa` 的外部 `ProcessHandle.Info` 没有提供所需数据，已改为目标 IDE 自身的 MXBean。没有屏蔽 IDE 异常，也没有将不完整样本写入预算。

### V2 匹配基线与预算

| Swing 轮次 | 首开 ms | CPU 启动后 / 循环后 % | RSS 启动后 / 循环后 MiB | 堆启动后 / 循环后 MiB |
|---|---:|---:|---:|---:|
| 1 | 1421 | 20.75 / 10.16 | 2297.02 / 2050.41 | 485.79 / 609.13 |
| 2 | 1407 | 15.52 / 13.31 | 2306.92 / 2153.98 | 473.48 / 522.86 |
| 3 | 1381 | 14.81 / 11.02 | 2116.02 / 2094.02 | 525.33 / 613.81 |

最终使用的 V2 上限（不大于 V1）：`firstOpenMs` = 5000.00, `beforeCpuPercent` = 22.50, `afterCpuPercent` = 13.74, `rssGrowthMiB` = 128.00, `heapGrowthMiB` = 185.02, `steadyRssMiB` = 2409.98, `steadyHeapMiB` = 741.81。CPU 单位为单核百分比，时间为 ms，内存为 MiB。完整精度见本地 `performance-budget-v2.json`。此处仍是共享桌面上的同机工程基线，未宣称实验室隔离或统计上的性能提升。

V2 Compose 首次对照 `7i7bi6vo` 的宿主场景通过：1 用例、3 个正常退出的新进程、201 个采样点、6 张实际屏幕诊断图，未再出现图形异常。各轮 listener 21/21、composition 41/41 配对且最大活动数均为 1。数值预算为 **20/21 项通过、1 项失败**：第 2 轮循环后 CPU 15.57% 超过冻结的 13.74%，其余首开、CPU 与内存项通过。

| Compose 轮次 | 首开 ms | CPU 启动后 / 循环后 % | RSS 启动后 / 循环后 MiB | 堆启动后 / 循环后 MiB |
|---|---:|---:|---:|---:|
| 1 | 1704 | 17.01 / 9.45 | 2291.86 / 2177.92 | 453.50 / 412.96 |
| 2 | 1672 | 15.74 / 15.57 | 2304.38 / 2085.05 | 487.71 / 355.75 |
| 3 | 1765 | 18.12 / 10.21 | 2327.86 / 2203.59 | 464.99 / 385.50 |

超限轮最后三个 5 秒区间 CPU 分别约 43.29%、38.93%、33.72%；其时间范围与 IDE 保存全局 SDK/Library entities、组件设置的日志重合。该相关性不能证明因果，也不足以将失败改为噪声。保留同一 ZIP、V2 协议和冻结预算另跑三轮，复测 `9q78sin2` 的 3 个宿主场景均通过，但数值仍为 20/21 项通过，第 1 轮循环后 CPU 16.85% 再次超限。两批共 6 轮中有 2 轮超出冻结上限；不以三轮平均值代替逐轮上限，也不放宽阈值。

| Compose 复测轮次 | 首开 ms | CPU 启动后 / 循环后 % | RSS 启动后 / 循环后 MiB | 堆启动后 / 循环后 MiB |
|---|---:|---:|---:|---:|
| 1 | 1811 | 14.31 / 16.85 | 2189.53 / 2054.59 | 489.79 / 371.10 |
| 2 | 1769 | 16.04 / 10.46 | 2257.33 / 2311.66 | 474.00 / 572.77 |
| 3 | 1690 | 16.28 / 10.08 | 2250.77 / 2232.95 | 519.98 / 369.80 |

首开与全部内存项通过，六轮资源计数均配对且进程正常退出；没有观察到资源数累积。CPU 仍未形成稳定达标证据。这里只确认预算复测失败，不能单靠总进程 CPU 归因为 Compose，也不能据自动保存日志重合将失败忽略。后续需定位/区分宿主后台活动与 UI 开销，再在保留原结果和预算的前提下重新验证。

## 7. G0 判定与继续条件

| 项目 | 状态 | 结论 |
|---|---|---|
| S0.1 | pass | 工具链、模块、API、旧测试意图及 3 轮匹配 Swing 基线已核对。 |
| CMP-V01 / V02 | pass（最小探针范围） | 编译、模块/descriptor、隔离产物及受控负例有效；不代表完整迁移候选。 |
| CMP-V03 / S0.3 | pass（最小探针范围） | 2 个组件用例、真实点击/焦点/键盘、20 次隐藏与重建及监听释放通过。 |
| CMP-V11 | pass（S0 基线范围） | 基线与预算可复现且已冻结；完整 UI 对比仍属于 S4。本轮额外探针对比的循环后 CPU 在两批中各超限一次。 |
| S0.4 / 独立 Reviewer | reviewed / 风险未关闭 | 固定提交 `7ad3387` 已完成只读独立审查；无代码阻塞项，重复 CPU 预算失败为 P1 验收阻塞。 |
| G0 | blocked | 保留现有 Swing UI 和全部门禁，S1–S4 仍 planned。 |

原 G0 对 V11 的明确要求是可复现基线与冻结预算，该项已满足；完整性能比较主要属于 S4。当前 blocked 是 Main 与 Reviewer 针对已经重复出现、尚未解释的预算失败作出的阶段决策，不表示已证明 Compose 持续高负载或泄漏，也不改写原计划的阶段分工。剩余为循环后 CPU 超限的归因与稳定达标证据，该缺口未关闭前不将 S0 标成 completed。

### 独立审查记录

用户明确授权的只读 Reviewer `/root/s0_review` 于 2026-09-27 审查 `7ad3387` 的 13 个变更文件、相关规范及限定的本地证据；未修改文件、执行测试、启动 IDE 或访问远端。请求 profile 为 `astra`，继承 Main 的实际 `gpt-6-astra` / `xhigh`，委派前已通过 Main 当前会话的可信 `turn_context` 核验，状态为 `verified`；配置摘要保存在忽略目录的 `review-model.json`，不是依据子代理自述认定模型。

Reviewer 在内存将两份补丁逐 hunk 应用到固定基线，确认精确匹配、两种候选的宿主测试/测量方法一致，正式生产构建和最低 262 政策未变。审查核对了依赖隔离、实际 API 字节码与 disposer 接线、真实输入日志、V2 截图适配仍保留 IDE 错误失败检查，以及 58 个旧测试方法的迁移清单。最终输入计数为 1 → 2 → 重建后 1，listener 21/21、composition 41/41，最大活动数均为 1，进程正常退出。

独立复算 V1/V2 Swing 与两批 Compose 的全部原始样本，与现有 JSON 一致；V2 预算未放宽且冻结早于候选对比。Reviewer 确认两次循环后 CPU 分别为 15.573860% 和 16.852476%，超过 13.744078% 上限；结论为无代码阻塞项、保留这项 P1 验收阻塞，建议 G0 继续 blocked。自动保存日志与峰值重合不能消除失败，也不能证明 Compose 是原因。

审查限制：366 个保留测试及 2 个组件测试核对到构建日志与 JUnit 汇总，未逐个重读原始 XML；未访问允许范围外的 ZIP、完整截图、用户配置或会话。动态卸载、VoiceOver、CI 图形环境、长期资源泄漏及完整 UI 性能仍未验证，现有计数和最小探针不替代这些覆盖。

## 8. 本地证据与复现

忽略目录 `integrations/goland/build/reports/compose-s0/` 保存本轮命令日志、API 注解/字节码摘录、Junit 汇总、冻结预算、两批比较结果、原始 TSV 及专用宿主报告；原完整宿主日志留在各 `reqws-compose-host-*` 私有目录。报告/截图可能含本机路径和其他应用画面，不将其加入 Git。临时文件日后不可用时应重新执行，不由本文推断仍有原始证据。

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
# 性能：用 --variant swing 暂存另一候选；分别编译/导出 ZIP 后运行同一个 performance 场景。
python3 "$PROBE/scripts/run_compose_s0.py" \
  --profile "$HOME/.reqws-ide-tests/goland-2026.2.1.1" \
  --archive <对应候选的确切ZIP> --version 0.1.7 --scenario performance --variant compose
```

该入口仅覆盖 S0 的输入与性能实验，每次执行一个用例，不接受其报告替代既有三组/九进程 V10 集成报告。有关组件测试和真实输入边界，分别参照[官方 Compose 测试说明](https://kotlinlang.org/docs/multiplatform/compose-desktop-ui-testing.html)和[Driver UI 测试说明](https://plugins.jetbrains.com/docs/intellij/integration-tests-ui.html)。

## 9. 旧用例逐项迁移清单

以下 58 个用例均在原 Swing 基线实际执行。`保留`表示继续保护原契约；`planned` 的新类/方法尚未实现或运行，不能据表删除旧测试。新方法默认延续表中的同名行为，必要的状态/语义拆分在 S1/S2 再固定。

### ReqwsToolWindowPanelTest

[实际源码](../../../integrations/goland/src/test/kotlin/com/reqws/goland/ui/ReqwsToolWindowPanelTest.kt)。文本、布局与可访问行为；只替换 Swing 类型断言。

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

[实际源码](../../../integrations/goland/src/test/kotlin/com/reqws/goland/ui/ReqwsToolWindowViewModelTest.kt)。保留业务状态、投影证明及错误优先级。

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

[实际源码](../../../integrations/goland/src/test/kotlin/com/reqws/goland/ui/LatestOnlyEdtDispatcherTest.kt)。后到值优先、有序投递及终态拒绝。

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
