# GoLand 插件支持测试与验证

本目录保存 GoLand 插件支持的测试范围和后续按次验证证据。

- 状态：active
- 阶段：验证中
- 更新日期：2026-08-30

## 文档

| 文档 | 类型 | 状态 | 说明 |
|---|---|---|---|
| [测试方案](test-plan.md) | test-plan | active | 定义 Desktop、插件、JetBrains Platform、Plugin Verifier、真实 macOS GUI、规模和安全测试。 |
| [2026-08-17 GUI 验收报告](verification-2026-08-17.md) | test-report | active | 记录日常 GoLand 2026.1.3 的真实 GUI 结果、project-dispose 阻塞异常、未完成矩阵和 `NO-GO` 结论。 |

[2026-08-17 报告](verification-2026-08-17.md)绑定旧候选 ZIP（SHA-256 `c1cd6966869bae86d72b998e401d3a9a470e19f6811f51248b3cb763988d9609`）及当时的 164 项测试证据，并因可复现的 project-dispose 异常、Project View 刷新缺口、矩阵未完成和缺少 exact commit 判定为 `NO-GO`。

当前产品契约是自动同步 Project Model、只读诊断 VCS，并由用户在 GoLand Directory Mappings 中手动维护 Git Roots。插件不调用 mapping mutation API、不直接写 `.idea/vcs.xml`；GoLand 原生 auto-detection 仍由 IDE/用户设置控制并需单独归因。旧 `.idea/reqws-vcs-ownership.json` 与 lock 为 inert 文件，不读取、不迁移、不自动清理。2026-08-30 当前源码候选在既有 sticky reconcile、two-phase VCS listener、stable native descriptor、stable `.idea` directory-inode `flock` 和 exact-publication cancellation CAS 基础上，补充首次 blank `INACTIVE` 的一次性 service-scope automatic retry。平台回归从单次 `ReqwsStartupActivity.execute` 分别覆盖首次 read/apply 的 PCE 与 coroutine cancellation，并覆盖 post-registration listener cleanup/re-register、retry 上限（read/apply）、ordinary listener failure、dispose、owner scope cancellation、newer-generation supersede 与 exact state-version winner；测试不直接调用 refresh 代替 production startup recovery。当前 exact-source 候选的 JDK 21 完整命令通过 31 个 XML suites、281 项插件测试（零 skipped/failure/error）、项目配置/结构检查、GO-261.25134.147 / GO-262.8665.270 fresh Plugin Verifier（均 `Compatible`）和 ZIP 构建；ZIP SHA-256 为 `9746925dad410016187d1f9859829dfa77ca020d167f4a87f9261afbac6e2fc8`，大小 484,669 bytes。Desktop `npm run check` 同时通过 typecheck、ESLint、i18n、文档检查，以及 31 个测试文件、335 项测试。真实 GUI 仍须覆盖生产无 VCS writer、手动配置步骤、配置事件自动复核、`Sync Now` 只读检查、用户 mapping/`rootSettings`、纯 inspection 的 `.idea/vcs.xml` 保持、平台原生变化归因，以及 old ownership/lock inert，并绑定推送后的 exact commit；在此之前不形成 `GO`。[按原型收口后的同步态实现截图](../ui/tool-window-implementation-2026-08-17.png)仍只用于旧候选界面参考；[2026-08-17 报告](verification-2026-08-17.md)正文保持历史 `NO-GO` 证据。
