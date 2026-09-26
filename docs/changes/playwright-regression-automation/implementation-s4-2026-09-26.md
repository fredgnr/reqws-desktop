---
title: S4 Desktop 与 GoLand 本机联动实施记录
type: test-report
status: active
updated: 2026-09-26
---

# S4 Desktop 与 GoLand 本机联动实施记录

本记录区分 S4 联动实现、CI 检查和获准执行后的本机 IDE 实际结果。

## 1. 范围与当前结论

基于 `feat/playwright-automation` 的 `4f5ff89` 扩展 S4；原 S0–S3 证据仍见[既有记录](implementation-2026-09-26.md)。初始 S4 宿主实现不改生产行为；实跑后另获准修复插件初始 JPS 同步，并调整精确 API 例外的验证入口。manifest schema、SDK、签名/发布授权与兼容性下限不变：仍支持整个 `262` 系列，无上限；完整 GUI 代表仍为 GO 2026.2.1.1 / 262.9437.286。

**S4 联动已通过同候选完整实跑。** 插件使用 `16ffce1` 的 CI 原始 ZIP，Desktop/宿主冻结为 `1652c8f`，固定代表 IDE 和专用 profile 不变；三项测试、六个独立完整进程、二十条投影及四个工作区原生落盘证明通过，见第 7 节。首轮失败和修复过程保留在第 4–6 节。V 和旧手工门槛保持原状，不涉及日常 IDE 或真实用户工作区。

## 2. 实现与真实性

现有 [run_local_ide.py](../../../scripts/run_local_ide.py) 增加 `run --suite desktop`，对应 `npm run check:goland:desktop`；默认 `legacy` 三组场景及九进程要求保留。profile 独占锁覆盖两端，本轮目录、显式 ZIP、授权准备、固定 SDK、退出检查和报告复用原入口。新增套件要求冻结干净 Desktop commit，前后核对 Git 身份；验证报告同时核对该源码和精确 ZIP。

- [Desktop spec](../../../tests/e2e/local-ide/desktop-link.spec.ts) 使用实际构建的 Electron、preload、renderer、真实 Main 服务和隔离 HTTPS Git。通过 UI 创建四个工作区、GoLand 入口及加载选择；普通用户文件与原生用户模块仅用于保留断言。
- [文件协议](../../../tests/e2e/fixtures/desktop-link.ts)及 [Driver 消费者](../../../integrations/goland/src/integrationTest/kotlin/com/reqws/goland/DesktopLink.kt) 只接受固定场景名和两仓库选择。会话 UUID、序号、原子不可覆盖消息、路径/目录身份及独立文件回读约束跨进程关系；产品无新增控制端点。
- [Driver 场景](../../../integrations/goland/src/integrationTest/kotlin/com/reqws/goland/DesktopWorkspaceIntegrationTest.kt) 要求 `desktopSelectionAndColdProcesses`、`desktopTrustTransitionUsesRealUi`、`desktopInvalidInputsPreserveUserModel` 三项全部执行，合计六个独立 IDE 进程、二十条投影证据。
- 成功路径由 Desktop UI 唯一写入业务文件。Driver 核对 workspace/binding/revision、从真实输入计算的 digest、加载 IDs、模块根、PFI、普通 Project 树和用户内容；不调用 Refresh、Sync Now 或内部同步函数。故障专项仅在本轮固定文件注入坏 JSON/身份冲突，恢复原字节后检查真实 watcher 恢复。
- Trust 专项通过真实 Safe Mode 和 Trust Project 对话框，不预信任该项目，禁用全局 trust bypass，并只读核对 IDE trust 状态。它不代替授权登录。
- [协调器](../../../scripts/desktop_ide.py) 与[报告门禁](../../../scripts/check_ide_test_reports.py) 拒绝失败、取消、超时、空/skip/重复测试、缺步骤、错源码/ZIP、树/PFI不一致或异常进程退出。两端退出无法确认时保留 active-session；Desktop fixture 保留在私有报告目录，避免提前删除 IDE 使用中的文件。

CI 继续只编译宿主并运行平台/API 检查；无工作流完整 IDE、许可 Secret 或生产 Driver 依赖。报告和 profile 不提交、不上传；原始私有诊断分享前需单独审阅。

## 3. 已执行检查

当前实际结果：

