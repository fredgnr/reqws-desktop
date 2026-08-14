---
title: GoLand 插件支持测试方案
type: test-plan
status: draft
updated: 2026-08-14
---

# GoLand 插件支持测试方案

## 1. 测试目标

验证 ReqWS Desktop 与 GoLand 插件在 macOS 本地环境中形成可靠的受管多仓库工作区：

- Desktop 正确探测并打开 GoLand；
- 插件把 manifest 活动仓库集合转换为项目内容和 Git Root；
- 逻辑移除但仍留在磁盘的仓库不参与默认索引和 VCS；
- manifest 原子替换、错误、快速连续更新和重启均可收敛；
- 插件不越权执行 Git、删除目录或覆盖用户无关配置；
- VS Code、Cursor 和现有 workspace 事务不回归。

## 2. 测试环境记录

每份 dated verification 必须记录：

```text
reqws commit SHA
plugin version
plugin ZIP SHA-256
macOS version/build
CPU architecture
GoLand version and exact build
GoLand install source/path
JetBrains Runtime
Gradle version
JDK used to build
Go version(s)
Node/npm version
test fixture absolute root（可脱敏）
```

首个 release candidate 的真实环境必须包含 Apple Silicon macOS 与当前稳定 GoLand 2026.2。

## 3. Fixture

基础 fixture：

```text
workspace/
├── .reqws/workspace.json
├── repo-a/     active
├── repo-b/     active
├── repo-c/     retained, not in manifest
└── notes/      ordinary directory
```

扩展 fixture：

- 路径含空格；
- NFC/NFD Unicode 名称；
- symlink 指向 root 外；
- missing active repo；
- duplicate IDs/names；
- unsupported schema；
- malformed/oversized manifest；
- 50 active + 20 retained；
- 用户自定义 module/content root/VCS mapping；
- root 级用户 `go.work`。

所有仓库使用本地 `git init` 创建，不需要网络或凭据。

## 4. Desktop 自动化

### 4.1 GoLand resolver

| 编号 | 输入 | 期望 |
|---|---|---|
| D-01 | `/Applications/GoLand.app` 存在 | available，返回 canonical path。 |
| D-02 | 仅 `~/Applications/GoLand.app` 存在 | available。 |
| D-03 | Toolbox candidate | 选择经过验证的稳定候选。 |
| D-04 | 多个候选 | 确定性排序，不随机。 |
| D-05 | app 缺 launcher | 不标记为完整可用，或使用已验证 open fallback。 |
| D-06 | 均不存在 | `available:false`, `reasonCode:NOT_FOUND`。 |
| D-07 | malicious PATH `goland` | 不覆盖受信 app resolver。 |

### 4.2 启动

- workspace ready；
- workspace root missing；
- manifest missing；
- app missing；
- launcher spawn error；
- non-zero exit；
- 路径含空格/Unicode；
- command 与 args 分离；
- `shell:false`；
- 正确选择 root 而不是 `.code-workspace`；
- GoLand 已运行/未运行的真实 smoke。

### 4.3 IPC 与 renderer

- typed channel；
- invalid workspace ID；
- availability shape 包含 goland；
- disabled state；
- click loading/error；
- zh-CN/en-US key 一致；
- screen reader label；
- VS Code/Cursor/Finder snapshot 和行为不变。

### 4.4 Manifest contract

- current v1 golden fixture；
- unknown additive fields；
- duplicate repo；
- unsafe relative path；
- Desktop 写入后 Kotlin fixture 可读；
- Kotlin valid fixture 可由 TypeScript schema 接受；
- manifest 原子写行为不回归。

## 5. 插件纯单元测试

### 5.1 Parser

- valid minimal/full v1；
- invalid UTF-8；
- file > 1 MiB；
- invalid JSON；
- wrong types；
- unsupported version；
- empty/oversized values；
- duplicate IDs/names；
- unknown fields ignored；
- URL 不被执行或日志化。

### 5.2 Path

- canonical root match；
- root mismatch；
- absolute relativePath；
- `..`；
- separator and Unicode normalization；
- path inside root；
- symlink escape；
- missing directory；
- `.git` directory；
- ordinary non-repo directory；
- case-insensitive collision assumptions on default macOS filesystem。

### 5.3 Planner

输入 current managed state + desired manifest，断言：

- add/keep/remove-owned；
- no duplicate；
- unknown user entries preserved；
- adopted existing mapping；
- user modification conflict；
- no-op for same digest；
- partial missing repo behavior。

### 5.4 Coordinator

- single event；
- atomic replace event burst；
- latest-wins；
- apply running then newer manifest；
- invalid then valid；
- target temporary missing；
- project disposed；
- manual sync races automatic sync；
- exception does not deadlock queue。

## 6. 插件平台模型测试

优先使用真实 IntelliJ Platform components，不用大面积 mocks。

### 6.1 Content Root / Workspace Model

- initial active roots；
- add；
- remove retained；
- re-add；
- restart serialization；
- unknown module preserved；
- unknown content root preserved；
- owned root manually changed；
- transaction failure；
- no index loop；
- selected strategy only。

### 6.2 VCS

- add two Git mappings；
- automatic existing mapping adoption；
- remove owned mapping；
- preserve user mapping；
- duplicate path normalization；
- VCS apply failure degraded state；
- Git plugin disabled（明确诊断，不 crash）。

### 6.3 Safe Mode

