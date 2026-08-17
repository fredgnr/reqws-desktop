---
title: GoLand 插件支持测试方案
type: test-plan
status: active
updated: 2026-08-17
---

# GoLand 插件支持测试方案

本方案定义 Desktop、插件、兼容性和真实 macOS GoLand 的分层验收，并明确尚未取得的 exact-head 证据不能写成通过。

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

首个候选的真实环境必须包含 Apple Silicon macOS 与本机 GoLand 2026.1.3 exact build；二进制兼容矩阵同时由 Plugin Verifier 覆盖 GoLand 2026.1.3 和 2026.2。

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
| D-08 | valid standard app + 同路径损坏 Toolbox launcher | 标准候选仍 available；各来源独立验证后再 canonical 去重。 |
| D-09 | Toolbox `tools` 超过 1024 条 | 整个 Toolbox 来源 fail closed，标准安装仍可用。 |
| D-10 | 512 条重复 Toolbox 记录 | 在任何 plist 验证前折叠为一个候选。 |
| D-11 | 64 个以内的唯一候选 | 固定 4-worker，`plutil` 并发峰值有界；第 65 个唯一候选使 Toolbox 来源 fail closed。 |

### 4.2 启动

- workspace ready；
- workspace root missing；
- manifest missing；
- app missing；
- launcher spawn error；
- non-zero exit；
- 路径含空格/Unicode；
- `/tmp`、`/var` 第一层系统 alias 可用，更深层 symlink 拒绝；
- command 与 args 分离；
- `shell:false`；
- 正确选择 root 而不是 `.code-workspace`；
- GoLand 已运行/未运行的真实 smoke。

### 4.3 IPC 与 renderer

- typed channel；
- invalid workspace ID；
- availability shape 包含 goland；
- availability 判别联合要求 `true + absolute path` 或 `false + no path`；
- availability 尚未知时按钮禁用，但列表和详情都不显示 `editorNotFound`/`editorsUnavailable`；
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
- manifest 原子写行为不回归；
- TypeScript 与 Kotlin 直接读取同一 versioned URL safety corpus。

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
- URL 与 Desktop `isSafeRepositoryUrl` 接受/拒绝结果一致；双方直接消费 `repository-url-safety.json`，覆盖无凭据 HTTPS、`ssh://`、SCP-like SSH、raw/percent-encoded UTF-8 国际化 host、IPv4/IPv6、明文 HTTP、local/remote-helper、密码、credential-like query/fragment、无效 UTF-8 percent、反斜杠、C0/DEL 和既有 Unicode path 文本兼容语义；任何 URL 都不被访问、执行或日志化；
- `appStateSchema` 与真实 `AppStateStore.read` 允许旧 policy 接受但当前 manifest policy 拒绝的 credential-free catalog URL，不把整份 state 标为 corrupt；同值必须仍被 create/update/manifest schema 拒绝，直到用户改正。

### 5.2 Path

- canonical root match；
- root mismatch；
- absolute relativePath；
- `..`；
- separator and Unicode normalization；
- path inside root；
- name、relativePath、rootPath 和 workspaceFilePath 中的 NUL 在 TypeScript/Kotlin 两端一致拒绝；
- 共享 fixture 锁定 ECMAScript `TrimString`：`U+FEFF` 可裁剪，`U+001C` 不可裁剪；
- symlink escape；
- missing directory；
- `.git` directory；
- ordinary non-repo directory；
- case-insensitive collision assumptions on default macOS filesystem；
- active/retained/current exclude 使用可验证 filesystem identity 处理大小写、NFC/NFD 与 symlink alias；身份读取异常 fail closed。

### 5.3 Planner

输入 current managed state + desired manifest，断言：

