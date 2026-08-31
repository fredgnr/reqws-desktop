---
title: GoLand 插件 GUI 验收报告（2026-08-30）
type: test-report
status: archived
updated: 2026-08-31
---

# GoLand 插件 GUI 验收报告（2026-08-30）

本报告先记录前一候选的真实 GUI 缺陷，再绑定修复后候选的自动化与重验结果。前一候选安装、初始双仓库投影和 repo-a 的主要 Go/Git 功能通过，但三次 Desktop manifest 变化均未自动同步；repo-c 与重新加入的 repo-b 在 authoritative Workspace Model/序列化 `.iml` 已移除 exclude 后，live `ProjectFileIndex`/Project/Search 仍未收敛，Tool Window 又错误显示 `Synced`/`Active`。同一 JVM 保留 recovery claims 符合 crash-recovery 设计，不是该失败原因。修复后候选尚需完成本节第 8 节真实 GUI 重验与 exact commit 可取得性门禁，因此当前结论仍为 `NO-GO`。

> 2026-08-31 追记：本文的 HEAD、fingerprint、ZIP/JAR 和 GUI 数据均冻结为 2026-08-30 历史候选，不随随后源码修复改写。新的本地 exact implementation commit、产物哈希、隐私收口和剩余工作见[2026-08-31 清单](open-bugs-and-verification-work-2026-08-31.md)；它们不能反向把本文的 `FAIL`/`NOT RUN` 改成 `PASS`。

## 1. Exact input

| 项目 | 值 |
|---|---|
| 分支 | `feat/goland-plugin-support` |
| HEAD | `768090d2783675f81b44471c4941de11e28c7d1e` |
| tree | `6a50446ab164abbaea2ebe46ba34225211c1bbbc` |
| 输入形态 | GUI 验收前 tracked worktree clean；候选从上述本地 exact HEAD 重建。 |
| 远端状态 | 相对 upstream ahead 1、behind 1；本地 HEAD 尚未推送，upstream tip 为 `e78851f77a49a33559381c275d6efe1a5877b0fb`。 |
| 插件版本 | `0.1.0`，ID `com.reqws.workspace` |
| 插件 ZIP | `integrations/goland/build/distributions/reqws-goland-0.1.0.zip` |
| ZIP SHA-256 | `9746925dad410016187d1f9859829dfa77ca020d167f4a87f9261afbac6e2fc8` |
| ZIP 大小 | 484,669 bytes |
| fixture root | 隔离 fixture 工作区（不记录本机绝对路径） |
| 初始 manifest SHA-256 | `4dd54bf10446358ae9152f0f160df8839152d9537c807f18c0f272debb9d3d0e` |

### 1.1 修复后待重验候选

| 项目 | 值 |
|---|---|
| Git base HEAD | `768090d2783675f81b44471c4941de11e28c7d1e`；修复尚未创建 commit。 |
| plugin source fingerprint | 对 `integrations/goland` 的 86 个 build/source/resource/test 文件按相对路径排序并逐文件 SHA-256 后再次 SHA-256：`1684af67b6ef516eba13285a48c377c33e8c5cb2bfe0563d68f0bfbe1c02108f`。 |
| 输入形态 | base HEAD + 本报告所列未提交修复；以 source fingerprint、ZIP/JAR hash 与 worktree diff 共同绑定，不能冒充 pushed exact commit。 |
| plugin ZIP SHA-256 | `25f3d7b1e09b8829af2d6df9122fef188d379477adb0a7beee6d2d1147e91658`，498,704 bytes。 |
| ZIP 内 plugin JAR | `d20238b5ec4206cd5c4fee90d648aeb2c660af35190b53a9b6b3d32e9887349f`，532,757 bytes。 |
| packaged Desktop | Desktop 产品代码未变；重新生成的 `app.asar`/executable hash 与前一候选相同。 |
| 远端状态 | base HEAD 相对 upstream ahead 1、behind 1；修复未 commit/push，仍不满足可取得的 exact candidate 门槛。 |

fixture 初始活动仓库为 repo-a、repo-b，repo-c 只保留在磁盘。三个仓库的验收提交为：

