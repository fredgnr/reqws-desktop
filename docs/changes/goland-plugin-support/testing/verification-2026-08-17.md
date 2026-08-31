---
title: GoLand 插件 GUI 验收报告（2026-08-17）
type: test-report
status: archived
updated: 2026-08-31
---

# GoLand 插件 GUI 验收报告（2026-08-17）

本报告记录未提交工作树在日常 GoLand 2026.1.3 上的真实 GUI 验收；核心同步与 Go 基础功能大部分可用，但项目关闭/IDE 退出稳定触发插件异常，因此结论为 `NO-GO`。

## 1. Exact input

| 项目 | 值 |
|---|---|
| 分支 | `feat/goland-plugin-support` |
| 基础 HEAD | `d19c699a09c1c871214b3482ffd7023a05f2c705` |
| 输入形态 | 未提交工作树；用户明确要求本轮不提交代码。 |
| GUI 前源码快照指纹 | `6689d3ec75876a5f09a461a6415f94384ba0ff1ebb07d18d79a66b5b4c598876` |
| 插件版本 | `0.1.0` |
| 插件 ZIP | `integrations/goland/build/distributions/reqws-goland-0.1.0.zip` |
| ZIP SHA-256 | `c1cd6966869bae86d72b998e401d3a9a470e19f6811f51248b3cb763988d9609` |

源码快照指纹在创建本报告前计算，覆盖相对 HEAD 的 tracked diff 和所有未忽略 untracked 文件内容：

```bash
{
  git diff --binary HEAD
  git ls-files --others --exclude-standard -z | sort -z | xargs -0 shasum -a 256
} | shasum -a 256
```

该指纹只能标识本地输入，不能替代 exact commit。按照测试方案，缺少 exact commit 本身就不满足 `GO` 证据格式。

## 2. Environment

| 项目 | 值 |
|---|---|
| macOS | 26.2（build `25C56`） |
| 架构 | Apple Silicon `arm64` |
| GoLand | 2026.1.3，build `GO-261.25134.147` |
| 安装来源与路径 | JetBrains Toolbox 管理的日常实例，`~/Applications/GoLand.app` |
| JetBrains Runtime | JBR 25.0.3+9，aarch64，JCEF |
| 构建 JDK | Temurin 21.0.12+8；Gradle toolchain、Java release 与 Kotlin JVM target 均为 21 |
| Gradle / Kotlin / IJ Platform plugin | 9.3.0 / 2.3.20 / 2.18.1 |
| Go | CLI 1.24.7；GUI 运行/调试使用 GoLand 自动识别的 1.26.5 toolchain |
| Node / npm | 24.19.0 / 11.17.0（`nvm use`） |
| Git | 2.55.0 |
| fixture root | 隔离 fixture 工作区（不记录本机绝对路径） |

验收直接操作用户日常 GoLand，没有复制或启动第二个 IDE 实例。fixture 位于仓库忽略的 `out/` 下，使用三个本地 `git init -b main` 仓库，不含真实 remote 或凭据。

## 3. Build artifacts and automated results

同一 ZIP 哈希对应的自动化证据如下：

| 检查 | 结果 |
|---|---|
| `npm run check` | PASS；TypeScript、ESLint、i18n、docs 与 29 个 Vitest 文件共 328 项测试通过。 |
| 插件测试 | PASS；164 项 Kotlin/JUnit 测试通过。 |
| `verifyPluginProjectConfiguration` | PASS。 |
| `verifyPluginStructure` | PASS。 |
| `verifyPlugin` | PASS；`GO-261.25134.147` 与 `GO-262.8665.270` 均为 Compatible。 |
| `buildPlugin` | PASS；生成上述 ZIP。 |
| Desktop arm64 package smoke | PASS；Electron app.asar 隔离检查通过。 |

这些结果证明自动化与二进制兼容层，不覆盖本报告发现的真实 project-dispose 生命周期异常。

## 4. GUI scenario results

### 4.1 安装、首次打开与普通项目隔离

