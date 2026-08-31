# GoLand 插件支持测试与验证

本目录保存 GoLand 插件支持的测试范围和后续按次验证证据。

- 状态：active
- 阶段：验证中
- 更新日期：2026-08-31

## 文档

| 文档 | 类型 | 状态 | 说明 |
|---|---|---|---|
| [测试方案](test-plan.md) | test-plan | active | 定义 Desktop、插件、JetBrains Platform、Plugin Verifier、真实 macOS GUI、规模和安全测试。 |
| [2026-08-31 待修复与未完成验证清单](open-bugs-and-verification-work-2026-08-31.md) | test-report | active | 记录已完成的证据隐私修复和远端可取得性门禁、四组待 GUI 回归关闭的历史产品缺陷、当前 exact implementation commit 与未完成矩阵。 |
| [2026-08-30 GUI 验收报告](verification-2026-08-30.md) | test-report | archived | 绑定历史本地 exact HEAD 与 ZIP；安装和 repo-a Go/Git smoke 通过，但 manifest 自动同步与 reactivated repository live ProjectFileIndex 失败，结论为 `NO-GO`。 |
| [2026-08-17 GUI 验收报告](verification-2026-08-17.md) | test-report | archived | 保存旧候选的 project-dispose 异常、Project View 刷新缺口和 `NO-GO` 历史证据。 |

[2026-08-31 清单](open-bugs-and-verification-work-2026-08-31.md)是当前待办状态的入口：代码复核未发现剩余 P0/P1，automatic watcher、live `ProjectFileIndex`、Go Modules registry/PACKAGE 与 Tool Window truthfulness 四组历史缺陷已有自动化修复但仍缺动态 GUI 关闭证据。现有 tracked 截图/文档隐私已收口，exact implementation commit 已推送并可从配置的 GitHub `origin` 取得；完整 §8 矩阵仍未完成，因此 verdict 继续为 `NO-GO`。

当前 implementation commit 为 `26fb3c6517b1fcb46b2e82ed9336e24b1d2a8945`。JDK 21 完整命令通过 35 个 XML suites、345 项插件测试（0 skipped/failure/error）、44 个 production 文件与 293 个 composed-JAR class 的 forbidden-symbol gate、项目配置/结构检查，以及 GO-261.25134.147 / GO-262.8665.270 两版 Plugin Verifier（均 `Compatible`）。exact ZIP SHA-256 为 `b8fec9f55ff15f98532dc898d044dfbbb253024fff5081e7f167651a435b35df`（566,230 bytes），ZIP 内 JAR SHA-256 为 `17f20c0dce78f933352eb1b9c219839c2cdd2763973c5fd1f262ed51731072f2`（606,936 bytes）。Desktop `npm run check` 同时通过 31 个文件、335 项测试。该 ZIP 尚未完成当前 commit 的 GUI 安装与第 8 节验收。

[2026-08-30 报告](verification-2026-08-30.md)绑定本地 HEAD `768090d2783675f81b44471c4941de11e28c7d1e`、ZIP SHA-256 `9746925dad410016187d1f9859829dfa77ca020d167f4a87f9261afbac6e2fc8` 和 exact packaged Desktop。真实 GoLand 2026.1.3 GUI 已证明安装、初始双仓库投影、手动 Directory Mappings 与 repo-a 主要 Go/Git smoke 可用；同时复现三次 Desktop manifest 变化都不自动同步，repo-c 与重新加入的 repo-b 在 authoritative Workspace Model/`.iml` 已移除 exclude 后，live `ProjectFileIndex`/Project/Search 仍未收敛，Tool Window 又错误显示 `Synced`。同 JVM recovery claims 保留符合设计，不是失败原因。本地 commit 尚未推送，恢复、视觉/无障碍、配置保护和多仓 Go/Git 矩阵也未完成，因此当前结论为 `NO-GO`。

[2026-08-17 报告](verification-2026-08-17.md)已归档；它继续绑定旧候选 ZIP（SHA-256 `c1cd6966869bae86d72b998e401d3a9a470e19f6811f51248b3cb763988d9609`）并保存当时的 project-dispose 异常与 Project View 刷新缺口，不能替代当前候选证据。

当前产品契约是自动同步 Project Model、只读诊断 VCS，并由用户在 GoLand Directory Mappings 中手动维护 Git Roots。插件不调用 mapping mutation API、不直接写 `.idea/vcs.xml`；GoLand 原生 auto-detection 仍由 IDE/用户设置控制并需单独归因。旧 `.idea/reqws-vcs-ownership.json` 与 lock 为 inert 文件，不读取、不迁移、不自动清理。作为历史记录，2026-08-30 exact-source 候选的 JDK 21 完整命令通过 31 个 XML suites、281 项插件测试（零 skipped/failure/error）、项目配置/结构检查、GO-261.25134.147 / GO-262.8665.270 fresh Plugin Verifier（均 `Compatible`）和 ZIP 构建；Desktop `npm run check` 同时通过 typecheck、ESLint、i18n、文档检查，以及 31 个测试文件、335 项测试。真实 GUI 当时确认 VCS 生产路径保持只读，平台 auto-detection 能单独归因，old ownership/lock 保持 inert；但 manifest 自动监听和 reactivated repository live project content 收敛失败，阻断后续多仓矩阵。[历史浅色同步态实现截图](../ui/tool-window-implementation-2026-08-30.png)只作为该候选界面证据，最终状态以[2026-08-30 报告](verification-2026-08-30.md)的 `NO-GO` 为准。
