---
title: Playwright S0–S4 实施与验证记录
type: test-report
status: active
updated: 2026-09-27
---

# Playwright S0–S4 实施与验证记录

本记录按阶段汇总 Desktop 自动化与本机 GoLand 联动的实现、候选、实际验证结果及保留边界。

以下保留各阶段当时的记录；“本轮”“当前”“待执行”等表述以所在阶段为准，不将后续结果回填为早期通过。后续 V 的逐项替代结果见 [V 验收记录](verification-v-2026-09-26.md)。本次合并仅调整文档组织与链接，插件最低仍为整个 262 系列，无兼容上限。

- [S0–S3：Desktop 自动化与 CI](#s0-s3-desktop-自动化)
- [S4：Desktop 与 GoLand 本机联动](#s4-本机联动)

## S0-S3 Desktop 自动化

本记录区分当前分支的实现、实际本机执行结果、CI 候选证据及仍保留的手工验收边界。

### 候选与环境

- 分支 `feat/playwright-automation`，初始基线 `39c80b78de4fb36fce2307d9b896f4c4346d7ac9`；实现提交 `b70e452`，公开 Git 接口修复 `ea90aeb`。随后合并 main `677ef00`，并以 `4831585` 补齐独立 Git 进程组登记。最终代码候选 `56657ea7586ba1b6f0820a13ffbab50749ce4928` 修复 D12 等待顺序；对应 CI 实际 tested merge commit 为 `f2d90844fe938b7cdd372656b637d794ee94da10`。后续仅更新本记录与索引，不改变运行时代码或测试。
- 本机 macOS 15.7.3（24G419）、arm64、Node 24.20.0、Electron 43.4.0、Playwright 1.63.0。Playwright 使用仓库 Electron runtime，没有另行下载浏览器矩阵。
- 每次源码入口重新构建 `.vite/e2e` 的 Main、真实 preload 和 renderer；测试实例使用独占临时 HOME、userData、sessionData、logs、origin 和输出目录。未使用真实 ReqWS 数据、工作区或日常 IDE。
- 插件最低版本影响：未改变插件 API、SDK、manifest/selection schema 或 IDE 入口协议；最低仍为整个 262 系列，无 `until-build` 或 `strict-until-build`。本轮 Desktop 选择测试不证明 GoLand 模型或 Project 树结果。

### 阶段结论

| 阶段 | 当前结论 |
|---|---|
| S0 | 通过：手工清单、当前 Electron/Playwright、sandbox、HTTPS Git、故障证据及真实 macOS runner/同包启动均已验证。 |
| S1 | 通过：锁前隔离、真实装配、PID/目录归属、正常及阻止退出的清理、生产 ASAR 排除均有检查；同步 main 后补齐 detached Git 的登记与删除前存活检查。 |
| S2 | 通过：最终代码候选在真实 CI 的 D01–D12 共 18 项通过；D12 等待竞态修复另有本机连续 20 次通过。 |
| S3 | 通过：最终代码候选的源码、负向、同包、项目及 Desktop 聚合门禁通过；核心 smoke 80/80，0 skip/flaky/retries。保留原有插件/API 检查，未撤销旧手工门槛。 |
| S4 / V | 未实施；未撤销旧手工门槛。 |

### 实现边界

生产入口与外置测试入口共用 bootstrap。路径设置同步发生在 single-instance lock 之前；真实 AppStateStore、WorkspaceFileWriter、GitRunner、WorkspaceService、IPC/Zod、preload、窗口安全、activity gate 和 mutation coordinator 均保留。生产入口没有测试环境开关或控制协议。

允许的替换位于系统边界：原生选目录返回值、编辑器最终 spawn、updater adapter、指定原子发布的单次 OS 错误/等待。故障和控制对象仅在外置 test-main。Forge 显式只收集生产 build/renderer，排除 `.vite/e2e` 和源测试代码；已在本机及 CI 同次生成的 `.app` 内检查实际 ASAR，而非只依赖源配置。

Git fixture 使用临时 CA 和 loopback HTTPS，经真实 `git http-backend` clone/fetch；不关闭 TLS、不导入钥匙串、不允许本地路径 URL，不使用 insteadOf。测试 spawn wrapper 在生产 sanitizer 之后设置固定的无系统配置策略，使用隔离 HOME/XDG、空 hooks/templates、非交互凭据策略及环境 allowlist。

Playwright 显式 `chromiumSandbox: true`，运行时核对 `app.getAppMetrics()` 的 renderer OS sandbox、无 `--no-sandbox`，并确认独立 preload 执行上下文与页面没有裸 Node API。Chromium 对既有 HTML meta `frame-ancestors` 指令的固定诊断单独保存在 console 证据；它在 meta 中无效，不能当作已生效的安全保证，其余 renderer error 仍使测试失败。窗口导航/popup 策略保留。

### 已执行的开发检查点

| 检查 | 实际结果 | 范围 |
|---|---|---|
| `npx vitest run tests/unit/main-lifecycle.test.ts tests/unit/main-service-boundaries.test.ts tests/unit/build-config.test.ts` | 3 文件、15 项通过 | 锁前路径、生命周期、真实服务/共享 gate、生产产物排除策略。 |
| `npx vitest run tests/integration/e2e-git-origin.test.ts` | 1 文件、7 项通过 | 真实 HTTPS Git、CA 拒绝、503 恢复、只读端点、环境隔离、活动 backend 与目录清理。 |
| `npm run test:e2e:smoke`（S1 检查点） | D01、D02 共 2 项通过 | built renderer、真实 bridge、OS sandbox、中英文保存、退出后新 PID 恢复设置。 |
| `npm run test:e2e -- tests/e2e/desktop/startup.spec.ts` | 2 项通过 | D01；两个独立 userData 实例共存，共享 userData 的竞争进程以 0 退出。 |
| `npm run test:e2e -- tests/e2e/desktop/repositories.spec.ts tests/e2e/desktop/workspaces.spec.ts` | 7 项通过 | D03 两项、D04、D05、D06、D07 两种发布后故障。 |
| `npx playwright test --config playwright.probes.config.ts` | 1 项按预期失败，进程 exit=1 | 一次性构建副本移走 preload，readiness 超时；不是正常套件中的 expected-failure 注解。 |

断 preload 负向实验已检查 JSON 报告为 `unexpected=1, skipped=0, flaky=0`，并读回非空 Main 日志、截图、启动错误、退出记录、renderer 错误与磁盘快照。解包 trace 确认包含实际 `screencast-frame` 和 `frame-snapshot` 事件，不能仅凭 trace 配置存在宣称覆盖。手动 context tracing 不记录 expect，断言保留在 Playwright 报告中。

以上是开发检查点，不替代最终集成候选的完整基线和稳定性结果。最初沙箱拒绝 loopback listen 的运行不是通过；允许本次隔离服务后重新执行。测试日志/trace 留在忽略的 `test-results/`，临时 CA、Git 和数据目录在确认归属与进程退出后清理，不提交产物或逐文件摘要。

### 合并主分支前的本机集成验证

| 命令/检查 | 实际结果 |
|---|---|
| `npm run check` | TypeScript、ESLint、337-key i18n、文档检查通过；Vitest 47 文件，475 项通过、1 项按既有规则跳过。跳过的是仅真实 hosted macOS 执行的管理员 Code Signing trust cleanup，未在本机伪造 CI。 |
| `npm run test:e2e` | 18 项通过，0 skip、0 flaky、0 retries；严格 wrapper 返回 0。完整 JSON、源码身份、选择器与日志保存在本机忽略目录 `test-results/integrated/`。 |
| `npm run test:e2e:smoke -- --repeat-each=20` | D01/D02/D03/D04 各 20 次，共 80 项通过，耗时约 4.2 分钟，0 skip/flaky/retries。每项新 fixture、新进程，D02 还执行完整退出重启。报告在 `test-results/stability/`；仅为初步稳定性，不能推导 99% 可靠或手工成本降低比例。 |
| `npm run test:e2e:negative` | 两项准确故障均 exit=1，wrapper 检查原因、完整新鲜证据和退出后返回 0；不是把任意失败视为通过。`test-results/negative-result.json` 保存最终结果。 |
| `REQWS_OPENSSL=/opt/homebrew/opt/openssl@3/bin/openssl python3 -m unittest discover -s tests/workflows -p 'test_*.py' -v` | 177 项全部通过，包括 33 项新增 Desktop 报告/故障/进程归属和聚合测试。最初沙箱 DNS 失败、未显式选择 OpenSSL 3 的失败均不算通过；上述最终运行使用已有 OpenSSL 3 与本机 OpenJDK 21.0.12.1 执行一次性 ZIP Signer 测试，未运行 Gradle/IDE。 |
| `REQWS_BUILD_PROFILE=local npm run package:macos -- --skip-ci --skip-check --arch arm64` | 生成并验证 `out/ReqWS-darwin-arm64/ReqWS.app`；arm64、bundle/version 和 ad-hoc codesign 结构通过，未安装、未启动。复用刚通过的 check，未重复安装依赖。 |
| 实际 `app.asar` 检查 | 13 个条目，入口 `.vite/build/main.js`、真实 preload 与 renderer 存在；没有 `tests`、`e2e`、test-main、`REQWS_E2E_`、`__reqwsE2E` 或故障实现。结果在 `test-results/production-archive.json`；没有把本机构建冒充 CI 启动证据。 |
| 本机调用 `test:e2e:packaged` | 按预期拒绝：要求真实 GitHub-hosted macOS arm64 runner，exit=1；拒绝发生在启动前，没有触碰真实 ReqWS userData。 |

最终负向探针覆盖断 preload 及“页面仍 ready 的早期 renderer 错误”。启动前段错误从 Playwright 缓存补读，故障原因由报告、renderer-errors 和 launch-error 共同确认；trace 仍必须包含真实 Electron、DOM 和截图，但不要求开始 tracing 之前的 console 事件被回放到 trace。

独立只读审查发现并修复了无期限退出、启动早期错误漏检、feature/default 使用同一提交导致的弱断言，以及清理状态提前置位。D04 第一仓现在有独立 feature 提交与普通文本内容，第二仓从 main 创建新 feature；两仓起点和远端未推送均可区分。

一次开发检查中，修改正在归档的源码导致 `sources: true` 的 trace ZIP 失败；后续按冻结候选执行，未关闭该错误或用重试掩盖。D11 使用真实拒绝导航事件与现存 DOM 结果，避免 Electron 拒绝导航后 Playwright 的 pending-navigation 等待边界。D03 曾出现一次点击未提交，诊断确认英文编辑弹窗 footer 横向溢出，未声称已经从那次 trace 证明唯一根因；已修正按钮换行、补无横向溢出的真实断言并保留截图，最终完整套件及 20 轮 D03 均通过。没有新增 UI 文案或 catalog 变更。

runner 超时/中断时先请求本轮 Node 退出，再核对已登记 Electron/Git 的 PID、独立进程组和内核创建时间；只对仍一致的归属进程终止并确认。正常返回如留下活进程仍判失败。开发中一次因测试参数错误主动中断的运行真实返回失败并完成进程审计，未被计入通过数字。

### 主分支同步与 CI 修复

用户已授权提交、推送和分支 CI；本轮沿用 [PR #22](https://github.com/fredgnr/reqws-desktop/pull/22)，未合并或发布。

- [首轮 CI](https://github.com/fredgnr/reqws-desktop/actions/runs/36219318927) 对 head `b70e452`、合并候选 `72def816` 执行。18 项 Electron、两项故障探针与精确包 smoke 通过，但项目检查发现 main 已将 `getOriginUrl` 改为私有，新增测试因此类型检查失败。测试改用公开 `run` 和 `originUrlMatches`；未放宽生产可见性。
- [第二轮 CI](https://github.com/fredgnr/reqws-desktop/actions/runs/36219763922) 对 head `ea90aeb` 完整通过，包括源码、负向、同包、项目和保留的 GoLand/API 门禁。随后本地同步 main `677ef00`，避免沿用旧运行时代码的稳定性结论。
- main 的 GitRunner 新增独立进程组。只读复核发现测试清理遗漏 client；`4831585` 将 host 和 Main 的 Git client 都登记到本轮 registry，保留生产 detached 行为。按 fixture HOME 标记 scope；删除前只读探测未关闭的 Git 组，存活或身份不明则保留目录，终止仍由核对完整进程身份的 runner 负责。真实 `GitRunner.run(['hash-object', '--stdin'])` 回归证明运行中拒删、Python 精确终止、无关 Node 进程存活、确认 close 后才删除；HTTPS/Git fixture 共 8/8 通过，D04 另核对实际 Main 的登记。
- `4831585` 本机 `npm run check` 通过：49 文件、512 项通过、1 项按既有规则跳过，338-key i18n、26 索引/110 文档及类型/lint 均通过。核心 smoke 20 轮共 80/80 通过，0 skip/flaky/retries，报告在 `test-results/final-stability-4831585/`；报告记录 clean commit `483158500762720f86c7180fd927a18f352b1664`。
- [第三轮 CI](https://github.com/fredgnr/reqws-desktop/actions/runs/36220222258) 对 head `4831585`、合并候选 `60e61e91` 执行。项目检查 513/513、工作流 177/177 和精确包 smoke 1/1 通过；runner 为 macOS 15.7.9 arm64，Node 24.20.0、Electron 43.4.0、Playwright 1.63.0。包报告核对两个独立 PID、真实 ASAR、renderer sandbox 和设置重启持久化。
- 第三轮源码结果为 17/18：D12 解除原子写挂起后，在首次状态文件发布前读取磁盘，遇到 ENOENT；失败 artifact 中收尾磁盘快照已包含目标设置。修复将等待顺序改为先观察 UI 从保存中返回已保存状态，再独立读取磁盘，不捕获 ENOENT 冒充成功，不增加自动重试。`npm run test:e2e -- tests/e2e/desktop/native-boundaries.spec.ts --grep 'D12 a real settings write' --repeat-each=20` 已 20/20 通过；类型和受影响 lint 通过。补丁以 `56657ea` 提交；第三轮仍是失败结果，不因后续修复而改记为通过。

### 最终代码候选验证

[修复后 CI](https://github.com/fredgnr/reqws-desktop/actions/runs/36236695695) 的 head 为 `56657ea`，实际 tested merge commit 为 `f2d90844`。以下为已执行的 Desktop 检查，不把单个 job 的通过当作整轮 CI 结论；原有 GoLand/API 最终聚合以 PR 必需检查为准。

| 检查 | 实际结果 |
|---|---|
| Project and workflow checks | Vitest 49 文件、513/513；Python 工作流 177/177；类型、lint、338-key i18n、26 索引/110 文档通过。 |
| Desktop Electron regression | 18/18，包含修复后的 D12；单 worker，零 retry、skip、flaky。 |
| 两项故障探针 | 断 preload 与早期 renderer 错误按精确原因失败；wrapper 验证报告、真实 DOM/trace/截图、Main/磁盘及退出证据后返回 0。本机同一代码候选亦再次通过。 |
| macOS package smoke | 同次构建的显式 ad-hoc `.app` 1/1 通过；真实 ASAR/bridge/资源、renderer OS sandbox、设置保存、完整退出和新 PID 重启通过。未再次构建测试替代包。 |
| Checks and macOS package smoke | 所有适用 Desktop 依赖成功后，保留名称的聚合门禁通过。失败、取消、零测试、skip 和缺报告的拒绝行为仍由工作流负向测试保护。 |
| 稳定性与等待顺序 | `4831585` 上核心 20 轮共 80/80；后续 `56657ea` 仅调整不属于 smoke 的 D12 断言顺序，未改变应用或 fixture 运行时代码。该 D12 在提交前的精确补丁上另外连续 20/20，通过后由本轮完整 Desktop 套件再验证。 |

源码和精确包使用相同 Electron 43.4.0、Playwright 1.63.0、Node 24.20.0；真实 runner 为 macOS 15.7.9 arm64。详细报告绑定候选及 profile；本机 macOS 15.7.3 的稳定性数字不冒充托管 runner 的重复运行统计。

远端报告分别保留在 `ci-desktop-e2e`、`ci-desktop-packaged` artifact，保留期 7 天。源码和包报告同时记录 PR head 与实际 tested merge commit；本地通过、某一 job 通过和整轮 CI 通过分开记录。

### 后续阶段与保留边界

- S4 Desktop→IDE 联动和 V 替代验收不在本轮实施范围。旧手工步骤保持[登记表](manual-inventory.md)中的保留状态；没有测量 Computer Use 成本，不宣称达到 80% 降低目标。
- 签名、Finder/系统信任、真实编辑器加载及真实两版本自更新都不由本轮 OS adapter、CI ad-hoc 包或源码 E2E 证明。

## S4 本机联动

本记录区分 S4 联动实现、CI 检查和获准执行后的本机 IDE 实际结果。

### 1. 范围与当前结论

基于 `feat/playwright-automation` 的 `4f5ff89` 扩展 S4；原 S0–S3 证据仍见[S0–S3 记录](#s0-s3-desktop-自动化)。初始 S4 宿主实现不改生产行为；实跑后另获准修复插件初始 JPS 同步，并调整精确 API 例外的验证入口。manifest schema、SDK、签名/发布授权与兼容性下限不变：仍支持整个 `262` 系列，无上限；完整 GUI 代表仍为 GO 2026.2.1.1 / 262.9437.286。

**S4 联动已通过同候选完整实跑。** 插件使用 `16ffce1` 的 CI 原始 ZIP，Desktop/宿主冻结为 `1652c8f`，固定代表 IDE 和专用 profile 不变；三项测试、六个独立完整进程、二十条投影及四个工作区原生落盘证明通过，见第 7 节。首轮失败和修复过程保留在第 4–6 节。V 和旧手工门槛保持原状，不涉及日常 IDE 或真实用户工作区。

### 2. 实现与真实性

现有 [run_local_ide.py](../../../scripts/run_local_ide.py) 增加 `run --suite desktop`，对应 `npm run check:goland:desktop`；默认 `legacy` 三组场景及九进程要求保留。profile 独占锁覆盖两端，本轮目录、显式 ZIP、授权准备、固定 SDK、退出检查和报告复用原入口。新增套件要求冻结干净 Desktop commit，前后核对 Git 身份；验证报告同时核对该源码和精确 ZIP。

- [Desktop spec](../../../tests/e2e/local-ide/desktop-link.spec.ts) 使用实际构建的 Electron、preload、renderer、真实 Main 服务和隔离 HTTPS Git。通过 UI 创建四个工作区、GoLand 入口及加载选择；普通用户文件与原生用户模块仅用于保留断言。
- [文件协议](../../../tests/e2e/fixtures/desktop-link.ts)及 [Driver 消费者](../../../integrations/goland/src/integrationTest/kotlin/com/reqws/goland/DesktopLink.kt) 只接受固定场景名和两仓库选择。会话 UUID、序号、原子不可覆盖消息、路径/目录身份及独立文件回读约束跨进程关系；产品无新增控制端点。
- [Driver 场景](../../../integrations/goland/src/integrationTest/kotlin/com/reqws/goland/DesktopWorkspaceIntegrationTest.kt) 要求 `desktopSelectionAndColdProcesses`、`desktopTrustTransitionUsesRealUi`、`desktopInvalidInputsPreserveUserModel` 三项全部执行，合计六个独立 IDE 进程、二十条投影证据。
- 成功路径由 Desktop UI 唯一写入业务文件。Driver 核对 workspace/binding/revision、从真实输入计算的 digest、加载 IDs、模块根、PFI、普通 Project 树和用户内容；不调用 Refresh、Sync Now 或内部同步函数。故障专项仅在本轮固定文件注入坏 JSON/身份冲突，恢复原字节后检查真实 watcher 恢复。
- Trust 专项通过真实 Safe Mode 和 Trust Project 对话框，不预信任该项目，禁用全局 trust bypass，并只读核对 IDE trust 状态。它不代替授权登录。
- [协调器](../../../scripts/desktop_ide.py) 与[报告门禁](../../../scripts/check_ide_test_reports.py) 拒绝失败、取消、超时、空/skip/重复测试、缺步骤、错源码/ZIP、树/PFI不一致或异常进程退出。两端退出无法确认时保留 active-session；Desktop fixture 保留在私有报告目录，避免提前删除 IDE 使用中的文件。

CI 继续只编译宿主并运行平台/API 检查；无工作流完整 IDE、许可 Secret 或生产 Driver 依赖。报告和 profile 不提交、不上传；原始私有诊断分享前需单独审阅。

### 3. 已执行检查

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

### 4. 获准首轮执行与修正

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

### 5. 待执行与保留范围

冷启动修复及 S4 落盘门禁已在第 7 节候选上完成；保留以下历史失败以说明覆盖缺口和修复依据。全部结果以同一精确 ZIP、冻结 Desktop commit、独立完整进程与新鲜报告为准；遇需用户协助事项停止等待回复。

原三组 `legacy` 宿主也已获准执行，私有目录标识 `reqws-local-ide-j3gxzzqk`：`atomicSelectionAutomaticallyRefreshes` 通过；`loadingAndProjectTree` 因宿主下载 TLS 握手失败而失败；`emptyAndNonemptySurviveColdProcesses` 因固定 IDE 的 Marketplace 插件列表缓存 JSON 被截断而失败。合计三项执行、一项通过、两项失败，八个已启动 IDE 进程全部退出，专用 profile 已释放；未达到九进程门槛。保留严格 IDE 错误门禁，未屏蔽网络/缓存异常，不能借用 2026-09-22 结果声称新宿主通过。

V 后续仍需逐项核对[旧步骤登记](manual-inventory.md)，特别是 Excluded Files 两态、late-shell、额外 repo3 用户覆盖、完整负向破坏实验及成本/稳定性口径；本轮不撤销其要求。

执行参数、私有报告与 `verify-report --suite desktop` 的使用见[本机入口](../ide-plugin-compatibility-automation/local-integration.md#desktop-真实-ui-联动)。

### 6. 已授权 API 例外

用户于 2026-09-26 明确批准仅为 `WorkspaceModelInternal.awaitSynchronizationWithJpsModel` 引入受控 `@Internal` 例外，并确认目前没有公开 API 或组合提供同等能力。[AGENTS.md](../../../AGENTS.md) 和[插件标准](../../standards/ide-plugin-development-testing.md#3-插件实现约束)同步记录该单独例外。修复方向是在任何 journal/model 写入前可取消地等待初始 JPS 同步，返回后重新核对 generation、trust 和 binding，并保留所有权检查。它不等待语言服务、不提高最低 262、不允许其他内部 API。

源码/JAR 与 Verifier 必须将例外限制在单一包装器及精确成员，保留其余阻断级别和原始报告。后续先验证整个 262 基线/API 矩阵上的接口与取消行为，再对新候选执行 S4、legacy 及原生落盘检查；不能把此前 ZIP 的 CI 或局部 GUI 证据移用于生产修复后的 ZIP。

已读取缓存的最低 262.8665.270、262.8665.336、262.9437.195、固定 262.9437.286 和 262.10315.135 SDK：方法签名一致，类型有 Internal 注解，点名方法另有 Experimental 注解。两类注解的例外都只覆盖这一个使用点；源码读取不等于候选 API 矩阵通过。

平台方法有一分钟软超时：它可能正常返回并留下共享超时状态，调用方取消也不会取消平台计时器。因此包装器采用项目生命周期内一次共享等待、30 秒硬超时、失败记忆且不重试；单个候选取消只取消自己的等待，不能反复启动平台计时器。超时继续阻止 ReqWS 写入。其他平台调用者此前留下的共享软超时状态无法通过获准 API 读取，该限制仍存在；不得宣称该方法在任意历史平台状态下提供绝对加载完成保证。S4 同时保留真实树/PFI和退出后原生落盘门禁。

#### 实现与当步检查

`InitialJpsSynchronization` 是唯一受限 API 包装器；`ManagedRootsAdapter` 在目录/模型锁和 journal 之前等待，等待前后检查取消和候选有效性；`LoadedProjectionService` 在根应用成功后才发布 shell 隐藏。新增三项共享等待/取消/失败记忆测试及五项 adapter 安全测试。直接受影响的三十三项测试通过，随后完整插件 baseline 三百七十四项通过，零跳过、零失败。初轮一项测试把异常对象身份当作断言，受协程栈恢复复制影响；修正为核对异常类型、消息、调用次数和 scope 存活后重跑通过。

`scripts/ide_api_exception.py` 同时核对实际 ZIP/JAR 指令和原始 Verifier 报告，只允许固定私有 `awaitPlatform(Project, Continuation)` 内一次精确 cast 与接口调用。所有 Gradle failure levels 保留，原始任务仍如实非零退出；统一 runner 仅接受两行 Internal、一行 Experimental 和唯一可解释的 `verifyPlugin` 失败，其余内容或失败仍阻断。最低/固定目标统一走 `--baseline`，CI、weekly、release 和本机原有完整矩阵继续保留；签名后仍按最终 ZIP 重新核验。二十项新门禁检查及整合后的二百零八项 Python workflows 通过。独立只读审查未发现生产修复或例外门禁的剩余明确问题，审查不计作测试执行。

Desktop 全检在授权环境为五百一十八项通过、一项原有 hosted-only 跳过，类型/lint/i18n/文档检查通过；首轮沙箱阻断 loopback 与一次性 macOS fixture，未改标跳过。Starter 改用公开 `useRelease(version)` 避免无关 EAP/preview 查询，保留固定 SDK 的 ProductInfo 强核对；坏 Marketplace JSON 仅位于旧 run root，新轮自然使用新目录，未修改 profile 的账号或许可文件。该检查点尚未完成的完整 API 矩阵和新 ZIP 的 S4/legacy 实跑，后续结果独立记于下文。

`16ffce1` 的[完整 CI](https://github.com/fredgnr/reqws-desktop/actions/runs/36243622124)已全部通过，包括七个 API 目标；已下载原始 ZIP/报告并严格核对同一候选。其 ZIP 与本机重建 ZIP 字节不同，因此 GUI 选择已核验的 CI 原始 ZIP，本机慢速下载中的矩阵仍独立记录。为避免共享 Gradle 输出，在同一提交的隔离 checkout 编译宿主和运行 Electron，仍独占同一专用 IDE profile。

隔离首轮 `reqws-local-ide-futqtkvq` 在任何 IDE 启动前失败：Starter ZIP reader 尝试可写打开只读输入，解包失败清理又删除了本轮下载的临时 CI ZIP。Desktop 已退出、profile 已释放；未触碰用户原有产物。随后修正本机入口：原 ZIP 保持只读，先创建本轮私有可写的精确字节副本再交给 Starter；每 context 安装前后、成功前都校验候选，报告记录原件和副本。新增测试模拟安装器删除副本，确认只读原件完整保留，并拒绝错摘要、链接和已存在的 staging 目录。恢复的 CI ZIP 必须重新核对原 CI 证据，不能将该失败轮记作通过。

副本修正 `e3e1211` 的二百一十项 Python workflows 通过。使用恢复并重新核验的同一 CI ZIP 运行 `reqws-local-ide-lc0x7t04`：选择/冷启动场景通过三个独立完整进程，八条实时投影及退出后原生 `.iml` 两根/marker、journal、用户模块的只读复核通过。Trust 和非法输入场景在 IDE 启动前被发布目录 TLS 握手失败阻断，整套仍失败。原只读 ZIP 保留完整，所有已启动进程退出、profile 已释放；局部冷启动成功不能代替 S4 GO。

随后宿主通过公开 `IdeInstaller` 和 `DefaultIdeDistributionFactory` 复用已校验的固定 SDK，避免每个 context 重复联网解析。仅缓存确实缺失才走官方安装器；已存在但身份、路径不符的缓存直接失败。进入工厂前拒绝 JBR override/backup，防止工厂替换运行时；每 context 创建独立描述并再次核对身份。没有新增受限 API，最低 262 与原始候选 ZIP 不变；完整重跑结果见第 7 节。

### 7. 修复后的同候选验证

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