| 场景 | 结果 | 证据与说明 |
|---|---|---|
| Install Plugin from Disk | PASS | 从上述 ZIP 安装，重启后日志显示 `ReqWS (0.1.0)` 已加载。 |
| 普通非 ReqWS 项目 | PASS | 验收前已打开的普通项目未显示 ReqWS Tool Window；项目名称已脱敏。 |
| fixture 首次打开 | PASS | 固定 manifest 被识别；Tool Window 显示 workspace、branch、repo-a/repo-b 和 `Synced`。 |
| 初始项目隔离 | PASS | repo-a/repo-b 活动，`.reqws` 与 retained repo-c 排除；初始 Git roots 为 repo-a/repo-b。 |
| 初始默认搜索隔离 | PASS | `REQWS_RETAINED_ONLY_20260817` 在 Project scope 中返回 `Nothing found`。 |
| 手动 Sync Now | PASS | 相同摘要重新读取后仍为 `Synced`，无重复 mapping。 |
| 从 ReqWS Desktop 按钮启动 | NOT RUN | 本轮按用户指示直接操作日常 GoLand；Desktop launcher 仅有自动化覆盖。 |

### 4.2 增删、重加与持久化

| 场景 | 结果 | 证据与说明 |
|---|---|---|
| 添加 repo-c | PARTIAL | 外部更新 manifest 后，打开固定 manifest 触发 VFS 刷新，无需重启即把 live Workspace Model 与 Git roots 更新为三个仓库；并非从 Desktop 原子 writer 发起。 |
| 逻辑移除 repo-b | PARTIAL | `Sync Now` 后 live model 与持久配置只保留 repo-a/repo-c Git roots，并为 repo-b 建立 ReqWS-owned exclude；repo-b 目录仍在。未重新执行移除后的 Find in Files。 |
| 重新添加 repo-b | PARTIAL | `Sync Now` 后保存配置为 repo-a/repo-b/repo-c 三个唯一 Git mappings，活动仓库无 exclude，无重复 target/marker/mapping。 |
| `go.work` 边界 | PASS | fixture 全程未生成 `go.work`。 |
| Project View 即时刷新 | FAIL | remove 操作能显示新增 exclude，但 remove-exclude 后 Project View 的 `excluded directory` 标签未立即消失；持久 Workspace Model 已正确，关闭/重开后显示才收敛。 |

持久配置的关键观测序列：

```text
initial:  Git=[repo-a, repo-b]          excludes=[.reqws, repo-c]
add c:    Git=[repo-a, repo-b, repo-c]  excludes=[.reqws]
remove b: Git=[repo-a, repo-c]          excludes=[.reqws, repo-b]
re-add b: Git=[repo-a, repo-b, repo-c]  excludes=[.reqws]
```

### 4.3 Go 功能

| 场景 | 结果 | 说明 |
|---|---|---|
| `go.mod` 识别 | PASS | repo-a、repo-b 被识别为独立 Go modules。 |
| repo-a test | PASS | `TestGreeting`：1 test passed，exit code 0。 |
| repo-b test | PASS | `TestDouble`：1 test passed，exit code 0。 |
| run repo-a main | PASS | 输出 `hello ReqWS from repo-a`，exit code 0。 |
| debug repo-a main | PASS | 在 `main.go:10` 暂停、展示 frame，继续后 exit code 0；验收断点已清除。 |
| code completion | NOT RUN | 原生快捷键与 macOS 快捷键冲突，未取得可复核的 completion popup 证据；临时源码编辑已恢复。 |
| navigate declaration / find usages | NOT RUN | 本轮未执行。 |
| Git diff/log/commit root selection | NOT RUN | 映射集合由 GoLand 日志和 `.idea/vcs.xml` 交叉确认，未执行 commit flow。 |

### 4.4 错误与重启恢复