| 检查 | 结果与范围 |
|---|---|
| `compileIntegrationTestKotlin` | 通过；Java 25，仅编译，无 IDE 启动。 |
| `npm run check` | 518 通过、1 项原有 hosted-only 系统信任检查跳过，共 50 个测试文件；类型、lint、i18n 和当时文档检查通过。跳过不计作通过。 |
| 新增 TS 协议安全检查 | 6 项，涵盖不可覆盖、会话/序号、symlink/大小、受限命令、abort/超时和私有进程 registry。 |
| Python 本机协议/报告检查 | 首轮 7 项；宿主及落盘门禁修正后 9 项，覆盖丢失/错误证据、实时与冷启动步骤、旧 suite、同 ZIP 不同 Desktop 源码、两端清理、profile 保留、非法输入的受保护 PFI、原生落盘根/marker/模块登记和旧报告拒绝。 |
| Desktop 协议单独试跑 | 实际 UI 完成 9 个创建/保存请求及 1 个结束请求；Playwright 1/1，通过，零重试。仅验证 Desktop 端，无 IDE 启动，`scope=desktop-link-protocol-only`。 |
| `npm run test:e2e` | 18/18 通过，零跳过/重试；覆盖本次修改涉及的共享 fixture、进程登记与退出。 |
| `npm run test:e2e:negative` | 两项预定真实启动故障均准确失败并留下完整新鲜证据，严格外层门禁通过；无吞掉或改标 expected-failure。 |
| Python workflows | 184/184 通过；使用已有 OpenSSL 3 与 Java 25。首次受限网络下载失败、第二轮系统 LibreSSL 参数不兼容；修正执行环境后完整重跑，不修改或跳过测试。 |
| S4 修正后的 Python workflows | 186/186 通过；重复下载签名器的首轮被明确中止，随后用已有固定 0.1.43 ZIP Signer 缓存、OpenSSL 3 与 Java 25 完整重跑，无跳过。 |
| 插件 baseline | 366 执行，零跳过、零失败；保留生产/测试/宿主编译、Light/Heavy、禁用 API 与结构门禁。 |
| `npm run docs:check` | 通过，26 个索引、111 个文档。 |

协议首轮试跑因旧 registry 仅允许仓库 `test-results` 路径而失败。修复为本轮 session 绑定的固定私有 registry，并传递给外置测试 Main 后重跑通过；未放宽生产 Git、路径或 sandbox 保护。失败记录与成功 trace/截图、真实磁盘、子进程证据分别保留于本机私有输出。

独立只读审查发现“同 ZIP 可借用不同 Desktop 源码的旧报告”；已增加干净 Git 候选的运行前后校验、verify-report 校验和拒绝测试。审查者只读，未运行 IDE；其结论不代替实际执行。