```text
repo-a  34ddfcd2a2a3b58049f06a0eadaf1a9909da3625
repo-b  de9ebce031f9e532423c318b854337d499cd87b4
repo-c  61b0496c883a744c3ec8fc7508b69c208d74c9e0
```

所有 remote 均指向 `https://127.0.0.1:18443/` 下的本地 HTTPS fixture，不含真实凭据或外部仓库依赖。验收完成时三个仓库均为 clean。

## 2. Environment

| 项目 | 值 |
|---|---|
| macOS | 26.2，build `25C56` |
| 架构 | Apple Silicon `arm64` |
| GoLand | 2026.1.3，build `GO-261.25134.147` |
| GoLand 路径 | `<home>/Applications/GoLand.app` |
| JetBrains Runtime | JBR 25.0.3+9-b329.124，aarch64 |
| 构建 JDK | Temurin 21.0.12+8 |
| Gradle / Kotlin / IntelliJ Platform Gradle Plugin | 9.3.0 / 2.3.20 / 2.18.1 |
| Go | CLI 1.24.7；GoLand GUI 使用 1.26.5 toolchain |
| Node / npm | 24.19.0 / 11.17.0 |
| Git | 2.55.0 |

## 3. Build artifacts and hashes

| 产物 | 结果 | SHA-256 / 说明 |
|---|---|---|
| plugin ZIP | PASS | `9746925dad410016187d1f9859829dfa77ca020d167f4a87f9261afbac6e2fc8`，484,669 bytes。 |
| ZIP 内 plugin JAR | PASS | `3aea4d866c83d20ac57700a3d27f52066ab378b654d48dd95ccdef1bebbdc5cb`。 |
| 重启后已安装 JAR | PASS | SHA-256 同为 `3aea4d866c83d20ac57700a3d27f52066ab378b654d48dd95ccdef1bebbdc5cb`，517,564 bytes；运行实例对应本轮候选。 |
| packaged Desktop `app.asar` | PASS | `ea6c46f0b76c0f3d29ea0f6d5ba99419a66851fb20a13063e539cd2ed229236f`。 |
| packaged Desktop executable | PASS | `817446a29aa307221e152a7d10a241e868f21dcae6803281fabe1ebfe8262e0e`。 |
| macOS 签名检查 | PASS | `out/ReqWS-darwin-arm64/ReqWS.app` 的 `codesign --verify --deep --strict` 通过。 |

修复后候选产物：plugin ZIP `25f3d7b1e09b8829af2d6df9122fef188d379477adb0a7beee6d2d1147e91658`（498,704 bytes），ZIP 内 JAR `d20238b5ec4206cd5c4fee90d648aeb2c660af35190b53a9b6b3d32e9887349f`（532,757 bytes）。packaged Desktop 重新生成并通过脚本内置校验及 `codesign --verify --deep --strict`；`app.asar` 与 executable SHA-256 仍分别为 `ea6c46f0b76c0f3d29ea0f6d5ba99419a66851fb20a13063e539cd2ed229236f`、`817446a29aa307221e152a7d10a241e868f21dcae6803281fabe1ebfe8262e0e`。

## 4. Automated results

| 检查 | 结果 | 说明 |
|---|---|---|
| `npm run check` | PASS | typecheck、ESLint、i18n、docs，以及 31 个 Vitest 文件、335 项测试通过。 |
| 插件测试 | PASS | 31 个 XML suites、281 项测试，零 skipped/failure/error。 |
| `verifyPluginProjectConfiguration` | PASS | 项目配置检查通过。 |
| `verifyPluginStructure` | PASS | 插件结构检查通过。 |
| `verifyPlugin` | PASS | `GO-261.25134.147` 与 `GO-262.8665.270` 均为 `Compatible`。 |
| `buildPlugin` | PASS | 从 exact HEAD 生成上述 ZIP。 |
| `npm run package:macos` | PASS | 从 exact HEAD 生成上述 arm64 packaged Desktop。 |

