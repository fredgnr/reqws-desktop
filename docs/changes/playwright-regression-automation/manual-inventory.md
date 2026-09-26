---
title: Playwright 迁移的旧验收步骤与替代登记
type: test-plan
status: draft
updated: 2026-09-26
---

# Playwright 迁移的旧验收步骤与替代登记

本文登记旧验收断言与自动化映射，为 S0 清单和 V 阶段逐项判定替代关系提供依据。

## 1. 登记口径与当前结论

映射遵循[技术方案第 11 节](technical-design.md#11-手工用例替代登记)。本轮仍在实现中；表中的 D01–D12 是[场景编号](technical-design.md#5-首批用例范围)，S4 是本机 Desktop→GoLand 联动阶段，均不表示相应完整场景已经通过。本文不改变原需求、历史结论或现行门禁。

S0–S3 已有下表源码入口；本机候选的实际执行数、失败实验和稳定性统一见[实施记录](implementation-2026-09-26.md)。选择器为 `npm run test:e2e -- --grep '<表达式>'`，完整测试标题和行号同时保存在每次 runner 的 JSON 报告中。文件存在不构成 V 替代验收。

| 场景 | 实际测试文件 | 选择器 |
|---|---|---|
| D01 / S1 启动与退出 | [startup.spec.ts](../../../tests/e2e/desktop/startup.spec.ts) | `^D01` / `^S1` |
| D02 冷进程持久化 | [settings.spec.ts](../../../tests/e2e/desktop/settings.spec.ts) | `^D02` |
| D03 仓库配置与 HTTPS | [repositories.spec.ts](../../../tests/e2e/desktop/repositories.spec.ts) | `^D03` |
| D04–D07 创建、成员、发布前后失败 | [workspaces.spec.ts](../../../tests/e2e/desktop/workspaces.spec.ts) | `^D0[4-7]` |
| D08–D09 Desktop 加载选择与冲突 | [goland-selection.spec.ts](../../../tests/e2e/desktop/goland-selection.spec.ts) | `^D0[89]` |
| D10–D12 OS 边界、安全与更新门禁 | [native-boundaries.spec.ts](../../../tests/e2e/desktop/native-boundaries.spec.ts) | `^D1[0-2]` |

启动故障由独立 `npm run test:e2e:negative` 执行[断 preload](../../../tests/e2e/probes/disconnected-preload.spec.ts)和[早期 renderer 错误](../../../tests/e2e/probes/startup-error.spec.ts)；外层 runner 必须验证准确的失败原因和完整新鲜证据才成功。这些探针不作为普通套件的 expected-failure 注解。

S4 已加入 [Desktop 联动 spec](../../../tests/e2e/local-ide/desktop-link.spec.ts)与 [Driver 场景](../../../integrations/goland/src/integrationTest/kotlin/com/reqws/goland/DesktopWorkspaceIntegrationTest.kt)，入口为 `check:goland:desktop`。三个选择器是 `desktopSelectionAndColdProcesses`、`desktopTrustTransitionUsesRealUi`、`desktopInvalidInputsPreserveUserModel`；同候选完整 IDE 与原生落盘通过结果、Trust 环境限定见 [S4 记录](implementation-s4-2026-09-26.md#7-修复后的同候选验证)。其核心链路不自动涵盖 G3 的 Excluded Files 两态/late-shell、G5 的额外 repo3 用户覆盖或全部旧 GUI 断言，本表仍逐项等待 V。

以下各行的**统一当前决定为“保留；等待 V”**。行内“保留范围”说明即使自动化通过，仍需独立证明或继续保留的范围，不代表现在已撤销其余步骤。历史 MVP 报告为 archived，仅用于追溯旧步骤；执行以当前有效设计、标准和授权为准，不恢复已被语言解耦规范移除的 Go SDK、搜索或原生 Git 全套验收。

旧来源没有统一编号时，本文提供定位别名：`MVP-01` 对应原 smoke 表第 1 行，`GS-01` 对应设置设计 §17 第 1 条；`G1`–`G7`、`U1`–`U9` 沿用原编号。别名不是新增加的验收要求。安全列表没有原 ID，使用“来源章节＋断言名称”定位。

每行最终关闭前须补齐：同一候选 Git 身份/构建 profile、精确包或 ZIP 身份关联、真实测试路径/完整 selector、实际执行数/跳过理由、负向失败证据、首次运行和重复运行记录、真实性差异及批准替代的依据。worker 报告、旧候选结果、CI 绿色和构建成功均不能单独关闭该行。

## 2. MVP macOS smoke

来源：[2026-08-13 验证记录的 macOS 目标机 smoke 表](../mvp/testing/verification-2026-08-13.md#macos-目标机-smoke-结果)。下表保留当时断言，不把历史成功转记为本轮结果。

| 旧步骤 ID | 原断言 | 拟映射 | 真实性差异、负向验证和保留范围 |
|---|---|---|---|
| MVP-01 | 安装依赖、完整检查、开发启动成功，主界面出现。 | D01；S3 同包 smoke | built Electron 验证真实 Main/preload/renderer；仍保留依赖/质量门禁。开发启动不能证明候选 `.app` 可用；启动超时、资源缺失应失败并留日志。 |
| MVP-02 | 第二实例退出，第一实例保持唯一并被聚焦。 | D01 的实例专项 | 须启动两个共享同一测试 userData 的真实进程；不同 userData 可并存。mock lock 仅是单元证据；焦点无法自动判定时保留有限原生观察。 |
| MVP-03 | hidden-inset 窗口按钮正常；renderer 无 require/process/Buffer，真实 reqws bridge 可用。 | D01、D11 | 必须显式启用并检查真实 Chromium sandbox；断开 preload 必须失败。窗口按钮位置与原生焦点的视觉结论另验，不能由 BrowserWindow 参数替代。 |
| MVP-04 | SSH Agent 与公开 HTTPS 可连接并识别默认分支；私有 HTTPS helper 认证当时未验证。 | D03、D04 | loopback HTTPS 只证明隔离传输和真实 Git 路径，不证明 SSH Agent、系统 Keychain 或私有 HTTPS helper；历史未验证项继续保留，不能记为迁移后通过。 |
| MVP-05 | Catalog 增删改；连接失败仍可保存，编辑后可连接；删除配置保留本地 clone。 | D03 | UI 走真实 schema/state，独立读盘；传输失败由 Git origin 层制造。原生确认对话框若被替换，只证明确认结果后的业务语义。 |
| MVP-06 | 双仓创建时分别选择代码父目录和 workspace 文件目录，最终 Ready。 | D04 | dialog 边界可替换；原生目录选择器外观和交互不在替换证据内。必须独立读实际输出路径、manifest 和索引。 |
| MVP-07 | 两份独立 `.git`；已有 feature 跟踪远端，不存在时从默认分支创建；无 push。 | D04 | 使用真实 HTTPS origin 和 Git；从测试进程检查分支、`.git` 与远端 refs 前后状态，不能只信 UI Ready 或应用 get API。 |
| MVP-08 | 预置 root/`.code-workspace` 拒绝覆盖，sentinel 内容不变。 | D04、D06 的冲突专项 | 两类冲突分别制造并检查内容保留；路径/symlink/竞态的完整组合保留低层安全测试，不用单次 UI 成功替代。 |
| MVP-09 | 默认分支缺失时失败，无半成品索引、正式 root/workspace 文件或残余 staging。 | D06 | 使用真实缺分支 origin，明确故障发生于发布前。不能把 D07 的发布后工件也清理掉以满足本断言。 |
| MVP-10 | Ready workspace 增加第三仓后 manifest/workspace 更新；逻辑移除后目录和 `.git` 保留。 | D05 | 独立检查成员、文件和保留目录；故意破坏保留语义必须被测试拒绝。 |
| MVP-11 | 移走文件/manifest/root 呈现 Missing/Error；恢复后 Sync 回 Ready；遗忘只删索引。 | D05、D07 的恢复/遗忘扩展 | 现有 D 编号不自动包含完整 Missing/Error 矩阵，须补精确 selector 后逐项核对；移动与恢复仅限自建 fixture，磁盘保留独立断言。 |
| MVP-12 | VS Code/Cursor 打开正确 workspace；Cursor root 与 Finder 定位正确。 | D10 | OS spawn 记录只能证明命令/参数/错误反馈；不能证明真实编辑器加载或 Finder 选中。原生集成改变时保留精确应用观察；未安装分支不能借历史已安装环境证明。 |
| MVP-13 | Cmd+Q 后重新启动恢复 catalog/workspace，创建表单恢复目录默认值。 | D02、D04 | 必须确认原进程退出后启动新 PID，保留该测试自己的数据；reload 或关 macOS 窗口不足。断开持久化应产生失败。 |
| MVP-14 | state 目录 0700、文件 0600、schemaVersion 1；无秘密字段或含凭据 URL。 | D02、D03、D11 辅助断言 | 由测试进程检查自有临时文件权限与内容；保留 URL/日志脱敏低层负向测试，不读取真实用户 state 或凭据。 |

MVP 报告另有[本机安装脚手架验证](../mvp/testing/verification-2026-08-13.md#本机安装脚手架验证)：签名、LaunchServices、真实安装位置、运行中保护和事务恢复不因 D01 或源码 E2E 通过而移除。S3 同包 smoke 只能覆盖其实际执行的候选启动范围；Finder、Gatekeeper、quarantine 与正式签名验收继续按风险和原计划执行。

## 3. 设置与语言验收

来源：[全局设置技术方案 §17](../global-settings/technical-design.md#17-手工验收)。翻译工作流使用当前项目技能契约；本文不沿用旧文档中的历史模型指定，也不修改文案。

| 旧步骤 ID | 原断言 | 拟映射 | 真实性差异、负向验证和保留范围 |
|---|---|---|---|
| GS-01 | 顶层导航出现设置。 | D02 | 真实 renderer 角色/名称定位；缺少入口必须失败。 |
| GS-02 | 点击进入独立设置页。 | D02 | 通过 UI 导航，不直接切内部组件状态。 |
| GS-03 | 首次默认跟随 macOS 系统语言。 | D02 | 固定语言 provider 可证明选择算法；真实 OS 语言获取仍由 Electron 边界/必要原生检查补证。 |
| GS-04 | 保存 English 后全部 React 页面立即切换。 | D02，并联 D03–D10 页面 | 仅设置页截图不证明全部页面；逐页关键文案覆盖和 catalog 一致性检查都保留。 |
| GS-05 | 重启后仍为 English。 | D02 | 完整进程退出/新 PID 和真实 state，禁止 reload 代替。 |
| GS-06 | 切回跟随系统立即应用系统语言。 | D02 | 固定 provider 下验证切换；不把 stub 输出当作本机系统设置实测。 |
| GS-07 | 工作区父目录默认预填。 | D02、D04 | 设置经真实 state 保存，创建表单经 UI 打开并检查。 |
| GS-08 | `.code-workspace` 默认目录预填。 | D02、D04 | 独立验证两种目录，不能用同一路径掩盖字段串用。 |
| GS-09 | 创建表单单次修改路径不改变全局设置。 | D02、D04 | 检查本次产物路径和全局 state，重新打开表单核对。 |
| GS-10 | 旧版 state 可加载。 | D02 的兼容专项 | 仅使用受控旧 schema fixture；保留已有迁移/缺字段测试，不能只运行空 state。 |
| GS-11 | 保存设置不影响仓库和工作区。 | D02、D03、D04 | 先有业务数据，再保存设置并独立比较受保护字段。 |
| GS-12 | 非法/已删除目录不使应用崩溃。 | D02 | 制造失效临时路径，检查错误和后续可操作性；只断言进程存活不足。 |
| GS-13 | 新中文源文案有经复核的英文翻译且一致。 | 保留 i18n 工作流；D02 仅显示检查 | 翻译复核不是 UI 测试可替代的手工步骤，不计入 UI 自动化替代数量。 |
| GS-14 | i18n:check 和现有 CI 全通过。 | 保留原 CI；S3 聚合 | 本项原本是自动门禁，不计作新增替代。新增 job 不得掩盖失败、取消、零测试或全 skip。 |
| GS-15 | 列表/详情刷新失败 Toast 同时显示稳定码和本地化消息。 | D03、D05、D07 的错误扩展 | 在实际失败层注入代表故障；中英文与错误码分别断言，不用成功页面截图替代。 |
| GS-16 | 设置保存失败可查看/复制码、阶段和技术诊断。 | D02 的保存失败扩展 | 单次写失败须走真实错误映射；复制行为需有实际剪贴板或明确边界证据。 |
| GS-17 | 缺失路径列出具体工件并跟随语言切换。 | D05、D07 的 Missing 扩展 | root/manifest/workspace 文件分别核对；不能只检查通用 Missing 标记。 |
| GS-18 | 创建失败进度区分清理未发布 staging 与保留已发布工件。 | D06、D07 | 各阶段分别制造失败，并独立读盘证明清理或保留；两种场景不能合并为一个“回滚成功”。 |
| GS-19 | 已保存目录失效时显示字段警告；选择替代目录后警告消失、全局设置不变。 | D02、D04 | 真实临时目录失效；dialog 替身只证明返回值处理，原生 picker 交互仍不在范围内。 |

## 4. GoLand 加载集合与原生 Project 树

来源：[GoLand 加载集合测试计划 G1–G7](../goland-workspace-loading/test-plan.md#4-必要-gui-用例)。最低版本影响：本清单不修改代码/API/描述符，仍为整个 `262` 系列，无上限；固定原生代表环境仍为 GoLand 2026.2.1.1。高版本采用自动 API 检查，CI 不启动完整 IDE、不要求 IDE 授权，依据[兼容与自动化方案](../ide-plugin-compatibility-automation/README.md)。

[2026-09-22 本机记录](../ide-plugin-compatibility-automation/local-verification-2026-09-22.md)已包含其旧候选的真实 Starter/Driver 结果；这些结果不等于本轮真实 Desktop UI 写入或新候选通过，不覆盖未完成 V。下列联动必须复用显式 ZIP、专用 profile 和同一候选证据。

| 旧步骤 ID | 原断言 | 拟映射 | 真实性差异、负向验证和保留范围 |
|---|---|---|---|
| G1 | Desktop 保存 repo1/2，准备唯一合法入口并打开；Project 展示二仓普通文件，repo3/notes/shell 不展示。 | D08、D10＋S4 | D08 只证明 Desktop binding/selection；S4 必须观察真实普通 Project 父子节点。预写选择文件、只看 Synced 或模拟 OS launch 均不足。 |
| G2 | 仅经 Desktop 执行 2→1→0→2，自动同步；仓库仍在磁盘，成员/分支/其他编辑器文件不变。 | D08＋S4 | 不点 Sync Now；分别证明 Project、模型/PFI 与磁盘状态。破坏 watcher 后联动必须失败，不能以手工刷新补救。 |
| G3 | Excluded Files 开关两态 shell 均隐藏；运行中新仓库文件出现，真实 late-shell 文件仍隐藏。 | S4 | 保留真实文件与开关恢复；不得用搜索、API 数量或移除 probe 代替树观察。只有 D08 选择文件测试不能替代此项。 |
| G4 | 用户额外 root 在显式空集合下保留；项目重开与完整冷进程均恢复；随后二仓恢复，用户配置不变。 | D08＋S4 | 项目重开和进程重启分别记证；不得停止日常 IDE。须新进程先只读观察，不用 reapply 制造恢复结果。 |
| G5 | 未受管 repo3 被用户 root 覆盖时仍可见，插件解释归属且不删用户条目。 | S4 | 用户配置夹具不等于受管加载操作；实际 Project 观察与 ownership 模型断言同时保留。 |
| G6 | 错误绑定/选择不被当空集合删除既有 roots；恢复后自动重核；无绑定项目同名 shell 正常可见且不受管。 | D09＋S4 | D09 revision/保存失败不包含插件读取错误全部语义；S4 独立检查错误恢复和普通项目，平台安全测试继续保留。 |
| G7 | 只读观察 VCS mappings；恢复显示选项，保留夹具和普通文件。 | S4 收尾 | 不修改 Directory Mappings，不新增 Git Log/Commit 卸载或原生 Git 验收。历史手工现场保留要求不授权自动删除；仅按新 fixture 的明确所有权清理自建临时资源。 |

## 5. 更新、打包与安全边界

来源：[自更新技术方案 U1–U9](../macos-self-update/technical-design.md#9-最小验证计划与发布门禁)。这些条目同时包含原有自动门禁与原生验收，不全部计作“旧手工步骤”。当前正式发布缺口以[需求包状态](../macos-self-update/README.md)为准，不从隔离 PoC 或历史签名结果推导本轮 GO。

| 原 ID | 原断言 | 拟映射 | 真实性差异、负向验证和保留范围 |
|---|---|---|---|
| U1 | profile 禁用、状态转换、重复请求、错误映射、订阅清理和 main 输入边界正确。 | D12、D11＋保留低层测试 | 真 UpdateService 配边界 adapter，不换整条服务或 preload；任意 URL/路径与额外 IPC 参数仍须拒绝。 |
| U2 | Git/workspace/state 活动阻止安装；安装准入后拒绝新操作，失败释放。 | D12 | 必须共享真实 activity gate/coordinator，异步子进程未 close 仍占用；仅 mock busy 返回值不足。并发边界继续由低层测试穷举。 |
| U3 | 精确 `.app` 离线可用、依赖完整、userData 稳定；local 不需证书且更新禁用。 | S3 同包 smoke、D01、D12 | 源码 E2E 不证明打包依赖；只消费同次候选包，区分 ad-hoc 与 personal-release，不在本机真实 userData 下盲目启动。 |
| U4 | 元数据、版本、大小和资产集合一致，错误输入拒绝，draft 先验证。 | 保留发布自动门禁；S3 辅助 | D12 的模拟下载不能代替 Release 字节/资产核对；不计作 UI 替代，也不授权发布。 |
| U5 | 缺失/错误签名身份和 ad-hoc 回退阻塞发布；嵌套签名有效，新版满足旧版 DR。 | 保留签名静态门禁 | 原生签名/同名错误证书负向不可由 Playwright adapter 证明，不改 fuse 或签名配置以便测试连接。 |
| U6 | 真实 macOS R1→R2 检查、下载、安装、重启，版本和业务数据保留。 | D12 仅代表状态链；保留真实升级 | 模拟版本、成功下载或源码重启不足；需要获准的精确生产候选与实际 feed 两版本证据。 |
| U7 | 同 Bundle ID 错误证书、签后篡改且 ZIP hash 正确时仍由原生签名拒绝。 | 保留原生安全负向 | 不能以校验和错误或 fake adapter rejection 代替。旧 PoC 证据只属于原候选。 |
| U8 | 网络/下载/目录/签名/重复安装失败安全；普通退出不安装、旧版可用、数据保留、不强杀 Git。 | D12＋原生边界验收 | adapter 可测状态和冻结释放；原生退出安装、安全恢复和精确位置仍需实际证据。 |
| U9 | 无私钥干净用户首启和更新；信任交互有记录；无秘密泄漏且临时信任清理。 | 保留干净用户/信任验收 | Playwright、CI ad-hoc smoke 或现有账号环境不能证明干净用户体验；不读取正式凭据，不为了测试导入长期信任。 |

补充安全映射以 [MVP 关键安全回归](../mvp/testing/verification-2026-08-13.md#关键安全回归)、[开发指南的进程边界](../../guides/development-guide.md#3-代码结构与进程边界)及[插件测试标准](../../standards/ide-plugin-development-testing.md)为依据。这些主要是必须保留的低层回归，不能算作新增节省的手工工作量。

| 来源断言 | 拟映射 | 必须保留或补充的验证 |
|---|---|---|
| 无凭据 HTTPS/SSH；Git 环境清理、参数数组、shell:false、输出脱敏。 | D03、D04、D10 | 保留 URL/schema、GitRunner 与 launcher 负向；loopback CA 仅在隔离 Git 配置中信任，不关闭 TLS，不引入 file/local 生产后门。 |
| sandbox/contextIsolation、无 renderer Node、禁止导航与 popup、typed preload/main schema。 | D01、D11 | 实际 Electron 检查加负向探针；断开 bridge、安全配置退化必须失败，页面存在或参数单元测试不足。 |
| canonical/containment、symlink 拒绝、manifest 与索引身份匹配、独立 `.git`。 | D04–D07 | 保留路径攻击、gitfile/commondir/alternates 与身份不匹配低层测试；UI 成功不能替代安全组合。 |
| FIFO mutation、失去 renderer 不破坏操作；发布前 staging 清理、发布后工件保留。 | D05–D07、D12 | 保留并发/取消/进度发送失败回归；指定阶段故障必须使独立磁盘断言区分正式与临时工件。 |
| 插件 trust/ownership/用户配置保护、PFI、取消/dispose、安全恢复和 VCS 只读。 | D08、D09＋S4 | 保留 Kotlin/Light/Heavy/API 检查；UI 不替代模型边界，禁止恢复 Go 工具链门禁或扩大真实 IDE 版本矩阵。 |

## 6. 成本基线与 V 关闭条件

本轮尚未测量旧手工操作耗时、Computer Use 调用数、稳定失败率或新套件 P95 运行成本。历史 smoke 的 14 组、GS 的 19 条和 G1–G7 不能直接相加作为成本：存在重叠断言、已有自动门禁和不可替代的原生边界。因此“减少 80% 手工成本”的目标**尚未证实**，本文不填写虚构的基准分钟数、节省比例或已替代数量。

V 应先冻结可比范围并实测旧/新流程：记录重复次数、实际用户协助、首次失败与诊断重试、运行耗时、Computer Use 调用及保留例外；分别比较日常代码回归与签名/安装等按风险触发的原生验收。不得用新增测试条数、截图数量或排除困难场景提高替代率。

每项撤销须同时满足：范围等价、真实候选关系、实际 selector 和非零执行证据、故意失败能可靠传播、初步稳定性记录，以及仍然保留的原生/权限边界说明。先完成这些证据再更新原规范；在此之前，本表各行均继续保留，S0/S1 worker 完成记录不构成 V 的替代决定。
