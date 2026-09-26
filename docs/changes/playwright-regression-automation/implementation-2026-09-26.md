---
title: Playwright S0–S3 实施与验证记录
type: test-report
status: active
updated: 2026-09-26
---

# Playwright S0–S3 实施与验证记录

本记录区分当前分支的实现、实际本机执行结果、CI 候选证据及仍保留的手工验收边界。

## 候选与环境

- 分支 `feat/playwright-automation`，初始基线 `39c80b78de4fb36fce2307d9b896f4c4346d7ac9`；实现提交 `b70e452`，公开 Git 接口修复 `ea90aeb`。随后合并 main `677ef00`，并以 `4831585` 补齐独立 Git 进程组登记。最终代码候选 `56657ea7586ba1b6f0820a13ffbab50749ce4928` 修复 D12 等待顺序；对应 CI 实际 tested merge commit 为 `f2d90844fe938b7cdd372656b637d794ee94da10`。后续仅更新本记录与索引，不改变运行时代码或测试。
- 本机 macOS 15.7.3（24G419）、arm64、Node 24.20.0、Electron 43.4.0、Playwright 1.63.0。Playwright 使用仓库 Electron runtime，没有另行下载浏览器矩阵。
- 每次源码入口重新构建 `.vite/e2e` 的 Main、真实 preload 和 renderer；测试实例使用独占临时 HOME、userData、sessionData、logs、origin 和输出目录。未使用真实 ReqWS 数据、工作区或日常 IDE。
- 插件最低版本影响：未改变插件 API、SDK、manifest/selection schema 或 IDE 入口协议；最低仍为整个 262 系列，无 `until-build` 或 `strict-until-build`。本轮 Desktop 选择测试不证明 GoLand 模型或 Project 树结果。

## 阶段结论

| 阶段 | 当前结论 |
|---|---|
| S0 | 通过：手工清单、当前 Electron/Playwright、sandbox、HTTPS Git、故障证据及真实 macOS runner/同包启动均已验证。 |
| S1 | 通过：锁前隔离、真实装配、PID/目录归属、正常及阻止退出的清理、生产 ASAR 排除均有检查；同步 main 后补齐 detached Git 的登记与删除前存活检查。 |
| S2 | 通过：最终代码候选在真实 CI 的 D01–D12 共 18 项通过；D12 等待竞态修复另有本机连续 20 次通过。 |
| S3 | 通过：最终代码候选的源码、负向、同包、项目及 Desktop 聚合门禁通过；核心 smoke 80/80，0 skip/flaky/retries。保留原有插件/API 检查，未撤销旧手工门槛。 |
| S4 / V | 未实施；未撤销旧手工门槛。 |

## 实现边界

生产入口与外置测试入口共用 bootstrap。路径设置同步发生在 single-instance lock 之前；真实 AppStateStore、WorkspaceFileWriter、GitRunner、WorkspaceService、IPC/Zod、preload、窗口安全、activity gate 和 mutation coordinator 均保留。生产入口没有测试环境开关或控制协议。

允许的替换位于系统边界：原生选目录返回值、编辑器最终 spawn、updater adapter、指定原子发布的单次 OS 错误/等待。故障和控制对象仅在外置 test-main。Forge 显式只收集生产 build/renderer，排除 `.vite/e2e` 和源测试代码；已在本机及 CI 同次生成的 `.app` 内检查实际 ASAR，而非只依赖源配置。

Git fixture 使用临时 CA 和 loopback HTTPS，经真实 `git http-backend` clone/fetch；不关闭 TLS、不导入钥匙串、不允许本地路径 URL，不使用 insteadOf。测试 spawn wrapper 在生产 sanitizer 之后设置固定的无系统配置策略，使用隔离 HOME/XDG、空 hooks/templates、非交互凭据策略及环境 allowlist。