修复后候选重新执行了完整门禁：`npm run check` 通过 31 个 Vitest 文件/335 项测试；JDK 21 下关闭 configuration/build cache 并 `--rerun-tasks` 的插件命令通过 31 个 XML suites/286 项测试（0 skipped/failure/error）、项目配置/结构检查、ZIP 构建，以及 GO-261.25134.147、GO-262.8665.270 两版 `Compatible`。另外 79 项缺陷定向矩阵全部通过，覆盖 native watch wiring、外部 hidden-temp + atomic replace 的 VFS translation、显式 exclude child removal、roots-change/live `ProjectFileIndex` add/remove/re-add、clean-digest gate、filesystem alias 精确 target 与 Tool Window 真实性。

Desktop 打包流程中的 `npm ci` 与第二次 `npm run check` 均通过；网络对 Electron 43.4.0 的冗余下载过慢后，改用本机缓存中经官方 `SHASUMS256.txt` 验证的同版本 arm64 ZIP（SHA-256 `827f9f182566f46846377575b51c547b9926b111637313a373b6f717462aebac`），再以 `--skip-ci --skip-check` 只重放已通过的 package 阶段。packager 完成且安装脚本报告 `Packaged and verified`，没有把跳过检查写成检查通过的替代证据。

完整插件验证使用 JDK 21、一次性 Gradle daemon、关闭 configuration/build cache 并强制重跑。Verifier 获取 JetBrains documented API changes 页面时发生一次网络 read timeout，因而没有应用 documented-problem ignore filter；两个目标 IDE 的实际验证仍分别完成并返回 `Compatible`。

## 5. GUI scenario results

### 5.1 §8.1 安装

| 场景 | 结果 | 证据与说明 |
|---|---|---|
| Install Plugin from Disk | PASS | 使用上述 exact ZIP 安装，Plugins 页显示 ReqWS 0.1.0 等待 IDE restart。 |
| 完全重启、版本与运行 JAR | PASS | 退出并重启 GoLand 后加载 `ReqWS (0.1.0)`；已安装 JAR 哈希与候选 JAR 一致。 |
| startup exception | PASS | 重启后日志区间未发现 ReqWS `ERROR`、`PluginException`、`AlreadyDisposedException` 或 ReqWS stack。 |

### 5.2 §8.2 初次打开

| 场景 | 结果 | 证据与说明 |
|---|---|---|
| 从 exact packaged ReqWS Desktop 打开 | PASS | Desktop 显示 `gui-acceptance-20260830`、2 repositories、`Ready`，随后打开正确 workspace root。 |
| trust flow | NOT RUN | workspace 已处于 trusted 状态，本轮没有重新出现可复核的原生信任弹窗。 |
| 初始 Tool Window | PASS | 显示 `Synced`、正确 workspace/branch、活动仓库数 2 和 digest `4dd54bf10446`。 |
| 初始 Project/Search 投影 | PASS | repo-a/repo-b 可见；`.reqws` 与 retained repo-c 排除，repo-c 不在默认 Project/Search。 |
| 手动 Directory Mappings 路径 | PASS | GoLand 起初原生 auto-detect repo-a/repo-b；关闭 auto-detection 并移除其 mappings 后，按 Settings → Version Control → Directory Mappings 手动添加精确 Git mappings，配置事件把状态更新为 `Synced`。原生 auto-detection 与用户设置均单独归因，不归因于 ReqWS。 |
| repeated indexing/model-sync loop | PASS | 日志只记录 4 次有界模型同步，均一次成功并耗时 123/28/16/12 ms，没有持续重试或循环。 |

### 5.3 §8.3 Desktop 增删与重新加入（前一候选）

