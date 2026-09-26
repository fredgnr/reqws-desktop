---
title: Playwright V 替代验收记录
type: test-report
status: active
updated: 2026-09-27
---

# Playwright V 替代验收记录

本记录按旧断言核对真实候选、故障检测、稳定性和操作成本，只有证据齐全的范围才退出重复手工验收。

## 当前结论与候选

V 尚未完成，旧门槛未撤销。用户已明确允许固定 SDK 在异常/超时时将可能包含其他窗口的全屏诊断图仅保存在私有测试目录；这些图不上传、不作为通过证据。用户已授权测试、脚本和验收文档修改，必要本地提交，以及隔离 Electron、固定 GoLand 正负向验收；本轮不推送、不发布，不接触日常 IDE 或真实用户工作区。

正向插件沿用 `16ffce1` 的原始 CI `0.1.7` ZIP，来源为 [CI 36243622124](https://github.com/fredgnr/reqws-desktop/actions/runs/36243622124)。已重新读取七份原始 API 结果并执行严格汇总，确认属于同一 ZIP 和冻结矩阵。本机重建 ZIP 与它字节不同，不混用报告。最低仍为整个 `262` 系列、无上限；生产插件、SDK、API 例外和产品代码未因本轮测试扩展而改变。

集成前基线是 `a8a5541`。其 [CI 36246285479](https://github.com/fredgnr/reqws-desktop/actions/runs/36246285479) 的适用任务成功：Electron 18 项、两项故意启动失败的严格外层门禁、精确 ad-hoc 包 smoke、插件 baseline 和七目标 API。已下载真实包报告并复核一项测试、两个正常退出进程；实际 tested merge commit 为 `a185e50`。这些是原候选证据，不将新增 V 用例写成已经在该轮 CI 执行。

## 已完成的开发验证

| 检查 | 真实结果和边界 |
|---|---|
| 新增 Desktop 验收 | [acceptance.spec.ts](../../../tests/e2e/desktop/acceptance.spec.ts) 首次 12/12 通过，零跳过、零重试，约 60 秒；涵盖预占冲突、缺默认分支、三类 Missing/Sync/遗忘、设置兼容/默认目录/错误恢复及独立权限检查。 |
| 映射补漏 | English 再次冷启动、遗忘后删除 catalog 仍保留 clone，两项受影响用例 2/2 通过；D05 已补为真实 `2→3→2`，移除后独立核对 `.git`、HEAD 和用户文件。 |
| Desktop 全检 | `npm run check`：50 个文件、520 项通过、1 项既有 hosted-only 系统信任检查跳过；类型、lint、i18n 和当时文档检查通过。跳过不计入通过。 |
| 完整 workflow 回归 | `python3 -m unittest discover -s tests/workflows -p 'test_*.py' -v`：225/225 通过；复用已有 OpenSSL 3、Java 25 和固定 ZIP Signer 缓存，没有真实签名身份操作。 |
| 宿主/协议 | 原三场景之外新增 `desktopUserCoverageAndUnboundShell`；初轮 Java 21 自动寻找 Java 25 遇 Foojay TLS 失败，后使用已有 Java 25 编译成功，没有修改 TLS 或安装系统工具。G1/G6 的后续补证另通过 25 项 Python 检查及 Java 25 宿主编译，集成 Vitest 包含新增协议拒绝用例；真实 GUI 仍待下节验收。 |

冻结 `4cd76ae` 的全套 source 30/30、两项精确负向启动门禁和核心 smoke 20 次共 80 项通过。D05 补齐后，干净 `c2013ad` 的全套 30 项各执行两次，共 60/60，零跳过、零重试、零 flaky，约 304 秒。G1/G6 补证宿主冻结为 `41b3db2`；其普通 Desktop 输入仅比 `c2013ad` 多一项 English 即时三页导航断言，该 D02 又独立重复 20 次，全部通过。其余差异限本机联动协议和宿主，不把早期报告改写成新提交的实跑。

`npm run check:goland` 完整退出零：结构、产物和受控 API 例外门禁通过；374 项 baseline 的有效报告由 `UP-TO-DATE` 任务复用，不能写成 374 项本轮新执行。最低/固定两目标和完整七目标产生新报告并严格汇总通过，证据根标识 `reqws-api-_gzykk73`。这轮检查消费本机 build ZIP，与正向 GUI 所用原始 CI ZIP 分别核对，绝不混借。

选择器以 Playwright 实际完整标题匹配，不在 `D01` 等表达式前加 `^`：完整测试名包含文件前缀。首个 manifest 实验因此匹配到零测试，被入口拒绝且未启动 Electron；保留失败后修正选择器，未把零测试作为通过。

## 故意失败的实际证据

实验在独立 checkout、自有临时 fixture 和私有报告目录执行。正向原始 ZIP 不被替换；变异源码在实验结束后精确恢复，变异 ZIP 明确标为仅用于负向验证。

| 变异 | 观察到的失败 |
|---|---|
| manifest 原子写失败 | 外置测试 Main 使用已有单次 OS 写失败边界；原 D04 因工作区未成功创建而失败。 |
| 错误删除已发布工件 | 隔离副本临时改变失败清理分支；原 D07 的独立磁盘断言因 manifest 缺失而失败。 |
| 逻辑移除错误删除仓库 | 隔离副本故意删除 D05 自有仓库，原测试在 `.git` 独立磁盘断言处因 `ENOENT` 失败。首次注入漏 `await`，只触发业务错误，保留且不计为目标检出；修正注入后准确检出，源码精确恢复，随后两轮完整正向均通过。 |
| 禁用 watcher 同步投递 | 独立测试 ZIP 保留初始加载、截断真实文件事件到同步管线的投递。旧 S4 宿主中 revision 1/2 曾收敛，revision 3 空集合等待超时；非法输入恢复也超时，完整入口返回失败。不能假定断 watcher 后第一步必然失败。 |

两项 Desktop 变异各执行一个原有测试，均准确失败；恢复原源码后同一 D04/D07 对照 2/2 通过。失败 trace 已读回真实 DOM/screenshot 事件，Electron 正常退出。watcher 实验三个 IDE 进程全部退出，Desktop 退出确认和 profile 释放均存在；它不是正向候选的通过报告。

## 增强的本机验收契约

新增宿主继续通过 `check:goland:desktop`，正向业务输入仍由真实 Desktop UI 写入。第三仓、notes、shell probe 和 late 文件都真实存在；Driver 检查普通 Project 树、模型、PFI、用户覆盖说明和只读 VCS 观察。Excluded Files 两态实际切换并恢复，额外用户 root 放在已存在的承载 module 内且没有 claim/marker；项目关闭重开与完整进程重启分开记录。

G4 的只读审查发现，仅在空集合下加入额外 root，再恢复两仓，不能证明清空时保留该 root。因此新增“额外 root 已存在时，由 Desktop 执行 `2→0`”的过程证明；同 PID 重开后完整退出，新 PID 先只读恢复这个实际清空结果，再恢复两仓并再次冷启动，不用离线构造空输入代替保存链覆盖。

当前新报告使用 `acceptanceVersion=5`，要求四项 JUnit、九个独立 IDE 进程、32 条投影、五份原生落盘证明、37 张独立 IDE Swing 内容图、五个 Desktop 工作区和 16 个请求。五个工作区初建必须由真实 Desktop“保存并打开 GoLand”走 Main/EditorLauncher 到最终 OS spawn 边界，Starter 使用该次观测的合法 shell 参数；只有最后 OS spawn 由隔离 adapter 记录，不证明系统 LaunchServices。四次非法输入必须实际读取 ReqWS 面板中的 Error 和稳定码，并保存独立原始 UI 记录及截图，不能只检查服务状态。`verify-report` 重新读取 JUnit、进程、逐步投影、原生文件及协议记录；PNG 检查完整 chunk/CRC、结束块和有界解压，不接受只有正确文件头的截断图。旧 S4 的 20 条投影报告仍是历史证据，不满足新增契约。G1/G6 的独立只读审查和 25 项直接 Python 检查已通过；后续 v4 采集/清理修复的直接检查见下节。完整正向实跑、重复运行及最终复核尚待完成。

### 首轮 GUI 证据拒绝

`41b3db2` 的首轮增强运行 `reqws-local-ide-pzouh2_a` 在实际查看图像时发现，Driver `takeScreenshot` 保存的是整个显示器，图像不保证属于测试 IDE。已立即通过会话 abort 停止；该轮没有 V 正向结论，不把模型/树的局部成功补写成完整通过。原始资料仅保留在私有目录，不上传或作为 IDE 图像展示。

外部 abort 还暴露 coordinator 的清理缺陷：finally 重复发布相同 abort 文件抛 `FileExistsError`，中断尾部清理确认。两个已启动 IDE PID 均已退出，Desktop 登记进程及带该 run root 的命令均不存在；主 Agent 复核原 registry、失败日志和精确 active marker 后释放专用 profile，另存操作收尾记录，原报告仍为 failed。后续须修正限定 IDE 内容的采集和取消清理，再用新宿主、新目录重新执行；不得复用该轮图像。

### 可审查的采集/清理修复与运行条件

组件采集仅使用公开 Driver 和标准 JDK API，在实际 IDE JVM 的 EDT 上将当前 `JFrame` 的真实 Swing root pane 通过 `printAll` 渲染为 PNG；不读取显示器、不裁剪全屏，也不根据状态重建界面。逐图侧车绑定采集方式、规范项目路径、frame 项目/标题、实际远程 JVM PID 和解码尺寸，v4 严格门禁继续校验完整 PNG 并拒绝旧 v3/无侧车证据。它不证明 macOS 窗口装饰、焦点或遮挡。`bb01aa5` 新轮首张 1400×918 内容图已实际查看，清楚呈现普通 Project 层级及 ReqWS 状态，项目/PID/尺寸侧车一致；图像修复已实跑，整套验收仍须分别判定。

相同 session 的合法 abort 标记可幂等读取；标记写入失败仍逐个处理宿主/Desktop 子进程和原有 PID/签名登记，任何清理未确认都会保留失败，不发布成功。正常退出的 SDK 截图已通过公开 `takeScreenshot=false` 关闭，周期全屏抓图通过最终 VM patch 清除 `ide.performance.screenshot`。这不屏蔽 IDE 异常或超时门禁。

固定 Starter/Driver `262.9437.185` 的缓存字节码已由独立只读调查确认两项剩余限制：`DriverWithDetailedLogging.withContext` 遇异常无条件生成 `driverError` 全屏图；`IDERunContext.captureDiagnosticOnKill` 在真实本机 GUI 模式下调用全屏 Robot helper，早于 `expectedKill`/`collectNativeThreads` 条件。公开入口没有找到可保留真实 GUI 且关闭这两种诊断的设置。不能宣称全部 SDK 截图已关闭，不能用 headless 或替换 SDK 来伪装该验收。

修复已通过 22 项 Desktop workflow、10 项 launcher 回归、Java 25 宿主编译及独立只读复审；随后主 Agent 完整 workflow 回归 223/223 通过，文档检查通过。初轮编译的 Driver 调用签名错误保留在日志，修正后实际执行编译通过；未启动新 GUI、未修改生产插件或最低 262。用户已明确确认私有诊断采集条件，后续按预定两轮正向、legacy 和独立 watcher 负向继续；SDK 诊断全屏图不能用作 IDE 通过证据。

### 组件实跑后的宿主问题定位

`bb01aa5` 的 `reqws-local-ide-fsamuaxl` 四项全部执行，两项通过、两项失败，五个 IDE 进程退出，Desktop 闭环确认和 profile 释放正常。组件图有效；选择场景在 late-file VFS 等待失败，用户覆盖场景则错误地把 JList 组合绘制文字当作单个组件精确匹配。实际 UI hierarchy 已有 `Included via User Project Root` 和 repo-c，修正为在真实列表中读取绘制文字，并保留模型/PFI/树检查。

随后 `104a8cf` 的一次有界诊断 `reqws-local-ide-9e28axs1` 仍为两项通过、两项失败，六个 IDE 进程全部退出。用户 root 的三次投影已通过，普通无绑定项目的记录器漏了 PFI 访问所需 read action，已定位为测试宿主调用问题。late 诊断分别读取两路径：磁盘两文件始终存在，shell 文件约一秒进入 VFS，repo 文件在 60 秒内仍未进入；frame 始终 focused/active。两轮原失败均保留，不计为稳定通过。

固定 SDK 源码/字节码显示 native watcher 周期任务主要把路径标为 dirty；实际刷新由空闲 debounce、平台 configuration 和 applicationActivated 等普通机制触发，不能把持续前台的读取轮询当作刷新保证。G3 将还原外部编辑后的观察流程：仅一次切换到自有 Desktop 窗口，确认测试 IDE inactive 后创建两个普通文件，再通过公开 Driver 返回同一 IDE，记录真实激活。保留原 60 秒、VFS/PFI/树门禁；该动作不进入 G2 的任何 revision 转换，也不调用 Refresh/Sync。新增受限 `focus-external-edit` 协议和三段原始证据，版本升为 v5、请求增加为 16，其余门禁计数不变。34 项直接 Python 检查、8 项协议 Vitest、TypeScript/lint、宿主编译及独立复审通过；随后完整集成检查为 Vitest 520 项通过、1 项既有跳过及 workflow 225/225。后续新冻结候选仍待实跑。

## 预先固定的成本样本

本轮先固定 D04 双仓创建和 D08/G2 `2→1→0→2` 两个普通回归样本，再执行 Computer Use；没有用测试条数代替操作成本，也不把签名、安装、自更新等不同范围放入分母。

| 样本 | 本次测得的 Computer Use 操作 |
|---|---|
| D04 双仓创建 | 19 个界面动作、13 次交互调用，约 90 秒；另外三次窗口发现/绑定调用单列，其中一次 bundle ID 歧义被拒绝后改用确切测试应用路径。保留原 D04 的独立 Git/磁盘断言并通过。 |
| D08/G2 选择与树观察 | 7 个点击动作、9 次操作/观察调用，约 90 秒；另一次初始绑定单列。实际观察一仓、空集合和恢复两仓，未点 Refresh/Sync Now；原宿主三项/六进程完整通过。树展开由保留的 Driver 执行，因此本样本不测量人工展开树的成本。 |

两项均无用户协助、无操作重试。D04 自动 smoke 20 次的测试耗时中位数约 6.0 秒、最近秩 P95 约 7.0 秒；该时间包括自动独立结果断言，但不包含整轮构建，不能与一次手工样本推导长期 P95 改善。自动流程的界面操作不使用 Computer Use。选择样本的临时宿主冻结为 `cf099dd`，仅测试端等待实际 Computer Use 操作并保留原有模型/树/磁盘断言；其三项/六进程报告只作成本样本，不充当新的四项 V 验收。测量代码已恢复。当前样本不足以推导全套回归成本减少 80% 或长期可靠性；新宿主的 G2 自动计时和完整 IDE 重复运行仍待补齐。

## 证据与保留范围

本轮私有证据根为 `/private/tmp/reqws-v-acceptance-gjnhqoyu`，包含候选核对、原始 CI 报告、各次失败/对照、成本测量代码和计数。产物计算摘要只在私有测试证据中保留，不写入仓库文档。

固定代表仍是 GoLand 2026.2.1.1 / GO-262.9437.286；Trust 场景继续沿用 S4 对可选 Go Linter 的单 context 限定，完整 IDE 不进入 CI。翻译复核、真实系统语言/剪贴板、原生 picker/Finder/外部编辑器、签名/安装/系统信任及真实两版本升级不由本轮边界 adapter 证明。

逐项替代决定以[旧步骤登记](manual-inventory.md)为准；在完整新候选、负向、稳定性和独立复核关闭前，不更新 AGENTS 或指南来撤销既有门槛。