- manifest visible；
- no model mutation；
- no external process；
- trust transition triggers one sync；
- repeated trust event idempotent。

### 6.4 Tool Window

Swing 像素级 UI 不作为主自动化目标。测试 view model：

- hidden/non-ReqWS；
- synchronized；
- pending；
- degraded；
- invalid manifest；
- missing repo；
- copy diagnostics redaction；
- sync action enabled rules。

## 7. Plugin Verifier 与构建

每个候选提交至少：

```bash
cd integrations/goland
./gradlew test
./gradlew verifyPlugin
./gradlew runPluginVerifier
./gradlew buildPlugin
```

验证矩阵由 W0 决定，优先：

- GoLand 2026.1；
- GoLand 2026.2；
- 当前本地 exact build；
- 下一稳定版/EAP 只作为非阻塞预警，除非进入发布范围。

失败分类：

- missing dependency；
- experimental/internal API；
- deprecated for removal；
- descriptor/since-build；
- bytecode/JDK；
- plugin classloader；
- unresolved Go plugin API。

任何 binary incompatibility 都必须解决或明确缩小支持范围，不能以 `continue-on-error` 掩盖。

## 8. macOS 真实 GUI smoke

### 8.1 安装

1. 构建 ZIP；
2. 记录 SHA-256；
3. GoLand Install Plugin from Disk；
4. 重启；
5. 检查 plugin version 和无 startup exception。

### 8.2 初次打开

- 从 ReqWS 点击 GoLand；
- 确认打开正确 workspace；
- trust flow；
- ReqWS Tool Window；
- repo-a/repo-b 可见；
- repo-c 默认不在 Project/Search/VCS；
- 两个活动 Git roots；
- no repeated indexing loop。

### 8.3 增删

在 Desktop：

1. 添加 repo-c；
2. 观察 GoLand 自动加入；
3. 移除 repo-b；
4. 确认 repo-b 目录仍在 Finder；
5. 确认它退出 Content Root、Find in Files 默认范围和 Git roots；
6. 重新添加 repo-b；
7. 确认无重复 root/module/mapping。

### 8.4 Go 功能

每个活动仓库：

- go.mod recognized；
- code completion；
- navigate declaration；
- find usages；
- run test；
- run/debug main package；
- Git diff/log/commit root selection。

### 8.5 恢复

- 重启 GoLand；
- Desktop 不运行时打开；
- malformed manifest 后恢复；
- 删除 manifest 后恢复；
- sleep/wake；
- plugin disable/enable；
- manual Sync Now。

### 8.6 用户配置保护

- 增加用户 VCS mapping；
- 增加非 ReqWS module/content root；
- 同步；
- 验证未被删除；
- 制造所有权冲突；
- 验证插件保守报错而不是强制覆盖。

## 9. 对抗与安全

| 编号 | 场景 | 期望 |
|---|---|---|
| S-01 | rootPath 指向其他 workspace | 全量拒绝。 |
| S-02 | `relativePath: ../outside` | 拒绝。 |
| S-03 | symlink inside → outside | 拒绝该 repo/全量，按设计记录。 |
| S-04 | 巨大 manifest | 读取前大小门禁。 |
| S-05 | URL 含敏感 query（即使 Desktop 通常拒绝） | 不网络访问、不普通日志输出。 |
| S-06 | manifest 字段伪装 command | 仅当字符串，不执行。 |
| S-07 | rapid 100 writes | 最终一致、无线程泄漏。 |
| S-08 | atomic delete/rename gap | 不立即清空项目。 |
| S-09 | Safe Mode | 无模型/进程副作用。 |
| S-10 | malicious app candidate | 不执行未经验证的任意 binary。 |
| S-11 | plugin state tamper | 保守恢复，不删除 unknown entries。 |
| S-12 | real workspace deletion request | 插件没有该能力。 |

## 10. 规模与性能

Fixture：

- 50 active repos；
- 20 retained repos；
- 每仓最小 go.mod；
- 10 次连续 add/remove manifest；
- 100 次 rapid rewrite。

观察：

- sync stage durations；
- EDT freeze；
- VFS event count vs apply count；
- indexing 是否结束；
- memory/threads 在重复同步后是否稳定；
- same digest no-op；
- Project/Git UI 是否仍可交互。

不设脱离本机环境的绝对毫秒硬门限，但出现可感知 UI freeze、持续 CPU、无限 indexing 或 apply 数量与 event burst 等量时均判失败。验证报告必须给出实测数据。

## 11. 回归

必须运行现有：

```bash
nvm use
npm ci
npm run check
npm run package:macos
```

并至少手工确认：

- VS Code `.code-workspace`；
- Cursor workspace；
- Cursor root；
- Finder reveal；
- create workspace；
- add repository；
- logical remove；
- regenerate workspace file；
- global settings/i18n；
- packaged app launch。

插件构建不得改变 Electron runtime 依赖、签名边界或现有 npm install 流程。

## 12. 验收证据格式

dated verification 建议结构：

```markdown
---
title: ...
type: test-report
status: active
updated: YYYY-MM-DD
---

# ...

## Exact input
## Environment
## Build artifacts and hashes
## Automated results
## GUI scenario results
## Screenshots/log excerpts
## Known limitations
## Verdict
```

Verdict 只能使用：

```text
GO
NO-GO
```

`GO` 必须引用 exact commit 和 exact plugin ZIP hash。若任一 GL acceptance blocker 未关闭，结论为 `NO-GO`，不得用“基本通过”代替。