| 场景 | 结果 | 证据与说明 |
|---|---|---|
| 添加 repo-c 后自动同步 | FAIL | Desktop 把 manifest 更新为 SHA-256 `33334a60ac1be4be2a8734096024195b15bb37544678094a4ada7c6ac2d28e94`；等待并切回 GoLand 后，Tool Window 仍是旧 digest 和 2 个活动仓库，必须点击 `Sync Now`。 |
| 添加 repo-c 的手动恢复入口 | FAIL | `Sync Now` 把活动计数更新为 3，并在缺失 mapping 时显示待手动配置；用户添加 repo-c mapping 后状态变为 `Synced`，但 authoritative model 的 child-removal 没有推动 live `ProjectFileIndex` 收敛，repo-c 在 Project 中仍是 `excluded directory`。 |
| 移除 repo-b 后自动同步 | FAIL | Desktop 更新 manifest 为 SHA-256 `5f1aa8d76d8cc1cb02c20daf3a0a7578085f13582a6d9b155f65ca28bec9770d` 后仍未自动同步；`Sync Now` 后 repo-b 退出默认范围，但目录保留。 |
| repo-b retained mapping 保护 | PASS | 插件没有删除 repo-b mapping，而是显示 VCS mismatch；用户手动移除 mapping 后状态更新为 `Synced`。 |
| 重新添加 repo-b 后自动同步 | FAIL | Desktop 复用原目录并生成 SHA-256 `06dba6cdc4bb871a936464a38440a8c858922b61874cd6a5fd5fe957ddfa6458` 的 manifest；第三次变化仍未自动同步，必须点击 `Sync Now`。 |
| 重新添加 repo-b 的最终 live 投影 | FAIL | 手动恢复 repo-b mapping 后 Tool Window 显示 `Synced` 和 3 个 `Active`，但 repo-b 与 repo-c 的 authoritative excludes 已移除，live Project/Search 却仍把二者视为 excluded。 |
| manifest / mapping 去重 | PASS | 最终 manifest 中 repo-a/repo-c/repo-b 各出现一次；`vcs.xml` 中 repo-a/repo-b/repo-c 各有一个精确 Git mapping。 |
| Tool Window 状态真实性 | FAIL | 最终 UI 声称 3 个仓库均 `Active` 且整体 `Synced`，实际 Project/Search 只包含 repo-a。 |

三次 Desktop manifest 变化均未由监听自动触发 Project Model 更新；本轮可见的后续模型同步来自手动 `Sync Now`：

```text
21:58:23  Synchronize ReqWS project excludes  # add repo-c 后手动 Sync
22:00:34  Synchronize ReqWS project excludes  # remove repo-b 后手动 Sync
22:02:48  Synchronize ReqWS project excludes  # re-add repo-b 后手动 Sync
```

最终 `.idea/reqws-managed-project-model.json` 为 generation 3，当前 managed ownership 只包含 `.reqws`；repo-b/repo-c 位于 `recoveryClaims` 是同一 JVM crash-recovery proof 的预期保留，不授权重新创建 exclude，也不表示当前 authoritative model 仍排除它们：

```json
{
  "managedClaims": [
    { "relativePath": ".reqws" }
  ],
  "recoveryClaims": [
    { "relativePath": "repo-b" },
    { "relativePath": "repo-c" }
  ]
}
```

日志揭示了结构层和 live 层的不对称：add repo-c 与 re-add repo-b 都是 remove-exclude update，但没有 `ModuleRootListener` roots-changed，后续 health check 分别报告 repo-c/repo-b non-indexable；remove repo-b 是 add-exclude update，出现 roots-changed 与索引扫描。序列化 `.idea/workspace.iml` 最终只保留 `.reqws` 及 marker excludes。这些证据把根因定位为 relationship-list child removal 未推动 Workspace File Index，而不是 recovery state。

### 5.4 §8.4 Go 与 Git 功能

| 能力 | repo-a | repo-b | repo-c |
|---|---|---|---|
| `go.mod` / Go module 识别 | PASS | BLOCKED：重新加入后仍 excluded。 | BLOCKED：加入后仍 excluded。 |
| code completion | NOT RUN | BLOCKED | BLOCKED |
| navigate declaration | PASS：`main.go` 的 `Message` 跳到 `greeting.go` 声明。 | BLOCKED | BLOCKED |
| find usages | PASS：Code Vision 显示 2 usages。 | BLOCKED | BLOCKED |
| run test | PASS：`TestMessage`，1 test passed，exit code 0。 | BLOCKED | BLOCKED |
| run main | PASS：输出 `hello ReqWS from repo-a`，exit code 0。 | BLOCKED | BLOCKED |
| debug main | PASS：`dlv` 启动、程序输出正确并正常结束。 | BLOCKED | BLOCKED |
| Git diff | PASS：临时 diff 在 repo-a root 下可见。 | BLOCKED | BLOCKED |
| Git log | NOT RUN | BLOCKED | BLOCKED |
| Commit root selection | PASS：Commit 工具正确选择 repo-a root；未创建提交。 | BLOCKED | BLOCKED |