- add/keep/remove-owned；
- no duplicate；
- unknown user entries preserved；
- borrowed existing mapping；
- user modification conflict；
- 同一 live coordinator 的 clean baseline 对 same digest no-op；新 coordinator 不从持久摘要 skip；
- active `CREATED` mapping 在 repository 暂时 missing/non-Git 时保留 mapping 与 ownership；
- active `CREATED` mapping 已消失时丢弃 stale 删除权；同路径用户 mapping 后续出现也不得删除；
- workspace 内额外 Git mapping 和 project-root mapping 保留但产生 degraded/ownership diagnostic；
- mapping 完整 equality 含 `rootSettings`，NFD/NFC root identity 不漏掉 workspace 内覆盖；
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
- exception does not deadlock queue；
- A 成功、B 部分 apply 后失败、manifest 回退 A 时必须重放 A，不能错误 no-op；
- 新 coordinator/service 即使读到相同持久摘要也必须重新 apply/reconcile。

## 6. 插件平台模型测试

优先使用真实 IntelliJ Platform components，不用大面积 mocks。

### 6.1 Content Root / Workspace Model

- initial active roots；
- add；
- remove retained；
- re-add；
- target/companion marker 同事务成对增删；
- state v2 reload + 独立最小 `.idea`/`.iml` fixture 的公开 JPS exclude loader contract；平台测试的 in-memory project 不冒充 save/reopen，真实 close/reopen 仍由 GUI 验收；
- marker/target 缺失或重复、claim/token 重复、旧 v1 state、物理 marker namespace/symlink 均保守冲突且事务不变；
- 现存等价 exclude 只借用；remove/re-add 生成新 marker，不泄漏旧 marker；
- 大小写、NFC/NFD 或 symlink alias 指向 active/current exclude 时不得误加语义重复 exclude，活动仓库不得仍被借用 exclude 覆盖；
- 合法 retained 名称与 synthetic ownership label（例如 `candidate:.reqws`）不得因 map-key 碰撞而绕过 nested Content Root 冲突；
- unknown module preserved；
- unknown content root preserved；
- owned root manually changed；
- transaction failure；
- no index loop；
- 只测试选定的 workspace-root + owned-excludes 策略；保留 root，排除 `.reqws` 和非活动 Git repository，不排除普通目录。

### 6.2 VCS

当前自动化以纯 planner、persistent ownership 和注入 `VcsMappingPlatform` 的 adapter 测试覆盖以下语义；真实 `ProjectLevelVcsManager`/Git Tool Window 的最终发现行为由 macOS GUI 验收补足，不能把 fake platform adapter 测试表述为完整平台集成测试。

- add two Git mappings；
- existing equivalent mapping 标记为 `BORROWED`，且永不由插件删除；
- 插件新增 mapping 标记为 `CREATED`，仅在当前条目仍精确匹配时删除；
- remove owned mapping；
- preserve user mapping；
- duplicate path normalization；
- apply 前实时重查 filesystem/.git，missing→appears 或 present→missing 按本轮真实状态规划；
- 规划期间 mapping 变化时重读/replan，连续不稳定则写入前失败；`rootSettings` 变化也必须识别；
- destructive remove 前先撤销持久删除权；若平台 set 失败，后续同路径用户 mapping 不得被旧 claim 删除；
- active、missing 和 inactive 三种路径下，只要用户给 `CREATED` mapping 增加 `rootSettings`，就必须丢弃删除资格、degraded 且绝不删除；
- extra/root mapping 与 NFC/NFD root coverage 保留并 degraded；
- VCS apply failure degraded state；
- Git plugin disabled（明确诊断，不 crash）。

### 6.3 Safe Mode

- manifest visible；
- no model mutation；
- no external process；
- trust/dispose 在 Workspace Model transaction commit boundary 翻转时回滚 model/state；model 已在 trusted 时提交而随后 gate 翻转时不得 mint ownership 或推进 digest；
- VCS 在 planning、ownership pre-revoke、mapping set、final ownership record 与 refresh 边界翻转时不得执行下一项写；
- 只在 blocked 期间通过稳定 trust probe 低频检测，trust transition triggers one sync；
- repeated trust event idempotent。

### 6.4 Tool Window

Swing 像素级 UI 不作为主自动化目标。测试 view model：

- absent manifest 的普通项目在初始 `READING` 期间也保持 hidden；
- 已有 snapshot 的 refresh/`READING` 期间保持 visible；
- synchronized；
- pending；
- degraded；
- invalid manifest；
- missing repo；
- copy diagnostics redaction；
- sync action enabled rules。

