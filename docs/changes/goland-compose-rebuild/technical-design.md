---
title: GoLand 插件 Compose 重构技术方案
type: technical-design
status: draft
updated: 2026-09-26
---

# GoLand 插件 Compose 重构技术方案

本方案将界面重构限定在插件展示与宿主接入层，以减少手工 Swing 布局和刷新代码，同时保留现有同步与安全语义。

## 1. 范围与非目标

目标是替换状态区、工作区摘要、仓库列表、诊断和同步/打开配置/复制诊断操作，采用一个 Compose 内容根和 Jewel 组件。完成后只有一套正式 UI，不保留运行时回退开关或旧界面分支。

不重写 Electron Desktop，不新增独立 Compose 应用或顶层窗口，不扩大 IDEA/远程开发/Split Mode 支持，不重绘 Project View、原生 Git Log 或 Commit UI，不把仓库加载选择、克隆、分支和目录删除职责搬进插件。IDE 必需的 Swing Tool Window 外壳不是历史兼容层。

以下产品边界保持不变：Desktop 是 workspace/加载配置及仓库生命周期的写入方；插件不访问仓库 URL、不执行 Git/语言工具链命令、不修改 VCS Directory Mappings；信任后仅更新可证明由 ReqWS 拥有的项目模型。冻结的旧所有权文件仍为惰性历史数据，禁止自动迁移或删除。依据见 [AGENTS.md](../../../AGENTS.md)。

## 2. 当前结构与改造范围

下表描述固定源码基线的现状，而不是已完成的改动；具体证据见[调研依据](research.md)。

| 当前对象 | 目标处理 |
|---|---|
| `ui/ReqwsToolWindowPanel.kt` | 替换布局、列表渲染、自绘、tooltip 和手工组件刷新；S4 删除旧内容实现。 |
| `ui/ReqwsToolWindowViewModel.kt` | 提取为纯 Mapper 和不可变 UiState，保留业务判断及测试，加入稳定仓库 ID。 |
| `ui/ReqwsToolWindowFactory.kt` | 保留平台入口、幂等初始化及动态启用恢复，改为创建 Compose 内容。 |
| `ui/ReqwsToolWindowAvailabilityController.kt` | 保留独立于内容创建的可用性控制；不移动到 Composable。 |
| `ui/LatestOnlyEdtDispatcher.kt` | UI 队列被替代且无其他消费者后删除；不得连带删除领域同步顺序保护。 |
| `project/TerminalStatePublisher.kt` | 保留有序通知、首次订阅快照和终态拒绝语义，不为换 UI 整体重写。 |
| `project/`、`sync/`、`manifest/`、`loading/`、`projectmodel/`、`vcs/` | 原则上保持；只允许展示接入确实需要的窄接口调整。 |

## 3. 宿主依赖和版本决策

生产插件使用目标 IDE 提供的 Compose/Jewel/Skiko/Kotlin 协程运行时。优先核对 `composeUI()`；若当前 Gradle 插件不能正确提供模块，再使用显式 `bundledModule`，二者只保留一种可复现配置。该 helper 是构建 API 的 incubating 标记，不等于允许生产代码调用 IntelliJ Experimental API。依据见[官方依赖说明](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html)。

Kotlin JVM 与 `org.jetbrains.kotlin.plugin.compose` 使用同一个 Kotlin 版本来源。当前仓库为 Kotlin 2.3.20、JVM 25、IntelliJ Platform Gradle Plugin 2.18.1；编译 GoLand 2026.2，代表 GUI 环境 2026.2.1.1。这些是 S0 的起点，不是尚未测试的 Compose 组合承诺。

在目标 SDK 的模块元数据中核对 Compose 依赖声明，例如 `com.intellij.modules.compose`；不能只改 Gradle 不改实际运行依赖。生产 ZIP 不携带另一份 Compose/Jewel/Skiko/stdlib/coroutines，不引入 standalone `compose.desktop.currentOs`、Material、Shadow 或自己的 JBR。独立 UI 测试可拥有匹配版本的测试运行时，但必须与生产 runtime classpath/ZIP 隔离。

S0 固定并记录 IDE product/version/build、JBR、Kotlin/compiler plugin、Gradle plugin 和实际解析模块，执行依赖树、编译、Verifier 与 ZIP 内容检查。当前 SDK 可满足则直接使用；确需新 SDK 时，不写旧版本分支，统一更新 `compatibility.properties`、Gradle 中的硬编码断言、descriptor、策略检查与相关文档。对实际声明的支持范围继续做自动 API/依赖验证，不新增最高版本真机矩阵，也不把声明无上限当作未来已全部验证。