临时 Git diff 已恢复，三个 fixture 仓库最终均为 clean。repo-b/repo-c 的阻断不是 Go 工具链失败，而是活动目录错误保留为 excluded，因而无法完成“每个活动仓库”的 §8.4 矩阵。

### 5.5 §8.5 恢复

| 场景 | 结果 | 说明 |
|---|---|---|
| 安装后的完全重启 | PASS | exact JAR 在重启后加载，初始 manifest 冷读成功。 |
| 增删后的 GoLand reopen | NOT RUN | 安装重启发生在动态增删之前，不能替代动态状态的 reopen 验证。 |
| Desktop 不运行时直接打开 | NOT RUN | 未取得本候选的独立证据。 |
| malformed manifest → valid | NOT RUN | 未执行。 |
| delete manifest → restore | NOT RUN | 未执行。 |
| sleep/wake | NOT RUN | 未执行。 |
| plugin disable/enable | NOT RUN | 未执行。 |
| `Sync Now` 触发 latest read | PASS | 三次均能触发一次 Project Model reconcile 和只读 VCS 复核。 |
| `Sync Now` 最终 live projection 收敛 | FAIL | authoritative model/`.iml` 已移除 repo-c 与 repo-b exclude，但 live `ProjectFileIndex`/Project/Search 未收敛。 |
| 首次 read/apply cancellation fault injection | NOT RUN | GUI 候选未提供受控 fault injection。 |

### 5.6 §8.6 Tool Window 视觉与可用性

| 场景 | 结果 | 说明 |
|---|---|---|
| 浅色 synchronized 实机截图 | EVIDENCE | 已把真实 GoLand PNG 保存为[同步态实现候选](../ui/tool-window-implementation-2026-08-30.png)，并在[视觉设计文档](../ui/tool-window-visual-design.md)与原型同屏对照；该图只证明候选界面形态，不表示 `PASS` 或 `GO`。 |
| 状态徽标、摘要卡、仓库卡和操作区 | EVIDENCE | 初始双仓库 synchronized 截图可见紧凑徽标、workspace/branch/count、仓库行、digest、全宽 `Sync Now` 和居中次级动作。 |
| 动态状态与实际模型一致 | FAIL | 最终 UI 显示 `Synced`/3 Active，但 repo-b/repo-c 仍 excluded。 |
| degraded/error/Safe Mode 文字与辅助色 | NOT RUN | 未执行。 |
| 深色主题 | NOT RUN | 未执行。 |
| 最窄宽度、长文本与 tooltip | NOT RUN | 未执行。 |
| Tab/Shift+Tab、enable 状态与屏幕阅读器 | NOT RUN | 未执行。 |
| content 实例化后的 close project / exit | NOT RUN | Computer Use 在验收后半段持续挂起；日志区间未见 ReqWS dispose 异常，但没有最终 `IDE SHUTDOWN`，不能据此判定通过。 |

### 5.7 §8.7 用户配置保护

| 场景 | 结果 | 证据与说明 |
|---|---|---|
| VCS inspection 与 `Sync Now` 只读 | PASS | ReqWS 同步未新增、删除、替换或重排 mappings；可见变化只来自 GoLand 原生 auto-detection 或用户在 Settings 的操作。 |
| GoLand auto-detection 独立归因 | PASS | 日志记录 `VcsRootProblemNotifier` 自动登记 repo-a/repo-b；随后由用户关闭该设置并手动管理。 |
| 最终 `.idea/vcs.xml` | PASS | SHA-256 `bf91efbbe2ade3eaf180b5dacfd9836697663fbc3bc1ecb5a9604ddc4855f42c`；三个唯一 Git mappings，auto-detection false。 |
| 旧 VCS ownership 文件 inert | PASS | `.idea/reqws-vcs-ownership.json` 前后 SHA-256 均为 `f96ce51a9db7cba1fb4a654f04ec6d5aa7f3f2c6d3b1fcb91ec5af5360d401fd`。 |
| 旧 ownership lock inert | PASS | lock 前后 SHA-256 均为 `4b22568e5b21d880b811880d97069c56437f0ebccb6a844fb75a72a68c316175`。 |
| custom `rootSettings` 与自定义 mapping 顺序 | NOT RUN | 未构造。 |
| 非 ReqWS module/content root | NOT RUN | 未构造。 |
| Project Model ownership conflict | NOT RUN | 未构造。 |

