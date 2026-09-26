---
title: Playwright 迁移的旧验收步骤与替代登记
type: test-plan
status: active
updated: 2026-09-27
---

# Playwright 迁移的旧验收步骤与替代登记

本文按旧验收断言记录经过 V 验证的自动化替代范围，以及继续保留的原生和低层门禁。

## 1. 登记口径与当前结论

映射遵循[技术方案第 11 节](technical-design.md#11-手工用例替代登记)。D01–D12 是[场景编号](technical-design.md#5-首批用例范围)，S4 是本机 Desktop→GoLand 联动阶段；实际候选、执行、故意失败和修复后的独立重复见 [V 记录](verification-v-2026-09-26.md)。本轮按范围撤销已经证明等价的重复手工操作，原需求与历史结果不被改写。

S0–S3 已有下表源码入口；本机候选的实际执行数、失败实验和稳定性统一见[实施记录](implementation-2026-09-26.md)。选择器为 `npm run test:e2e -- --grep '<表达式>'`，完整测试标题和行号同时保存在每次 runner 的 JSON 报告中。文件存在不构成 V 替代验收。

| 场景 | 实际测试文件 | 选择器 |
|---|---|---|
| D01 / S1 启动与退出 | [startup.spec.ts](../../../tests/e2e/desktop/startup.spec.ts) | `D01` / `S1` |
| D02 冷进程持久化 | [settings.spec.ts](../../../tests/e2e/desktop/settings.spec.ts) | `D02` |
| D03 仓库配置与 HTTPS | [repositories.spec.ts](../../../tests/e2e/desktop/repositories.spec.ts) | `D03` |
| D04–D07 创建、成员、发布前后失败 | [workspaces.spec.ts](../../../tests/e2e/desktop/workspaces.spec.ts) | `D0[4-7]` |
| D08–D09 Desktop 加载选择与冲突 | [goland-selection.spec.ts](../../../tests/e2e/desktop/goland-selection.spec.ts) | `D0[89]` |
| D10–D12 OS 边界、安全与更新门禁 | [native-boundaries.spec.ts](../../../tests/e2e/desktop/native-boundaries.spec.ts) | `D1[0-2]` |
| V 旧步骤补漏 | [acceptance.spec.ts](../../../tests/e2e/desktop/acceptance.spec.ts) | 直接传入该文件，实际 12 个完整标题保存在报告中。 |

Playwright 完整名称带文件前缀，以上表达式不加开头锚点。每轮原始 JSON 的 `selectors` 列表保存文件、行号和完整实际标题；不得用宽泛场景编号冒充一次未执行的具体用例。

启动故障由独立 `npm run test:e2e:negative` 执行[断 preload](../../../tests/e2e/probes/disconnected-preload.spec.ts)和[早期 renderer 错误](../../../tests/e2e/probes/startup-error.spec.ts)；外层 runner 必须验证准确的失败原因和完整新鲜证据才成功。这些探针不作为普通套件的 expected-failure 注解。

S4 加入 [Desktop 联动 spec](../../../tests/e2e/local-ide/desktop-link.spec.ts)与 [Driver 场景](../../../integrations/goland/src/integrationTest/kotlin/com/reqws/goland/DesktopWorkspaceIntegrationTest.kt)，入口为 `check:goland:desktop`。历史三个选择器是 `desktopSelectionAndColdProcesses`、`desktopTrustTransitionUsesRealUi`、`desktopInvalidInputsPreserveUserModel`，原 S4 结果与 Trust 环境限定见 [S4 记录](implementation-2026-09-26.md#7-修复后的同候选验证)。V 新增 `desktopUserCoverageAndUnboundShell`，并补齐 G3 两态/late 文件、G4 同进程重开和额外 root、G5 用户覆盖、G6 Error UI；v7 四项套件已在同一原始 CI ZIP 上完成两轮独立正向及严格重读，不能用旧 S4 报告替代这些新证据。

以下“本轮裁决”只作用于明确列出的断言。“替代”撤销该业务范围的重复 Computer Use，“部分替代”保留列出的原生或未覆盖范围；“保留”继续原门禁。CI、翻译复核和低层安全测试不计作新增替代。历史 MVP 报告为 archived，仅用于追溯旧步骤；执行以当前有效设计、标准和授权为准，不恢复已被语言解耦规范移除的 Go SDK、搜索或原生 Git 全套验收。

旧来源没有统一编号时，本文提供定位别名：`MVP-01` 对应原 smoke 表第 1 行，`GS-01` 对应设置设计 §17 第 1 条；`G1`–`G7`、`U1`–`U9` 沿用原编号。别名不是新增加的验收要求。安全列表没有原 ID，使用“来源章节＋断言名称”定位。

裁决依据按实际输入分列：普通 Desktop 的 `c2013ad` 全套 30 项重复两次共 60/60，之后 D02 的 `41b3db2` 与 D01 Buffer 补充的 `89e7615` 分别独立重复 20 次；Git diff 确认其余普通 Desktop 输入未变。IDE 宿主 `e7a25f2` 对 `16ffce1` 原始 CI `0.1.7` ZIP 的两轮各四项/九进程通过，另有 legacy 三项/九进程通过和同宿主 watcher 负向准确失败。preload、manifest 写入、错误删除发布工件及逻辑移除错误删除仓库也有实际故意失败。完整标题、计数、源码/包身份、首次失败与独立重验入口集中在 [V 记录](verification-v-2026-09-26.md)及其私有原始证据，未把旧报告改写成新提交的实跑。旧 S3 的 CI ad-hoc 包是单独的精确产物证据，不能证明新的发布包。

## 2. MVP macOS smoke

来源：[2026-08-13 验证记录的 macOS 目标机 smoke 表](../mvp/testing/verification-2026-08-13.md#macos-目标机-smoke-结果)。下表保留当时断言，不把历史成功转记为本轮结果。

| 旧步骤 ID | 原断言 | 拟映射 | 真实性差异、负向验证和保留范围 | 本轮裁决 |
|---|---|---|---|---|
| MVP-01 | 安装依赖、完整检查、开发启动成功，主界面出现。 | D01；S3 同包 smoke | built Electron 验证真实 Main/preload/renderer；仍保留依赖/质量门禁。开发启动不能证明候选 `.app` 可用；启动超时、资源缺失应失败并留日志。 | 部分替代：真实 source 启动/bridge 及旧 S3 实测 CI ad-hoc 包启动；依赖、质量和精确新发布包门禁继续。 |
| MVP-02 | 第二实例退出，第一实例保持唯一并被聚焦。 | D01 的实例专项 | 须启动两个共享同一测试 userData 的真实进程；不同 userData 可并存。mock lock 仅是单元证据；焦点无法自动判定时保留有限原生观察。 | 部分替代：共享 userData 的第二实例退出与锁；第一窗口实际聚焦仍按原生风险补验。 |
| MVP-03 | hidden-inset 窗口按钮正常；renderer 无 require/process/Buffer，真实 reqws bridge 可用。 | D01、D11 | 必须显式启用并检查真实 Chromium sandbox；断开 preload 必须失败。窗口按钮位置与原生焦点的视觉结论另验，不能由 BrowserWindow 参数替代。 | 部分替代：真实 sandbox/隔离/bridge 及 require/process/Buffer/ipcRenderer 不可见；原生按钮与焦点保留。 |
| MVP-04 | SSH Agent 与公开 HTTPS 可连接并识别默认分支；私有 HTTPS helper 认证当时未验证。 | D03、D04 | loopback HTTPS 只证明隔离传输和真实 Git 路径，不证明 SSH Agent、系统 Keychain 或私有 HTTPS helper；历史未验证项继续保留，不能记为迁移后通过。 | 部分替代：隔离 HTTPS 与真实 Git；SSH Agent、公网、Keychain 和私有 helper 不在证据内。 |
| MVP-05 | Catalog 增删改；连接失败仍可保存，编辑后可连接；删除配置保留本地 clone。 | D03 | UI 走真实 schema/state，独立读盘；传输失败由 Git origin 层制造。原生确认对话框若被替换，只证明确认结果后的业务语义。 | 替代 Catalog CRUD、连接失败可保存/恢复及删除配置保留 clone 的业务步骤；原生确认边界另论。 |
| MVP-06 | 双仓创建时分别选择代码父目录和 workspace 文件目录，最终 Ready。 | D04 | dialog 边界可替换；原生目录选择器外观和交互不在替换证据内。必须独立读实际输出路径、manifest 和索引。 | 部分替代：两类独立目录和实际产物/Ready；原生 picker 操作保留。 |
| MVP-07 | 两份独立 `.git`；已有 feature 跟踪远端，不存在时从默认分支创建；无 push。 | D04 | 使用真实 HTTPS origin 和 Git；从测试进程检查分支、`.git` 与远端 refs 前后状态，不能只信 UI Ready 或应用 get API。 | 替代：实际独立 Git、分支/upstream 与远端 refs 不变；不是模拟 Git 结果。 |
| MVP-08 | 预置 root/`.code-workspace` 拒绝覆盖，sentinel 内容不变。 | D04、D06 的冲突专项 | 两类冲突分别制造并检查内容保留；路径/symlink/竞态的完整组合保留低层安全测试，不用单次 UI 成功替代。 | 替代两类预占冲突的 UI 与 sentinel 检查；路径/竞态组合低层门禁继续。 |
| MVP-09 | 默认分支缺失时失败，无半成品索引、正式 root/workspace 文件或残余 staging。 | D06 | 使用真实缺分支 origin，明确故障发生于发布前。不能把 D07 的发布后工件也清理掉以满足本断言。 | 替代：真实缺默认分支故障及发布前磁盘清理；保留 D06/D07 的区别。 |
| MVP-10 | Ready workspace 增加第三仓后 manifest/workspace 更新；逻辑移除后目录和 `.git` 保留。 | D05 | 独立检查成员、文件和保留目录；故意破坏保留语义必须被测试拒绝。 | 替代：真实 2→3→2、Git/用户文件保留；错误删除的故意失败已检出。 |
| MVP-11 | 移走文件/manifest/root 呈现 Missing/Error；恢复后 Sync 回 Ready；遗忘只删索引。 | D05、D07 的恢复/遗忘扩展 | 现有 D 编号不自动包含完整 Missing/Error 矩阵，须补精确 selector 后逐项核对；移动与恢复仅限自建 fixture，磁盘保留独立断言。 | 替代三类 Missing/Error→Sync→Ready 与遗忘只删索引；只移动自建 fixture。 |
| MVP-12 | VS Code/Cursor 打开正确 workspace；Cursor root 与 Finder 定位正确。 | D10 | OS spawn 记录只能证明命令/参数/错误反馈；不能证明真实编辑器加载或 Finder 选中。原生集成改变时保留精确应用观察；未安装分支不能借历史已安装环境证明。 | 部分替代：VS Code OS 参数/错误语义；Cursor、Finder 及真实应用打开效果仍保留。 |
| MVP-13 | Cmd+Q 后重新启动恢复 catalog/workspace，创建表单恢复目录默认值。 | D02、D04 | 必须确认原进程退出后启动新 PID，保留该测试自己的数据；reload 或关 macOS 窗口不足。断开持久化应产生失败。 | 部分替代：进程实际退出、新 PID 持久化和目录默认值；Cmd+Q 原生键绑定另验。 |
| MVP-14 | state 目录 0700、文件 0600、schemaVersion 1；无秘密字段或含凭据 URL。 | D02、D03、D11 辅助断言 | 由测试进程检查自有临时文件权限与内容；保留 URL/日志脱敏低层负向测试，不读取真实用户 state 或凭据。 | 替代代表 state 的权限、schema 和无秘密字段断言；完整 URL/日志安全组合继续。 |

MVP 报告另有[本机安装脚手架验证](../mvp/testing/verification-2026-08-13.md#本机安装脚手架验证)：签名、LaunchServices、真实安装位置、运行中保护和事务恢复不因 D01 或源码 E2E 通过而移除。S3 同包 smoke 只能覆盖其实际执行的候选启动范围；Finder、Gatekeeper、quarantine 与正式签名验收继续按风险和原计划执行。

## 3. 设置与语言验收

来源：[全局设置技术方案 §17](../global-settings/technical-design.md#17-手工验收)。翻译工作流使用当前项目技能契约；本文不沿用旧文档中的历史模型指定，也不修改文案。

| 旧步骤 ID | 原断言 | 拟映射 | 真实性差异、负向验证和保留范围 | 本轮裁决 |
|---|---|---|---|---|
| GS-01 | 顶层导航出现设置。 | D02 | 真实 renderer 角色/名称定位；缺少入口必须失败。 | 替代设置导航入口的重复手工检查。 |
| GS-02 | 点击进入独立设置页。 | D02 | 通过 UI 导航，不直接切内部组件状态。 | 替代真实 UI 进入设置页的重复手工检查。 |
| GS-03 | 首次默认跟随 macOS 系统语言。 | D02 | 固定语言 provider 可证明选择算法；真实 OS 语言获取仍由 Electron 边界/必要原生检查补证。 | 部分替代固定 provider 的语言选择；真实 macOS 语言获取保留。 |
| GS-04 | 保存 English 后全部 React 页面立即切换。 | D02，并联 D03–D10 页面 | 仅设置页截图不证明全部页面；逐页关键文案覆盖和 catalog 一致性检查都保留。 | 替代三顶层页面关键文案的即时 English 切换；catalog 一致性检查继续。 |
| GS-05 | 重启后仍为 English。 | D02 | 完整进程退出/新 PID 和真实 state，禁止 reload 代替。 | 替代实际冷进程后的 English 持久化。 |
| GS-06 | 切回跟随系统立即应用系统语言。 | D02 | 固定 provider 下验证切换；不把 stub 输出当作本机系统设置实测。 | 部分替代固定 provider 下 Follow system 切换；真实 OS 边界保留。 |
| GS-07 | 工作区父目录默认预填。 | D02、D04 | 设置经真实 state 保存，创建表单经 UI 打开并检查。 | 替代工作区父目录的保存和 UI 默认值。 |
| GS-08 | `.code-workspace` 默认目录预填。 | D02、D04 | 独立验证两种目录，不能用同一路径掩盖字段串用。 | 替代独立 workspace 文件目录的保存和 UI 默认值。 |
| GS-09 | 创建表单单次修改路径不改变全局设置。 | D02、D04 | 检查本次产物路径和全局 state，重新打开表单核对。 | 替代本次路径覆盖、实际输出和全局设置不变。 |
| GS-10 | 旧版 state 可加载。 | D02 的兼容专项 | 仅使用受控旧 schema fixture；保留已有迁移/缺字段测试，不能只运行空 state。 | 替代受控旧 schema-v1 业务数据加载；既有迁移/缺字段测试继续。 |
| GS-11 | 保存设置不影响仓库和工作区。 | D02、D03、D04 | 先有业务数据，再保存设置并独立比较受保护字段。 | 替代已有 catalog/workspace 下保存设置的保护检查。 |
| GS-12 | 非法/已删除目录不使应用崩溃。 | D02 | 制造失效临时路径，检查错误和后续可操作性；只断言进程存活不足。 | 替代失效临时路径的错误与后续恢复操作。 |
| GS-13 | 新中文源文案有经复核的英文翻译且一致。 | 保留 i18n 工作流；D02 仅显示检查 | 翻译复核不是 UI 测试可替代的手工步骤，不计入 UI 自动化替代数量。 | 保留翻译技能的独立复核；不是 UI 替代项。 |
| GS-14 | i18n:check 和现有 CI 全通过。 | 保留原 CI；S3 聚合 | 本项原本是自动门禁，不计作新增替代。新增 job 不得掩盖失败、取消、零测试或全 skip。 | 保留原有 i18n/CI 自动门禁；不计作新增节省。 |
| GS-15 | 列表/详情刷新失败 Toast 同时显示稳定码和本地化消息。 | D03、D05、D07 的错误扩展 | 在实际失败层注入代表故障；中英文与错误码分别断言，不用成功页面截图替代。 | 替代两语言列表/详情故障的稳定码与 Toast 检查。 |
| GS-16 | 设置保存失败可查看/复制码、阶段和技术诊断。 | D02 的保存失败扩展 | 单次写失败须走真实错误映射；复制行为需有实际剪贴板或明确边界证据。 | 部分替代真实保存错误、码/技术诊断和复制文本到边界；系统剪贴板保留。当前 Settings 错误没有 stage，原阶段展示断言未关闭。 |
| GS-17 | 缺失路径列出具体工件并跟随语言切换。 | D05、D07 的 Missing 扩展 | root/manifest/workspace 文件分别核对；不能只检查通用 Missing 标记。 | 替代 root/manifest/workspace-file 三类缺失工件、双语和恢复检查。 |
| GS-18 | 创建失败进度区分清理未发布 staging 与保留已发布工件。 | D06、D07 | 各阶段分别制造失败，并独立读盘证明清理或保留；两种场景不能合并为一个“回滚成功”。 | 分层替代：E2E 证明发布前清理/发布后保留和失败反馈；两种瞬时进度文案由 renderer/preload/service 测试保留，未声称都被 E2E 实时看见。 |
| GS-19 | 已保存目录失效时显示字段警告；选择替代目录后警告消失、全局设置不变。 | D02、D04 | 真实临时目录失效；dialog 替身只证明返回值处理，原生 picker 交互仍不在范围内。 | 部分替代两字段警告、替代路径和全局设置不变；原生 picker 操作保留。 |

## 4. GoLand 加载集合与原生 Project 树

来源：[GoLand 加载集合测试计划 G1–G7](../goland-workspace-loading/test-plan.md#4-必要-gui-用例)。最低版本影响：本清单不修改代码/API/描述符，仍为整个 `262` 系列，无上限；固定原生代表环境仍为 GoLand 2026.2.1.1。高版本采用自动 API 检查，CI 不启动完整 IDE、不要求 IDE 授权，依据[兼容与自动化方案](../ide-plugin-compatibility-automation/README.md)。

[2026-09-22 本机记录](../ide-plugin-compatibility-automation/local-verification-2026-09-22.md)属于旧候选，不作为下表的 V 关闭证据。下表使用新 v7 原始报告，并继续要求显式 ZIP、专用 profile 和同一候选证据。额外用户 root 由自有保存模型夹具构造；实测的是插件保护、真实投影及恢复，不声称操作过 Project Structure UI。

| 旧步骤 ID | 原断言 | 拟映射 | 真实性差异、负向验证和保留范围 | 本轮裁决 |
|---|---|---|---|---|
| G1 | Desktop 保存 repo1/2，准备唯一合法入口并打开；Project 展示二仓普通文件，repo3/notes/shell 不展示。 | D08、D10＋S4 | D08 只证明 Desktop binding/selection；S4 必须观察真实普通 Project 父子节点。预写选择文件、只看 Synced 或模拟 OS launch 均不足。 | 部分替代 Desktop UI→Main/EditorLauncher→合法 shell→真实 IDE Project；最终 LaunchServices/原生 open 仍保留。 |
| G2 | 仅经 Desktop 执行 2→1→0→2，自动同步；仓库仍在磁盘，成员/分支/其他编辑器文件不变。 | D08＋S4 | 不点 Sync Now；分别证明 Project、模型/PFI 与磁盘状态。破坏 watcher 后联动必须失败，不能以手工刷新补救。 | 替代：同 v7 两轮实时选择、模型/PFI/树/磁盘通过；当前宿主准确拒绝 watcher 负向 ZIP，未 Refresh/Sync 补救。 |
| G3 | Excluded Files 开关两态 shell 均隐藏；运行中新仓库文件出现，真实 late-shell 文件仍隐藏。 | S4 | 保留真实文件与开关恢复；不得用搜索、API 数量或移除 probe 代替树观察。只有 D08 选择文件测试不能替代此项。 | 替代已测外部编辑→返回同 IDE 的 VFS/PFI/普通树、Excluded 两态及恢复；不承诺持续前台轮询发现。 |
| G4 | 用户额外 root 在显式空集合下保留；项目重开与完整冷进程均恢复；随后二仓恢复，用户配置不变。 | D08＋S4 | 项目重开和进程重启分别记证；不得停止日常 IDE。须新进程先只读观察，不用 reapply 制造恢复结果。 | 替代保护/恢复断言：保存模型夹具构造的额外 root 经 2→0、同 PID 重开、新 PID 先只读空集合及非空恢复；不声称操作过 Project Structure UI。 |
| G5 | 未受管 repo3 被用户 root 覆盖时仍可见，插件解释归属且不删用户条目。 | S4 | 用户配置夹具不等于受管加载操作；实际 Project 观察与 ownership 模型断言同时保留。 | 替代：实际 repo-c 用户覆盖、归属说明及不接管的三态证明。 |
| G6 | 错误绑定/选择不被当空集合删除既有 roots；恢复后自动重核；无绑定项目同名 shell 正常可见且不受管。 | D09＋S4 | D09 revision/保存失败不包含插件读取错误全部语义；S4 独立检查错误恢复和普通项目，平台安全测试继续保留。 | 替代四次实际 Error UI/稳定码、模型保留/自动恢复及独立无绑定普通项目；平台安全组合继续。 |
| G7 | 只读观察 VCS mappings；恢复显示选项，保留夹具和普通文件。 | S4 收尾 | 不修改 Directory Mappings，不新增 Git Log/Commit 卸载或原生 Git 验收。历史手工现场保留要求不授权自动删除；仅按新 fixture 的明确所有权清理自建临时资源。 | 替代本轮只读 VCS/显示选项恢复和文件保留；不扩展原生 Git、也不改 mappings。 |

## 5. 更新、打包与安全边界

来源：[自更新技术方案 U1–U9](../macos-self-update/technical-design.md#9-最小验证计划与发布门禁)。这些条目同时包含原有自动门禁与原生验收，不全部计作“旧手工步骤”。当前正式发布缺口以[需求包状态](../macos-self-update/README.md)为准，不从隔离 PoC 或历史签名结果推导本轮 GO。

| 原 ID | 原断言 | 拟映射 | 真实性差异、负向验证和保留范围 | 本轮裁决 |
|---|---|---|---|---|
| U1 | profile 禁用、状态转换、重复请求、错误映射、订阅清理和 main 输入边界正确。 | D12、D11＋保留低层测试 | 真 UpdateService 配边界 adapter，不换整条服务或 preload；任意 URL/路径与额外 IPC 参数仍须拒绝。 | 部分替代真实 service/preload/UI 代表状态；完整输入、订阅和错误组合继续由低层覆盖。 |
| U2 | Git/workspace/state 活动阻止安装；安装准入后拒绝新操作，失败释放。 | D12 | 必须共享真实 activity gate/coordinator，异步子进程未 close 仍占用；仅 mock busy 返回值不足。并发边界继续由低层测试穷举。 | 部分替代真实共享 activity gate 的 UI 安装准入；并发/失败释放完整低层回归继续。 |
| U3 | 精确 `.app` 离线可用、依赖完整、userData 稳定；local 不需证书且更新禁用。 | S3 同包 smoke、D01、D12 | 源码 E2E 不证明打包依赖；只消费同次候选包，区分 ad-hoc 与 personal-release，不在本机真实 userData 下盲目启动。 | 部分替代旧实际 CI 候选 ad-hoc 启动/资源/存储重启；不证明新包、断网、personal-release 或安装信任。 |
| U4 | 元数据、版本、大小和资产集合一致，错误输入拒绝，draft 先验证。 | 保留发布自动门禁；S3 辅助 | D12 的模拟下载不能代替 Release 字节/资产核对；不计作 UI 替代，也不授权发布。 | 保留 Release 元数据/资产/draft 自动门禁；不计 UI 替代。 |
| U5 | 缺失/错误签名身份和 ad-hoc 回退阻塞发布；嵌套签名有效，新版满足旧版 DR。 | 保留签名静态门禁 | 原生签名/同名错误证书负向不可由 Playwright adapter 证明，不改 fuse 或签名配置以便测试连接。 | 保留签名及原生负向门禁；不以 adapter 代替证书/DR 验证。 |
| U6 | 真实 macOS R1→R2 检查、下载、安装、重启，版本和业务数据保留。 | D12 仅代表状态链；保留真实升级 | 模拟版本、成功下载或源码重启不足；需要获准的精确生产候选与实际 feed 两版本证据。 | 保留精确真实两版本升级、安装、重启与数据验收。 |
| U7 | 同 Bundle ID 错误证书、签后篡改且 ZIP hash 正确时仍由原生签名拒绝。 | 保留原生安全负向 | 不能以校验和错误或 fake adapter rejection 代替。旧 PoC 证据只属于原候选。 | 保留真实错误证书及签后篡改的原生拒绝实验。 |
| U8 | 网络/下载/目录/签名/重复安装失败安全；普通退出不安装、旧版可用、数据保留、不强杀 Git。 | D12＋原生边界验收 | adapter 可测状态和冻结释放；原生退出安装、安全恢复和精确位置仍需实际证据。 | 部分替代代表 UI 状态、错误及 gate 释放；原生退出安装、失败恢复和位置/旧版保留仍须证明。 |
| U9 | 无私钥干净用户首启和更新；信任交互有记录；无秘密泄漏且临时信任清理。 | 保留干净用户/信任验收 | Playwright、CI ad-hoc smoke 或现有账号环境不能证明干净用户体验；不读取正式凭据，不为了测试导入长期信任。 | 保留干净用户、信任和真实更新验收。 |

补充安全映射以 [MVP 关键安全回归](../mvp/testing/verification-2026-08-13.md#关键安全回归)、[开发指南的进程边界](../../guides/development-guide.md#3-代码结构与进程边界)及[插件测试标准](../../standards/ide-plugin-development-testing.md)为依据。这些主要是必须保留的低层回归，不能算作新增节省的手工工作量。

| 来源断言 | 拟映射 | 必须保留或补充的验证 |
|---|---|---|
| 无凭据 HTTPS/SSH；Git 环境清理、参数数组、shell:false、输出脱敏。 | D03、D04、D10 | 保留 URL/schema、GitRunner 与 launcher 负向；loopback CA 仅在隔离 Git 配置中信任，不关闭 TLS，不引入 file/local 生产后门。 |
| sandbox/contextIsolation、无 renderer Node、禁止导航与 popup、typed preload/main schema。 | D01、D11 | 实际 Electron 检查加负向探针；断开 bridge、安全配置退化必须失败，页面存在或参数单元测试不足。 |
| canonical/containment、symlink 拒绝、manifest 与索引身份匹配、独立 `.git`。 | D04–D07 | 保留路径攻击、gitfile/commondir/alternates 与身份不匹配低层测试；UI 成功不能替代安全组合。 |
| FIFO mutation、失去 renderer 不破坏操作；发布前 staging 清理、发布后工件保留。 | D05–D07、D12 | 保留并发/取消/进度发送失败回归；指定阶段故障必须使独立磁盘断言区分正式与临时工件。 |
| 插件 trust/ownership/用户配置保护、PFI、取消/dispose、安全恢复和 VCS 只读。 | D08、D09＋S4 | 保留 Kotlin/Light/Heavy/API 检查；UI 不替代模型边界，禁止恢复 Go 工具链门禁或扩大真实 IDE 版本矩阵。 |

## 6. 成本基线与 V 关闭条件

本轮已对预先固定的两个样本实测：D04 手工 13 次交互调用/约 90 秒，自动 20 次中位数约 6 秒、P95 约 7 秒；G2 手工 9 次交互调用/约 90 秒，两轮自动约 7.3 秒和 6.1 秒。自动执行不使用 Computer Use，setup 调用另列；G2 手工样本仍由 Driver 展开树。详细口径见 [V 记录](verification-v-2026-09-26.md#预先固定的成本样本)。49 个原 ID 包含重叠断言、已有自动门禁和原生边界，不能按行数计算成本；全套“减少 80%”与长期 P95/可靠性仍未被这些有限样本证明。

本轮记录了重复次数、用户协助、首次失败与修复、运行耗时及调用数，区分日常代码回归与签名/安装等原生验收。此前增强宿主的五轮中断/失败全部保留；修复后两轮完整通过不等于首次失败为零，也不证明 99% 可靠性。后续继续按相同范围观察稳定性，不用新增测试条数、截图数量或排除困难场景提高替代率。

每项撤销依据范围等价、真实候选关系、实际 selector 与非零执行、故意失败传播、初步稳定性及保留边界。后续输入或产物变化时重新选择受影响检查；本表不授权真实数据操作、安装、签名或发布。GS-16 的实际 Settings 错误仍没有 stage，系统剪贴板及其他明确保留项没有被整体撤销；worker 报告、CI 绿色和构建成功均不能替代这些证据。