组件级回归同时锁定：manifest 文本不触发 Swing HTML、状态文字与辅助色同时存在、状态徽标保持内容宽度且不横向拉满、workspace 摘要与 repository 列表分别位于独立卡片、repository header 的标题与 count 分列、repository row 使用紧凑固定高度和主题分隔线、列表内容高度按可见行数计算、1–6 行无滚动条且 7 行起在固定六行高度内滚动、长文本提供完整 tooltip、`Sync Now` 全宽且具有主操作语义，以及两个次级动作在常用窄宽度居中且均可访问。像素、主题和最终排版仍由真实 GUI 对照原型验收。

### 6.5 VFS / lifecycle wiring

- project 初开 manifest 不存在时 watcher 已安装，后续 create 自动进入读取与同步；
- symlink 或 macOS top-level alias 打开的 project 使用 canonical manifest target，不漏 VFS event；
- Tool Window 对 file-based project 可动态激活，但 ordinary project 不闪现 UI；
- `shouldBeAvailable` 在 manifest absent 时初始为 false，service state controller 在内容尚未创建时也能在 EDT 完成 absent → create availability transition；
- state listener 的 initial/publish/dispose 并发按队列顺序通知；`DISPOSED` 后 state 永不回退，晚到 publish 被拒绝；
- dispose 后 watcher、debounce、trust probe 和 coordinator 均停止。

## 7. Plugin Verifier 与构建

每个候选提交至少：

```bash
cd integrations/goland
./gradlew test verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin
./gradlew buildPlugin
```

IntelliJ Platform Gradle Plugin 2.18.1 的 `verifyPlugin` 负责运行配置的 Plugin Verifier，目标固定为 GoLand 2026.1.3 与 GoLand 2026.2；`verifyPluginProjectConfiguration` 和 `verifyPluginStructure` 不能替代二进制兼容验证。

W0 固定的阻塞矩阵是：

- GoLand 2026.1.3；
- GoLand 2026.2；
- 本机 GoLand 2026.1.3 exact build `GO-261.25134.147` 另做真实 GUI；
- 下一稳定版/EAP 只作为非阻塞预警，除非后续需求明确扩大范围。

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
- 与[归档原型](../ui/tool-window-visual-design.md)对照同步态的信息层级、仓库行密度和操作区；
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

### 8.6 Tool Window 视觉与可用性

- 保存真实 GoLand PNG 到需求包 `ui/` 目录，并在视觉设计文档中与原型同屏对照；候选截图不得写成 `PASS` 或 `GO`；
- 逐项对照状态徽标位置和宽度、摘要卡边界与活动仓库数量、仓库卡 header/count/行分隔/内容高度，以及全宽主按钮和居中次级动作；仅“元素都存在”不能判定为接近原型；
- synchronized、degraded、error 和 Safe Mode 均同时展示文字与辅助色，错误态保留稳定错误码和上次有效模型说明；
- 浅色和深色主题使用平台颜色，无硬编码浅色背景导致的对比度问题；
- 常用最窄宽度下 workspace/branch/repository 长文本安全截断并可查看完整值，仓库行不纵向拉伸，`Sync Now`、`Open Manifest File`、`Copy Diagnostics` 都可达；
- Tab/Shift+Tab 焦点顺序和按钮 enable 状态符合 view model，屏幕阅读器不依赖装饰状态圆点；
- content 已实例化后 close project 与退出 IDE 不产生 `AlreadyDisposedException` 或晚到 availability 更新。

### 8.7 用户配置保护

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
- 同一 live coordinator 的 clean same-digest no-op；reopen/new service 仍重新 reconcile；
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

当前工作树已取得阶段性的 261/262 Plugin Verifier Compatible 结果和本地 ZIP，但代码在其后继续收口，且尚无满足下列格式的同一 exact candidate 报告。最终 261/262 Verifier、ZIP SHA-256、真实 GoLand GUI 场景和 verdict 仍须在同一候选上记录；局部单元测试、旧工作树 Verifier、Gradle 配置成功或 CI YAML 已提交都不能单独填补该缺口。

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