Jewel 官方仍提示第三方 Compose 插件未获正式支持；“存在桥接 API”不等于“本插件已可发行”。该风险通过 G0 验证控制，而不是全局关闭 Internal/Experimental 检查。依据见[官方 Jewel 说明](https://github.com/JetBrains/intellij-community/blob/master/platform/jewel/README.md)。

## 4. 展示架构

数据流采用：`ProjectService → 有序状态订阅 → 纯 Mapper → StateFlow<ReqwsUiState> → ReqwsScreen → ReqwsUiAction → 平台适配层`。项目模型仍由原有领域服务管理，不由 UI flow 驱动写入。

建议包结构均位于 `com.reqws.goland.ui` 下：`state/` 放 UiState、Action 和 Mapper；`presentation/` 放 Presenter 与内容级交互反馈；`compose/` 放屏幕与组件；`platform/` 放 Compose 挂载、IDE 操作和 disposal 接入。先按包拆分，不默认新增多模块构建、Android ViewModel、导航或依赖注入框架。

界面契约为 `ReqwsScreen(state, onAction)`。Composable 不接收 `Project`，不定位 service，不读文件/VFS，不订阅消息总线，不执行模型写入。状态模型不持有 Swing/Jewel 对象、VirtualFile 或可变领域集合。集合在边界形成快照，不通过可变引用偷偷改变已发布 UI 状态。

`ReqwsUiState` 应包含工作区与分支、生命周期/语义色调、仓库列表、已确认加载数量、诊断/错误、各操作是否可用和短暂复制反馈。每行使用 `catalogRepositoryId` 对应的稳定身份，不能用名称或索引作 key；同名仓库、重排和删行都需测试。行选中仅是本地 UI 状态，不写入 Desktop 的加载选择。

纯 Mapper 必须保留这些区别：配置包含、选择加载、目录存在、项目投影确认和 Git Root 配置不是同一状态。`validatedProjectionDigest` 未证明当前投影时不能显示完整同步成功；保留 SAFE_MODE_BLOCKED、DEGRADED、ERROR、旧快照、用户 root 覆盖、未加载、目录缺失和 Git 诊断优先级。状态枚举与测试样本从当前源码提取，不凭新界面猜测。

`SyncNow`、`OpenManifest`、`CopyDiagnostics` 经平台适配器调用既有行为。UI 的 enabled 状态不是安全授权：处理动作时再次检查项目/内容是否销毁及当前允许条件。同步使用既有 coordinator，不因连续点击创建新的并发同步路线。复制基于一次一致状态快照；反馈不覆盖原错误详情，重复点击只重置自身提示状态。

## 5. 顺序、线程与生命周期

当前 publisher 已将初始值与后续通知串行化，并拒绝终态之后的新值。Presenter 可通过可取消的 listener/flow 适配接入，但不要先独立读取 `service.state` 再无保护地订阅，导致旧快照覆盖新状态。映射默认同步、轻量；引入异步映射时必须带序列身份并丢弃过期结果。StateFlow 的合并行为不等于自动提供领域顺序保证。

项目级 scope 继续拥有监听、同步和投影；内容级 scope 拥有 UI 订阅、复制反馈和屏幕资源。创建内容时订阅一次，dispose 时幂等关闭 listener、取消内容 scope 并释放内容持有的 Compose 资源。项目关闭/插件卸载还需取消项目级工作；关闭或重建内容不得取消项目 service。平台 scope 约束见[官方说明](https://plugins.jetbrains.com/docs/intellij/coroutine-scopes.html)。

Tool Window 隐藏不一定代表内容被销毁；两种事件分别测试。可见性控制保留在 Compose 外，未打开面板时同步也能运行。Factory 继续保留动态启用后的自动刷新入口。主题重组不得重新创建 service、监听器或同步任务。

优先使用目标 SDK 的公开 `addComposeTab`/Jewel 桥接入口；其具体重载、焦点处理、主题包装和销毁方式在 S0 核对。不引用桥接内部 wrapper，不复制全局渲染属性，不再包第二层互相冲突的主题。内容级 Disposable 必须落在真正的 Content 生命周期上；不能只假设 composable 的 `onDispose` 会覆盖所有卸载路径。

UI 纯计算与 IDE 模型操作分离：阻塞 IO 在后台，平台操作按相应 EDT、modality、read/write action 约束运行；不要将 `Dispatchers.Main` 当作访问 IDE 模型的通行证，不在重组中调用阻塞 API。禁止 `GlobalScope`，禁止吞掉取消异常，禁止把项目模型事务放进 UI `collectLatest` 任意取消。依据见[官方 dispatcher 说明](https://plugins.jetbrains.com/docs/intellij/coroutine-dispatchers.html)。

## 6. UI 行为与国际化

一个 Compose 内容根覆盖摘要、状态、仓库列表和固定操作区；不要在每行或按钮层面交替嵌套 Swing/Compose。优先使用目标 SDK 上可通过检查的 Jewel 组件与主题 token，不重新硬编码 IntelliJ 配色。

保留窄宽度可用性、状态图标与文字、列表滚动、键盘操作、完整文本获取和错误反馈。长名称用省略/受限宽度 tooltip 等明确行为处理；普通 `Text` 显示用户字符串，不添加 HTML/Markdown 解释器。可访问性描述必须表达角色、完整值、禁用和状态，不依赖颜色。语义节点可测不代表 VoiceOver 已验证。

继续使用现有 `ReqwsBundle` 中英文资源。不因 UI 重构机械翻译全部内容。确有文案/key/占位符变化时走 [reqws-i18n](../../../.agents/skills/reqws-i18n/SKILL.md) 的只读翻译与主代理校验写回流程。

## 7. 测试、清理与完成条件

测试以[测试方案](test-plan.md)的 CMP-V01–V12 为唯一详细覆盖清单。组件可独立运行不代表 IDE 集成已验证；真实 Driver 点击无法打通时 G0 不通过，不能用直接调用 action/service 冒充真实交互。

S4 删除旧 Panel、自绘组件、Swing-only 断言和无消费者的 UI dispatcher；保留或重写的是安全/业务行为测试，而非旧框架类型断言。清理同一提交内的调用方、测试和文档，不以重命名或 no-op adapter 保留死代码。回退使用 Git 历史与明确撤销提交，不自动删除用户数据，不长期保留第二套 UI。

不预先承诺性能提升。S0 在相同 fixture/环境记录 Swing 基线，S4 比较首次打开、空闲 CPU、开关后监听数、内存趋势与日志。无限动画、持续后台重组、重复监听、项目关闭后悬挂任务均为阻塞；数值预算在 S0 测量后冻结，不能在失败后放宽以获得通过。

只有 G4 通过才能声明重构完成。该结论不包含发布、安装日常 IDE、Marketplace 上架或其他产品/平台支持。