Playwright 显式 `chromiumSandbox: true`，运行时核对 `app.getAppMetrics()` 的 renderer OS sandbox、无 `--no-sandbox`，并确认独立 preload 执行上下文与页面没有裸 Node API。Chromium 对既有 HTML meta `frame-ancestors` 指令的固定诊断单独保存在 console 证据；它在 meta 中无效，不能当作已生效的安全保证，其余 renderer error 仍使测试失败。窗口导航/popup 策略保留。

## 已执行的开发检查点

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

## 合并主分支前的本机集成验证

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

## 主分支同步与 CI 修复

用户已授权提交、推送和分支 CI；本轮沿用 [PR #22](https://github.com/fredgnr/reqws-desktop/pull/22)，未合并或发布。

- [首轮 CI](https://github.com/fredgnr/reqws-desktop/actions/runs/36219318927) 对 head `b70e452`、合并候选 `72def816` 执行。18 项 Electron、两项故障探针与精确包 smoke 通过，但项目检查发现 main 已将 `getOriginUrl` 改为私有，新增测试因此类型检查失败。测试改用公开 `run` 和 `originUrlMatches`；未放宽生产可见性。
- [第二轮 CI](https://github.com/fredgnr/reqws-desktop/actions/runs/36219763922) 对 head `ea90aeb` 完整通过，包括源码、负向、同包、项目和保留的 GoLand/API 门禁。随后本地同步 main `677ef00`，避免沿用旧运行时代码的稳定性结论。
- main 的 GitRunner 新增独立进程组。只读复核发现测试清理遗漏 client；`4831585` 将 host 和 Main 的 Git client 都登记到本轮 registry，保留生产 detached 行为。按 fixture HOME 标记 scope；删除前只读探测未关闭的 Git 组，存活或身份不明则保留目录，终止仍由核对完整进程身份的 runner 负责。真实 `GitRunner.run(['hash-object', '--stdin'])` 回归证明运行中拒删、Python 精确终止、无关 Node 进程存活、确认 close 后才删除；HTTPS/Git fixture 共 8/8 通过，D04 另核对实际 Main 的登记。
- `4831585` 本机 `npm run check` 通过：49 文件、512 项通过、1 项按既有规则跳过，338-key i18n、26 索引/110 文档及类型/lint 均通过。核心 smoke 20 轮共 80/80 通过，0 skip/flaky/retries，报告在 `test-results/final-stability-4831585/`；报告记录 clean commit `483158500762720f86c7180fd927a18f352b1664`。
- [第三轮 CI](https://github.com/fredgnr/reqws-desktop/actions/runs/36220222258) 对 head `4831585`、合并候选 `60e61e91` 执行。项目检查 513/513、工作流 177/177 和精确包 smoke 1/1 通过；runner 为 macOS 15.7.9 arm64，Node 24.20.0、Electron 43.4.0、Playwright 1.63.0。包报告核对两个独立 PID、真实 ASAR、renderer sandbox 和设置重启持久化。
- 第三轮源码结果为 17/18：D12 解除原子写挂起后，在首次状态文件发布前读取磁盘，遇到 ENOENT；失败 artifact 中收尾磁盘快照已包含目标设置。修复将等待顺序改为先观察 UI 从保存中返回已保存状态，再独立读取磁盘，不捕获 ENOENT 冒充成功，不增加自动重试。`npm run test:e2e -- tests/e2e/desktop/native-boundaries.spec.ts --grep 'D12 a real settings write' --repeat-each=20` 已 20/20 通过；类型和受影响 lint 通过。补丁以 `56657ea` 提交；第三轮仍是失败结果，不因后续修复而改记为通过。

## 最终代码候选验证

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

## 后续阶段与保留边界

- S4 Desktop→IDE 联动和 V 替代验收不在本轮实施范围。旧手工步骤保持[登记表](manual-inventory.md)中的保留状态；没有测量 Computer Use 成本，不宣称达到 80% 降低目标。
- 签名、Finder/系统信任、真实编辑器加载及真实两版本自更新都不由本轮 OS adapter、CI ad-hoc 包或源码 E2E 证明。