`42d23dd` 的[完整 CI](https://github.com/fredgnr/reqws-desktop/actions/runs/36238709358) 已通过 Electron、同包 smoke、项目/工作流、插件 baseline、冻结的七个 API 目标及聚合门禁。已下载该次原始 ZIP、矩阵与七份报告，严格汇总确认属于同一精确 ZIP，再用于获准本机运行。本机独立 `check:goland` 在第五个 API 目标下载依赖时停止，记为未完整完成；不把 CI ZIP 的结果转记到本机重建 ZIP。

## 4. 获准首轮执行与修正

用户允许后，使用专用 profile `goland-2026.2.1.1` 和同一 CI ZIP 执行 `run --suite desktop`。首次运行在 IDE 启动前遭遇官方 Maven TLS 下载失败；使用正常 Gradle 依赖解析重试成功，未修改版本或 TLS 校验，再执行完整套件。

实际运行目录标识为 `reqws-local-ide-2g688u0l`：三项 JUnit 均失败，记录五个已退出 IDE 进程、十条局部投影证据，Desktop 会话退出已确认，profile 锁和 active-session 已释放。局部成功步骤不计作整个套件通过。

- 选择场景的第一进程完成全部实时选择，第二进程完成冷启动空集合及切回两仓库；随后 IDE 延迟执行旧 `.iml` 的 JPS 加载，覆盖刚提交的两个根。第三进程遇到缺少根/marker 的所有权冲突。日志显示覆盖发生在第二进程退出前；不能归结为单纯退出未保存，也不能用路径清单重新认领、固定等待或测试自动保存掩盖。该首轮执行时生产修复尚未完成。
- Trust 场景的真实 Safe Mode 与信任后投影均有证据，但固定 IDE 随附的可选 `com.ypwang.plugin.go-linter` 在未信任状态启动时报错，严格 IDE 错误门禁使该项失败。宿主修正只为 Trust context 使用公开 Starter 配置生成本轮临时 disabled-plugins 文件，并回查该插件未加载；不改持久 profile 的插件设置、候选 ZIP 或错误门禁。该环境限定必须随 Trust 结果记录，不能宣称默认全部 bundled plugins 下通过。
- 非法 binding 场景错误地要求 shell PFI 不变。既有契约要求撤销非法绑定的临时 shell 排除/树过滤，同时保留模型和用户内容。修正后严格比较根、模块和非 shell PFI，另要求原生 shell 恢复可见；证据保存实际错误态而非错误前快照。Python 负向用例继续拒绝仓库 PFI 被改变或 shell 隐藏未撤销。

两项宿主修正冻结为 `b6fd9df`，`compileIntegrationTestKotlin` 与当时八项 Python 协议/报告检查通过，独立只读审查未发现明确问题。随后使用相同 CI ZIP 重跑，私有目录标识 `reqws-local-ide-36cso1z_`：三项 JUnit、六个独立正常退出进程、二十条实时投影、Desktop 1/1 均通过；Trust 与非法输入两项修正得到实际验证。

**该轮不能作为 S4 GO。** 退出后的独立磁盘复核发现 selection 的 `.iml` 仍没有任何 content root，而 journal claims 已有两个根；两个冷进程仅从 IDE 缓存恢复。先前报告门禁缺少原生落盘验证，产生了不足以证明冷恢复的通过报告。保留原始报告以追溯，不篡改其内容，也不重试取绿掩盖首轮失败。

因此补充退出后四个工作区的只读落盘门禁：核对 Desktop binding/journal 身份、`modules.xml` 精确登记、原生 `.iml` 最终根集合及每个 nonce 对应 marker；拒绝链接、越界路径、不完整模块、危险或超大 XML。新增 `savedProjectionProofs=4` 为报告复用必需字段，旧通过报告不再可用。九项 Python 检查通过；将新 validator 只读应用于第二轮真实产物，准确拒绝 selection，另外三组持久化检查通过。这是对既有产物的诊断，不是新一轮 GUI 执行或新候选 GO。

独立审查进一步要求完整 journal schema/目录身份、UTF-8 限定与用户模块落盘保留；已补齐对应拒绝用例，复核无剩余明确问题。完整报告门禁与 `verify-report` 均已只读拒绝第二轮不足的旧证据。审查不代替 GUI 执行。

对固定 262 SDK 的只读 API 调查排除了普通 startup 完成信号、后台 `ProjectActivity` 扩展顺序、legacy 可写模型和 `Project.save()`/`scheduleSave()`：这些都不能保证初始 JPS 同步已完成；JPS serializers 尚未建立时保存甚至直接跳过。已找到的精确等待接口 `WorkspaceModelInternal.awaitSynchronizationWithJpsModel` 及旧 JPS loaded 通知均标为 `@Internal`，按现行规范不能加入生产实现。没有调用这些接口、放宽所有权或引入语言服务等待。兼容基线和生产插件代码未改变。

## 5. 待执行与保留范围

冷启动修复及 S4 落盘门禁已在第 7 节候选上完成；保留以下历史失败以说明覆盖缺口和修复依据。全部结果以同一精确 ZIP、冻结 Desktop commit、独立完整进程与新鲜报告为准；遇需用户协助事项停止等待回复。

原三组 `legacy` 宿主也已获准执行，私有目录标识 `reqws-local-ide-j3gxzzqk`：`atomicSelectionAutomaticallyRefreshes` 通过；`loadingAndProjectTree` 因宿主下载 TLS 握手失败而失败；`emptyAndNonemptySurviveColdProcesses` 因固定 IDE 的 Marketplace 插件列表缓存 JSON 被截断而失败。合计三项执行、一项通过、两项失败，八个已启动 IDE 进程全部退出，专用 profile 已释放；未达到九进程门槛。保留严格 IDE 错误门禁，未屏蔽网络/缓存异常，不能借用 2026-09-22 结果声称新宿主通过。

V 后续仍需逐项核对[旧步骤登记](manual-inventory.md)，特别是 Excluded Files 两态、late-shell、额外 repo3 用户覆盖、完整负向破坏实验及成本/稳定性口径；本轮不撤销其要求。

执行参数、私有报告与 `verify-report --suite desktop` 的使用见[本机入口](../ide-plugin-compatibility-automation/local-integration.md#desktop-真实-ui-联动)。

## 6. 已授权 API 例外

用户于 2026-09-26 明确批准仅为 `WorkspaceModelInternal.awaitSynchronizationWithJpsModel` 引入受控 `@Internal` 例外，并确认目前没有公开 API 或组合提供同等能力。[AGENTS.md](../../../AGENTS.md) 和[插件标准](../../standards/ide-plugin-development-testing.md#3-插件实现约束)同步记录该单独例外。修复方向是在任何 journal/model 写入前可取消地等待初始 JPS 同步，返回后重新核对 generation、trust 和 binding，并保留所有权检查。它不等待语言服务、不提高最低 262、不允许其他内部 API。

源码/JAR 与 Verifier 必须将例外限制在单一包装器及精确成员，保留其余阻断级别和原始报告。后续先验证整个 262 基线/API 矩阵上的接口与取消行为，再对新候选执行 S4、legacy 及原生落盘检查；不能把此前 ZIP 的 CI 或局部 GUI 证据移用于生产修复后的 ZIP。

已读取缓存的最低 262.8665.270、262.8665.336、262.9437.195、固定 262.9437.286 和 262.10315.135 SDK：方法签名一致，类型有 Internal 注解，点名方法另有 Experimental 注解。两类注解的例外都只覆盖这一个使用点；源码读取不等于候选 API 矩阵通过。

平台方法有一分钟软超时：它可能正常返回并留下共享超时状态，调用方取消也不会取消平台计时器。因此包装器采用项目生命周期内一次共享等待、30 秒硬超时、失败记忆且不重试；单个候选取消只取消自己的等待，不能反复启动平台计时器。超时继续阻止 ReqWS 写入。其他平台调用者此前留下的共享软超时状态无法通过获准 API 读取，该限制仍存在；不得宣称该方法在任意历史平台状态下提供绝对加载完成保证。S4 同时保留真实树/PFI和退出后原生落盘门禁。

### 实现与当步检查

`InitialJpsSynchronization` 是唯一受限 API 包装器；`ManagedRootsAdapter` 在目录/模型锁和 journal 之前等待，等待前后检查取消和候选有效性；`LoadedProjectionService` 在根应用成功后才发布 shell 隐藏。新增三项共享等待/取消/失败记忆测试及五项 adapter 安全测试。直接受影响的三十三项测试通过，随后完整插件 baseline 三百七十四项通过，零跳过、零失败。初轮一项测试把异常对象身份当作断言，受协程栈恢复复制影响；修正为核对异常类型、消息、调用次数和 scope 存活后重跑通过。

`scripts/ide_api_exception.py` 同时核对实际 ZIP/JAR 指令和原始 Verifier 报告，只允许固定私有 `awaitPlatform(Project, Continuation)` 内一次精确 cast 与接口调用。所有 Gradle failure levels 保留，原始任务仍如实非零退出；统一 runner 仅接受两行 Internal、一行 Experimental 和唯一可解释的 `verifyPlugin` 失败，其余内容或失败仍阻断。最低/固定目标统一走 `--baseline`，CI、weekly、release 和本机原有完整矩阵继续保留；签名后仍按最终 ZIP 重新核验。二十项新门禁检查及整合后的二百零八项 Python workflows 通过。独立只读审查未发现生产修复或例外门禁的剩余明确问题，审查不计作测试执行。

Desktop 全检在授权环境为五百一十八项通过、一项原有 hosted-only 跳过，类型/lint/i18n/文档检查通过；首轮沙箱阻断 loopback 与一次性 macOS fixture，未改标跳过。Starter 改用公开 `useRelease(version)` 避免无关 EAP/preview 查询，保留固定 SDK 的 ProductInfo 强核对；坏 Marketplace JSON 仅位于旧 run root，新轮自然使用新目录，未修改 profile 的账号或许可文件。该检查点尚未完成的完整 API 矩阵和新 ZIP 的 S4/legacy 实跑，后续结果独立记于下文。

`16ffce1` 的[完整 CI](https://github.com/fredgnr/reqws-desktop/actions/runs/36243622124)已全部通过，包括七个 API 目标；已下载原始 ZIP/报告并严格核对同一候选。其 ZIP 与本机重建 ZIP 字节不同，因此 GUI 选择已核验的 CI 原始 ZIP，本机慢速下载中的矩阵仍独立记录。为避免共享 Gradle 输出，在同一提交的隔离 checkout 编译宿主和运行 Electron，仍独占同一专用 IDE profile。

隔离首轮 `reqws-local-ide-futqtkvq` 在任何 IDE 启动前失败：Starter ZIP reader 尝试可写打开只读输入，解包失败清理又删除了本轮下载的临时 CI ZIP。Desktop 已退出、profile 已释放；未触碰用户原有产物。随后修正本机入口：原 ZIP 保持只读，先创建本轮私有可写的精确字节副本再交给 Starter；每 context 安装前后、成功前都校验候选，报告记录原件和副本。新增测试模拟安装器删除副本，确认只读原件完整保留，并拒绝错摘要、链接和已存在的 staging 目录。恢复的 CI ZIP 必须重新核对原 CI 证据，不能将该失败轮记作通过。

副本修正 `e3e1211` 的二百一十项 Python workflows 通过。使用恢复并重新核验的同一 CI ZIP 运行 `reqws-local-ide-lc0x7t04`：选择/冷启动场景通过三个独立完整进程，八条实时投影及退出后原生 `.iml` 两根/marker、journal、用户模块的只读复核通过。Trust 和非法输入场景在 IDE 启动前被发布目录 TLS 握手失败阻断，整套仍失败。原只读 ZIP 保留完整，所有已启动进程退出、profile 已释放；局部冷启动成功不能代替 S4 GO。

随后宿主通过公开 `IdeInstaller` 和 `DefaultIdeDistributionFactory` 复用已校验的固定 SDK，避免每个 context 重复联网解析。仅缓存确实缺失才走官方安装器；已存在但身份、路径不符的缓存直接失败。进入工厂前拒绝 JBR override/backup，防止工厂替换运行时；每 context 创建独立描述并再次核对身份。没有新增受限 API，最低 262 与原始候选 ZIP 不变；完整重跑结果见第 7 节。

## 7. 修复后的同候选验证

插件候选是 `16ffce1` 的 CI 原始 `0.1.7` ZIP，该次完整 CI 与七目标 API 汇总均通过。Desktop/宿主冻结在干净提交 `1652c8f`，其后生产插件没有变化；不重建、重签或用另一份本机 ZIP 替代。固定 SDK 复用的宿主编译通过；独立只读审查发现并修正 plist 启动文件与解析歧义，复核无剩余明确问题。仅测试宿主使用公开 Starter API，最低 262 不受影响。

S4 私有运行目录标识 `reqws-local-ide-0n2gb5n4`，2026-09-26 13:35–13:38 UTC：

- 三个必需 JUnit selector 全部执行并通过，零跳过、零失败；六个不同 IDE PID 均通过并正常退出。
- 二十条逐步投影证据通过；退出后的四个工作区均通过原生 `modules.xml`、`.iml`、根/marker 和 journal 保存门禁，`savedProjectionProofs=4`。selection 和两个 invalid 场景另验证用户模块保留；Trust 场景没有用户模块 fixture，不计入该项覆盖。
- Desktop Playwright 1/1 通过，零跳过、零 flaky；十个请求创建四个工作区并完成全部选择转换，Desktop 正常退出。
- 只读 `verify-report --suite desktop` 在同一干净宿主提交核验通过；原始 ZIP 与安装副本保持一致。专用 profile 已释放后才开始后续 legacy 回归。
- Trust 结果保留第 4 节的限定：仅该 context 禁用固定 IDE 自带的可选 Go Linter；实际 Safe Mode/Trust Project 流程与所有 IDE 错误门禁均保留。

随后同一宿主、profile 和精确 ZIP 执行 legacy，私有目录标识 `reqws-local-ide-4ye6c5zo`，13:38–13:41 UTC：`loadingAndProjectTree`、`atomicSelectionAutomaticallyRefreshes`、`emptyAndNonemptySurviveColdProcesses` 三项全部通过，零跳过、零失败，九个独立 IDE 进程正常退出；`verify-report --suite legacy` 通过。两套共十五个 IDE 进程全部收尾，专用 profile 无遗留 active-session。

本机另建 ZIP 的 `npm run check:goland` 完成三百七十四项 baseline、最低/固定两个 API 目标，以及完整矩阵前四项；第五项 GO-262.10315.135 下载官方 PythonCore 依赖约三十三分钟后遇到 HTTP/2 `RST_STREAM`。已按核实的父子进程关系停止该份独立检查，保留私有日志；该命令整体记为未完整完成，绝不转记为七目标通过。最终 GUI 所选的是上述已有完整 CI/API 证据的原始 CI ZIP，两份产物身份不混用。最终文档提交只记录结果，不变更已冻结实跑宿主或生产代码。

该结果完成 S4 的本机联动验收，不代表最终 V、默认全部 bundled plugins 下的 Trust、签名后另一 ZIP、其他 IDE 的 GUI 或发布授权。