| 场景 | 结果 | 证据与说明 |
|---|---|---|
| malformed manifest | PASS | 无效 JSON 后 Tool Window 进入 `Error (MANIFEST_INVALID_JSON)`，仍展示上次有效的 repo-a/b/c，项目模型和 VCS 未被清空。 |
| malformed → valid | PASS | 恢复有效 JSON 并执行 `Sync Now` 后回到 `Synced`，摘要恢复为 `28e06268370a`。 |
| GoLand restart | PASS | 完全退出并重新启动日常 GoLand；Desktop 不运行时仍从 manifest 冷恢复。 |
| restart ownership | PASS | 重启后 Project View 中 repo-a/b/c 均为活动目录，只有 `.reqws` 排除；Tool Window 为 `Synced`，三个 Git mappings 持久化。 |
| temporary manifest missing / delete recovery | NOT RUN | 未执行。 |
| plugin disable/enable/reinstall recovery | NOT RUN | 初次安装与重启已执行，动态 disable/enable 未执行。 |
| sleep/wake | NOT RUN | 未执行。 |

### 4.5 未执行矩阵

本轮未执行 Safe Mode、用户自定义 module/content root/VCS mapping、ownership tamper、50+20 规模、100 次 rapid rewrite、路径攻击 GUI、manifest delete/recreate、sleep/wake 和动态插件 enable/disable。相关自动化不能冒充真实 GUI 证据。

## 5. Screenshots and log evidence

本轮曾生成本地、未纳入 Git 的原始截图。后续隐私审计确认该集合混有真实用户绝对路径和 URL 上下文，因此整组原图已从可发布证据中撤回，不再记录目录或文件名，也不得被后续报告复用。若需要重现本报告阶段，必须使用仅含 `repo-a`、`repo-b`、`repo-c` 的隔离 fixture 重新截图并逐图复审。

GoLand `idea.log` 记录了 Workspace Model 与 VCS 的真实提交：

```text
14:17:05 Synchronize ReqWS project excludes
14:17:05 VCS Root: repo-a, repo-b, repo-c
14:21:27 Synchronize ReqWS project excludes
14:21:27 VCS Root: repo-a, repo-c
```

同时稳定复现以下阻塞异常：

```text
AlreadyDisposedException: Already disposed: Project(name=workspace, ...)
  at ToolWindowImpl.setAvailable
  at ReqwsToolWindowFactory.kt:27
  at ReqwsToolWindowPanel.render(ReqwsToolWindowPanel.kt:116)
  at TerminalStatePublisher.publish(TerminalStatePublisher.kt:46)
  at ReqwsProjectService.dispose(ReqwsProjectService.kt:286)
Plugin to blame: ReqWS version: 0.1.0
```

该异常在 `CloseProject` 和完全退出 GoLand 时重复出现，IDE 显示 `IDE Internal Error Occurred`；重启后业务投影虽然能够恢复，但生命周期异常本身没有消失。

## 6. Known blockers and limitations

1. **GL acceptance blocker：project dispose 异常。** `ReqwsProjectService.dispose()` 发布终态后，Tool Window listener 仍调用已销毁 project 的 `ToolWindow.setAvailable`，触发 `AlreadyDisposedException`。这违反 dispose 后无晚到 UI/回调以及插件错误不干扰普通 IDE 生命周期的要求。
2. **Project View 即时状态未完全收敛。** Workspace Model、VCS 和持久文件已经正确时，移除 exclude 的标签仍可能停留到 reopen；GL-05/GL-07 的“无需重启即可观察正确项目内容”尚未完整通过。
3. **没有 exact commit。** 本轮按用户要求不提交代码；本地快照指纹不能满足 `GO` 的 exact commit 要求。
4. **GUI 矩阵不完整。** Safe Mode、用户配置保护、规模/快速写入、Desktop-originated atomic update、路径攻击和若干恢复场景仍缺真实 GUI 证据。

本轮只做验收和记录，没有修复上述代码缺陷，也没有创建 commit、tag、PR 或发布资产。

## 7. Verdict

`NO-GO`

解除结论至少需要：修复并回归 project-dispose 异常，证明 Project View 对 add/remove/re-add 无需 reopen 即时收敛，补齐阻塞 GUI 场景，然后在一个 exact commit 上重新构建 ZIP、记录新 SHA-256，并以同一候选重跑自动化、Plugin Verifier 和真实 GoLand 验收。