## 6. Screenshots/log excerpts

本轮曾生成本地 ignored 原始截图；后续隐私审计确认该集合混有非隔离 workspace、真实用户路径或无关第三方界面，因此整组原图不作为可发布证据，不再记录目录或文件名，也不得被后续报告复用。

纳入需求包并经逐图隐私复审的浅色同步态 PNG 为 1355×768，SHA-256 `27542e1be7e703051aad038a8364d626a0041660c017301e8507e74e89adc3c2`。该图只包含隔离 fixture，不显示私有仓库、repository URL 或真实用户路径。

GoLand `idea.log` 审计区间为第 6738–9018 行（21:52:53.606–22:32:12.644）：第 7021 行确认 `ReqWS (0.1.0)` 加载；ReqWS 模型同步只出现在第 7917/7967/7972、8411/8413/8414、8523/8530/8531、8571/8573/8574 行，均 attempt 1/3 一次成功。区间内没有 `com.reqws`/`Reqws*` 异常 stack、`PluginException` 或 `AlreadyDisposedException`。唯一 `SEVERE` 来自第三方 Pi Agent Selection 1.4.0 的无效 `<icon>` descriptor，不属于 ReqWS；本区间末尾仍没有 `IDE SHUTDOWN`。

## 7. Known limitations

1. **GL acceptance blocker：manifest 自动监听未收敛。** add repo-c、remove repo-b、re-add repo-b 三次 Desktop manifest 变化都依赖 `Sync Now`，违反无需手动操作自动同步的验收语义。
2. **GL acceptance blocker：活动仓库 live projection 未收敛。** repo-c 加入后、repo-b 重新加入后，authoritative model 与 `.iml` 已移除 target/marker，但 ProjectFileIndex/Project/Search 仍把二者留在默认范围之外；Tool Window 又未验证 live projection，错误显示 `Synced`。recovery claims 的同 JVM 保留是预期行为。
3. **§8.4 矩阵不完整。** repo-b/repo-c 因上述 Project Model blocker 无法执行 Go 与 Git 核心功能；repo-a completion 与 Git log 也未执行。
4. **§8.5–§8.7 仍有缺口。** malformed/delete recovery、sleep/wake、disable/enable、Safe Mode、fault injection、深色/窄宽度/无障碍、custom rootSettings、非 ReqWS module/content root、ownership conflict 和 close/exit 生命周期均未完成。
5. **exact commit 尚不可由远端取得。** 本地 HEAD 与 ZIP 可以精确绑定，但 commit 尚未推送，upstream 又与本地分叉，不能满足可取得的 exact candidate 门槛。
6. **验收基础设施限制。** Computer Use 在后半段对 GoLand 状态读取持续挂起，独立恢复会话同样超时，因此剩余 UI 场景如实记为 `NOT RUN`；这项限制本身不归因于 ReqWS 产品。

本轮随后已实现 automatic native watch、显式 child entity removal、live projection success gate、专用 `PROJECT_CONTENT_NOT_CONVERGED` 诊断与 Tool Window truthfulness，并完成自动化门禁；尚未执行修复后 GUI 结果时仍不改变 verdict。没有创建 commit、tag、PR 或发布资产。

## 8. Verdict

`NO-GO`

解除结论至少需要：在修复后 ZIP 上证明 manifest automatic refresh、live `ProjectFileIndex` 与 Tool Window 一致；对 repo-b/repo-c 补齐 Go/Git 功能；补齐恢复、视觉/无障碍、用户配置保护与 close/exit 证据；把 exact commit 推送到可取得的远端；再从同一 commit 重建 ZIP、记录新哈希并复验。
