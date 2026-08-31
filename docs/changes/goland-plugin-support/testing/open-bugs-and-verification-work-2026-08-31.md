---
title: GoLand 插件待修复 Bug 与未完成验证清单（2026-08-31）
type: test-report
status: active
updated: 2026-08-31
---

# GoLand 插件待修复 Bug 与未完成验证清单（2026-08-31）

本文把最终源码候选的已知缺陷、待回归项、证据缺口和交付阻塞分开记录；在同一 exact commit 完成[测试方案第 8 节](test-plan.md#8-macos-真实-gui-smoke)前，结论保持 `NO-GO`。

## 1. 审计结论

- 最终候选上当前**没有新复现且可直接进入代码修复的 ReqWS 产品 Bug**。
- 旧候选确认过 4 组产品缺陷；当前实现和自动化声称已经修复，但真实 GUI 尚未完成对应的动态回归，因此只能标为“修复已实现，GUI 待关闭”，不能标为 `PASS`。
- 已确认的证据隐私问题已在 tracked 文档和资产中收口：禁用原图整组撤出可发布证据，当前纳入 Git 的 2026-08-30 PNG 已逐图复审，只包含隔离 fixture；后续 GUI 场景仍须重新截图并逐图复审。
- 当前 exact implementation commit 尚未完成 §8 GUI 验收；仅能保留历史候选在 §8.1、§8.2 的部分结果作为问题背景，不能继承为当前候选证据。§8.3 动态增删、§8.5 恢复和大部分 §8.6–§8.7 仍未完成。
- 当前源码已固定为 exact implementation commit，并已推送到配置的 GitHub `origin`；远端 tip 与本地 HEAD 的独立查询结果一致，implementation commit 可由远端 feature branch 取得。远端可取得性门禁已关闭，但 GUI 矩阵关闭前仍不能给出 `GO`。

状态定义：

| 状态 | 含义 |
|---|---|
| `待修复` | 已在当前证据范围内确认的问题，需要修改资产、文档或代码。 |
| `修复已实现，GUI 待关闭` | 历史缺陷已有实现和自动化覆盖，但最终候选缺少同场景真实 GUI 证据。 |
| `PENDING` | 尚未执行，或证据不足以判定通过/失败。 |
| `BLOCKER` | 阻止 `GO`，但不等同于产品代码 Bug。 |

## 2. 当前候选绑定

| 项目 | 当前值 | 判断 |
|---|---|---|
| candidate 类型 | 远端可取得的 exact implementation commit | 源码可由单一 Git commit 复现，并已由配置的远端 feature branch 提供。 |
| implementation commit / tree | `26fb3c6517b1fcb46b2e82ed9336e24b1d2a8945` / `b8fa0088b41759c3c03bc213707766f5f30d9ed2` | 已推送；独立远端 tip 查询与推送后的本地 HEAD 一致，且该 commit 是远端分支可取得的祖先。 |
| plugin ZIP | SHA-256 `b8fec9f55ff15f98532dc898d044dfbbb253024fff5081e7f167651a435b35df`，566,230 bytes | 从上述 exact implementation commit 重建；尚未用于新的 GUI 安装。 |
| ZIP 内 plugin JAR | SHA-256 `17f20c0dce78f933352eb1b9c219839c2cdd2763973c5fd1f262ed51731072f2`，606,936 bytes | 与上述 ZIP 绑定；尚无当前 exact commit 的 installed-JAR 哈希。 |
| 插件自动化 | 35 个 XML suites、345 项测试，0 skipped/failure/error；44 个 production 文件与 293 个 composed-JAR class 通过 forbidden-symbol gate | 自动化门禁通过；headless fixture 不能替代真实 Go registry/PACKAGE GUI。 |
| Plugin Verifier | GoLand 2026.1.3 与 2026.2 均 `Compatible` | 二进制兼容门禁通过。 |
| Desktop | `npm run check` 通过，31 个文件、335 项测试 | Desktop 产品代码未变；当前 exact commit 未重新执行 macOS package/安装 GUI。 |

只要源码、依赖或构建输入发生变化，上述 fingerprint、ZIP/JAR 哈希和已完成 GUI 证据都必须重新绑定。

## 3. 待修复 Bug 与回归关闭清单

### 3.1 已完成的证据隐私修复

| ID | 状态 | 问题 | 当前结果 | 后续约束 |
|---|---|---|---|---|
| EVIDENCE-PRIVACY-01 | RESOLVED | 历史验收资产混有不可发布的真实用户路径、非隔离 workspace、仓库上下文或无关第三方界面。 | 两份历史报告已撤销整组原始截图引用，不再记录禁用目录/文件名；唯一新增 tracked PNG 已确认只含 `repo-a`、`repo-b`、`repo-c` 隔离 fixture，文档与图片复审未发现私有仓库地址、凭据或真实用户绝对路径。 | 新 GUI 报告只能链接重新拍摄并复审通过的隔离 fixture 资产；每张图继续检查标题栏、历史窗口、URL、插件面板与本机路径。 |

后续验收一律只使用隔离 profile；不得把日常 profile、真实远端、浏览器/插件账户面板或无关应用窗口作为截图背景。

### 3.2 历史产品缺陷：修复已实现，GUI 待关闭

| ID | 历史缺陷 | 已有修复/自动化信号 | 尚缺的关闭证据 | 失败后的动作 |
|---|---|---|---|---|
| GUI-REG-01 | Desktop 原子替换 manifest 后，GoLand 未自动同步，旧候选三次都依赖 `Sync Now`。 | native watch、1 秒定向 refresh pump、原子替换事件与 debounce 回归已加入，自动化通过。 | 在最终候选完成 add repo-c、remove repo-b、re-add repo-b；三次都必须记录 automatic log，且 `Sync Now` 点击数为 0。 | 若任一步没有自动进入新 digest，重新打开代码修复，不得用 `Sync Now` 填补 automatic 验收。 |
| GUI-REG-02 | 移除 owned exclude 后 authoritative model 已更新，但 live `ProjectFileIndex`/Project/Search 仍把重新激活仓库视为 excluded。 | 显式 target/marker entity removal、roots-change 和 live PFI gate 的平台测试已加入，自动化通过。 | 对 repo-c add 与 repo-b re-add 分别留下 authoritative、PFI/Project/Search 的同时间线证据。 | 若 live 层未收敛，继续修 Project Model/roots event 路径，不能只看 `.iml` 或 Tool Window。 |
| GUI-REG-03 | 后续候选的 PFI 已改善，但 Go Modules registry 仍滞后，`Go Test Package` 显示 `Cannot find package`，需要 `Continue Anyway`。 | 最终候选加入只读 registry gate、最多一次普通 roots event 和有界等待；初始 repo-a/repo-b 配置可直接校验并运行，三仓冷启动下 repo-c 测试通过。 | 必须在 automatic add 后验证 repo-c，在 remove/re-add 后复用同一个 repo-b 配置；运行前无错误、无需 `Continue Anyway`，registry root 与活动集合一致。冷启动单帧不能替代动态场景。 | 若配置校验仍失败，继续修 registry 收敛路径或重新评审项目模型策略。 |
| GUI-REG-04 | live PFI/registry 未收敛时，Tool Window 仍错误显示 `Synced` / `Active`。 | `PROJECT_CONTENT_NOT_CONVERGED`、dirty baseline 与 view-model truthfulness 回归已加入，自动化通过。 | 分别受控制造 PFI 不收敛和 registry 不收敛；两种情况下整体不得显示 `Synced`，present repository 不得显示 `Active`，并显示稳定错误码。 | 若 UI 与 live 层不一致，继续修 success gate/state publication，不能只改显示文案。 |

同 JVM recovery claims 保留、retained repository mapping 触发 `Partially Available`、VCS 由用户手动维护，以及第三方插件产生的 startup `SEVERE` 均不是上述 Bug。

## 4. 测试方案第 8 节未完成工作

| 范围 | 当前状态 | 已确认 | 未完成工作 / 完成条件 |
|---|---|---|---|
| [§8.1 安装](test-plan.md#81-安装) | PENDING | 2026-08-30 历史候选曾通过 GUI 安装/重启、JAR 一致性和 startup-log smoke。 | 用当前 exact commit 的新 ZIP 重新安装并重启，记录 ZIP/JAR/installed JAR 哈希和日志；旧候选结果与截图不能继承。 |
| [§8.2 初次打开](test-plan.md#82-初次打开) | PARTIAL | 历史候选中 repo-a/repo-b 可见、repo-c excluded，Search 只返回 repo-a/repo-b；repo-a/repo-b 的 `PACKAGE` 配置可直接校验并各运行 1 项测试；idle 未观察到重复同步/索引循环。 | 在当前 exact commit 补 Desktop 点击因果链、trust flow、缺失 mapping → 手动配置 → 配置事件复核、`.reqws` excluded 后的 atomic replace、authoritative/PFI/registry/Tool Window 四层时间线、roots event 与 GoLand 原生进程归因。 |
| [§8.3 增删](test-plan.md#83-增删) | PENDING | 最终候选没有覆盖完整动态序列的证据。 | 完成 automatic add repo-c、手动 mapping、remove repo-b、旧配置失效、retained mapping 保护、用户手动移除 mapping、automatic re-add repo-b、复用原配置恢复、恢复 mapping；每一步都需四层证据、manifest SHA/mtime、耗时、事件数、registry wait 和文件哈希。 |
| [§8.4 Go 功能](test-plan.md#84-go-功能) | PARTIAL | 历史候选的初始 repo-a/repo-b `PACKAGE` validation/run 通过；后续本地候选的三仓冷启动 repo-c test 通过。 | 在当前 exact commit 补动态 add 后 repo-c、re-add 后 repo-b 的 package validation；完成三个目标场景的 completion、declaration、usages、test、main run/debug、Git diff/log/commit-root；记录 GoLand 原生 `go list`/下载归因和运行前后 module 文件哈希。 |
| [§8.5 恢复](test-plan.md#85-恢复) | PENDING | 只覆盖安装后的窄范围冷启动。 | 完成最终动态态 reopen、Desktop absent、malformed → valid、delete → restore、sleep/wake、disable/enable、独立 `Sync Now`、受控首次 cancellation；每次都复核四层与保存的 PACKAGE 配置。 |
| [§8.6 视觉与可用性](test-plan.md#86-tool-window-视觉与可用性) | PENDING | 只有历史候选的普通宽度、浅色初始态；已复审 PNG 只能说明当时界面形态，不能继承为当前候选证据。 | 对当前 exact commit 重新拍摄并逐图复审 synchronized/degraded/error/Safe Mode、PFI/registry 两类失败、深色、最窄宽度、长文本 tooltip、Tab/Shift+Tab、屏幕阅读器，以及 close project/exit 无 late update 或 dispose exception。 |
| [§8.7 用户配置保护](test-plan.md#87-用户配置保护) | PARTIAL | 历史候选确认 retained mapping 保留，并记录 go.mod hash、go.sum absent 与 Git clean。 | 在当前 exact commit 补 mapping 顺序/type/rootSettings 与 `.idea/vcs.xml` 前后 hash、非 ReqWS module/content root、ownership conflict、旧 ownership/lock inert，以及 add/remove/re-add 各阶段 module 文件 hash；GoLand 原生变化需独立归因。 |

静态截图只能证明画面瞬时状态，不能单独证明 automatic refresh、Desktop absent、restart、no-loop、startup exception、close/exit 或零文件写入；这些场景必须同时保留结构化日志、哈希或录屏证据。

## 5. 第 8 节之外的未完成交付工作

| ID | 优先级 | 工作 | 完成条件 |
|---|---|---|---|
| WORK-01 | P0 | 高风险动态回归 | 在当前 exact implementation commit 完成 §8.3 和 registry/PACKAGE 核心路径；任何失败先修代码并重新固定 commit/产物。 |
| WORK-02 | DONE | remote exact candidate | exact implementation commit 与文档提交已推送；独立远端 tip 查询匹配本地 HEAD，implementation commit 可由远端 feature branch 取得。 |
| WORK-03 | P1 | 规模与性能 | 完成 50 active + 20 retained、连续增删、100 次 rapid rewrite，并记录 event/apply、registry wait、CPU、内存与 indexing 稳定性。 |
| WORK-04 | P1 | 安全与完整回归 | 把自动化安全/对抗结果逐项映射到 dated verification，并补 Desktop 真实启动、VS Code、Cursor、Finder、workspace 增删、i18n 与 packaged app 手工回归。 |
| WORK-05 | DONE | 现有证据隐私收口 | EVIDENCE-PRIVACY-01 已完成；后续新报告继续只链接复审通过的隔离 fixture 资产，不复用禁用原图。 |
| WORK-06 | P0 | 最终 dated verification | 为 exact commit 创建新的 dated verification，记录环境、ZIP/JAR/installed JAR、四层时间线、automatic/manual、event/registry、平台原生进程归因、module 文件哈希、已知限制和单一 `GO`/`NO-GO` verdict；同步索引并归档被替代报告。 |

## 6. 下一轮执行顺序与停止条件

1. 安装当前 exact ZIP；GUI 全程只运行隔离 ReqWS profile。
2. 优先执行 §8.3 add/remove/re-add 和 §8.4 的 registry/PACKAGE 关键路径；这是决定是否还需改代码的最短路径。
3. 任一四层不一致、automatic 场景使用了 `Sync Now`、PACKAGE 依赖 `Continue Anyway`，或 Tool Window 错报成功时，立即保存脱敏证据并回到代码修复；不得继续累计伪通过项。
4. 核心动态路径通过后完成 §8.2、§8.5–§8.7、规模、安全与 Desktop 回归。
5. 完成当前 exact commit 的安装、完整门禁和 GUI 矩阵；最终报告不能混用旧候选截图、日志或哈希。

以下任一条件仍存在时 verdict 必须保持 `NO-GO`：

- GUI-REG-01 至 GUI-REG-04 任一未关闭；
- §8 任一必测项仍为 `PENDING` 或只有单层证据；
- 出现任何未经隐私复审的新截图；
- 最终 ZIP、installed JAR、日志和 GUI 证据未绑定同一 commit。
