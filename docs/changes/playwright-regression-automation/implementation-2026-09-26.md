---
title: Playwright S0–S3 实施与验证记录
type: test-report
status: active
updated: 2026-09-26
---

# Playwright S0–S3 实施与验证记录

本记录区分当前分支的实现、实际本机执行结果、CI 候选证据及仍保留的手工验收边界。

## 候选与环境

- 分支 `feat/playwright-automation`，基线 `39c80b78de4fb36fce2307d9b896f4c4346d7ac9`；当前改动以本分支 Git diff 定位。未提交或推送的差异不能冒充该基线提交已经包含的实现。
- 本机 macOS 15.7.3（24G419）、arm64、Node 24.20.0、Electron 43.4.0、Playwright 1.63.0。Playwright 使用仓库 Electron runtime，没有另行下载浏览器矩阵。
- 每次源码入口重新构建 `.vite/e2e` 的 Main、真实 preload 和 renderer；测试实例使用独占临时 HOME、userData、sessionData、logs、origin 和输出目录。未使用真实 ReqWS 数据、工作区或日常 IDE。
- 插件最低版本影响：未改变插件 API、SDK、manifest/selection schema 或 IDE 入口协议；最低仍为整个 262 系列，无 `until-build` 或 `strict-until-build`。本轮 Desktop 选择测试不证明 GoLand 模型或 Project 树结果。

## 阶段结论

| 阶段 | 当前结论 |
|---|---|
| S0 | 手工清单、当前 Electron/Playwright、本机构建产物、sandbox、HTTPS Git、负向证据均已验证；托管 runner 的实际执行仍待 S3 远端证据。 |
| S1 | 本机通过：锁前隔离、真实装配、PID/目录归属、正常及阻止退出的清理、生产 ASAR 排除均有检查。 |
| S2 | 本机通过：D01–D12 代表场景与独立磁盘/Git 断言，共 18 项集成用例通过。 |
| S3 | 实现与本地门禁/稳定性检查通过；真实 CI 和同次构建 `.app` 的实际启动未运行，不能标记完整通过。 |
| S4 / V | 未实施；未撤销旧手工门槛。 |

## 实现边界

生产入口与外置测试入口共用 bootstrap。路径设置同步发生在 single-instance lock 之前；真实 AppStateStore、WorkspaceFileWriter、GitRunner、WorkspaceService、IPC/Zod、preload、窗口安全、activity gate 和 mutation coordinator 均保留。生产入口没有测试环境开关或控制协议。

允许的替换位于系统边界：原生选目录返回值、编辑器最终 spawn、updater adapter、指定原子发布的单次 OS 错误/等待。故障和控制对象仅在外置 test-main。Forge 显式只收集生产 build/renderer，排除 `.vite/e2e` 和源测试代码；还需以最终 `.app` 内实际 ASAR 验证，而非只依赖源配置。

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

## 最终本机集成验证

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

## 尚待远端和后续阶段验证

- 同一次 CI 打包生成的精确 `.app` 启动、设置持久化、退出和 ASAR 排除证据。此入口仅允许真实 GitHub-hosted 一次性 macOS runner，不在本机伪造 CI 环境；本机已做的 ASAR 检查不能替代它。
- 真实远端 CI 执行与 macOS runner 可行性；本地 Python 工作流测试不能替代远端运行证据。
- S4 Desktop→IDE 联动和 V 替代验收不在本轮实施范围。旧手工步骤保持[登记表](manual-inventory.md)中的保留状态；没有测量 Computer Use 成本，不宣称达到 80% 降低目标。

2026-09-26 用户已授权将本轮范围内改动提交、推送到 `feat/playwright-automation` 并运行分支 CI；此处尚未记录远端通过结果，S3 完成仍需取得同一提交的实际证据。签名、Finder/系统信任、真实编辑器加载及真实两版本自更新都不由本轮 OS adapter 或源码 E2E 证明。
