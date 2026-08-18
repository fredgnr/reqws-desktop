---
title: GoLand 插件支持测试方案
type: test-plan
status: active
updated: 2026-08-18
---

# GoLand 插件支持测试方案

本方案定义 Desktop、插件、兼容性和真实 macOS GoLand 的分层验收，并明确尚未取得的 exact-head 证据不能写成通过。

## 1. 测试目标

验证 ReqWS Desktop 与 GoLand 插件在 macOS 本地环境中形成可靠的受管多仓库工作区：

- Desktop 正确探测并打开 GoLand；
- 插件把 manifest 活动仓库集合自动转换为项目内容，并只读检查用户维护的 Git Root；
- 逻辑移除但仍留在磁盘的仓库不参与默认索引；若仍有 Git mapping，插件只提示用户在 Directory Mappings 中复核；
- manifest 原子替换、错误、快速连续更新和重启均可收敛；
- 插件不越权执行 Git、删除目录或覆盖用户无关配置，生产路径不调用 VCS mapping writer、不直接写 `.idea/vcs.xml`，旧 VCS ownership/lock 为零写入；GoLand 原生 auto-detection 独立归因；
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
- PATH 命中的 Git 必须展开为经过 `git --version` 验证的绝对路径，并能通过完整 availability IPC 响应校验；
- Git availability 尚未知时相关操作保持禁用，但不显示“未检测到 Git”警告或缺失 title；
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

输入 current Project Model managed state + desired manifest + current read-only VCS snapshot，断言：

- add/keep/remove-owned；
- no duplicate；
- unknown user entries preserved；
- existing exact Git mapping 分类为 configured；缺失 mapping、同路径 wrong-VCS、retained mapping 分别产生稳定手动配置诊断；
- unrelated/default/custom `rootSettings` mapping 不改变 Project Model plan，也不被转换为 ownership；
- 同一 live coordinator 的 clean baseline 只让自动/VFS same-digest 请求 no-op；手动 same-digest 必须再次 apply 并能恢复 live projection 漂移；新 coordinator 不从持久摘要 skip；
- active repository 暂时 missing/non-Git 时不修改 mapping，只产生 filesystem/VCS 诊断；
- workspace 内所有额外 Git mapping 和 project-root mapping 均原样保留；project-root 宽范围 mapping 与可验证的 retained repository mapping 只产生 review-required 诊断，其他无关项不误报；
- mapping canonical snapshot 保留完整 `rootSettings`，NFD/NFC root identity 不漏掉精确配置或冲突；
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
- manual same digest 在 clean baseline 下仍重放 apply；重放失败后 baseline 保持 dirty，后续自动同 digest 可继续恢复；
- pending manual candidate 后到 automatic candidate 时使用最新内容并继承 manual；后到 automatic read failure 时发布最新失败但保留 intent，下一份 valid automatic candidate 必须以 manual trigger apply，不能应用旧 snapshot 或错误 no-op；
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
- `.idea/reqws-managed-project-model.json` 使用同目录临时文件、原子替换和回读等值校验；temp/write/move/readback 任一失败时不得进入 Workspace Model mutation；
- 每次模型变更前 verified 落盘下一份 managed + recovery claims；model commit 失败或 post-commit trust/dispose gate 失败后，同 JVM state 与磁盘都保留 recovery，不得提前清理；
- 进程重启后的 cold service 从 verified atomic state + 独立最小 `.idea`/`.iml` fixture 的 target/marker proof 收敛：pair 仍完整时保留 recovery 并完成精确删除，target 与 marker 都不存在时才压缩，partial proof 必须冲突；平台测试的 in-memory project 不冒充真实 close/reopen，真实 reopen 仍由 GUI 验收；
- legacy PSC v2/v3 只在 atomic 文件不存在时迁移；atomic 文件存在、损坏、版本不支持或回读不一致时不得回退 PSC 重新取得删除权；
- marker/target partial 或重复、claim/token 跨 managed/recovery 重复、旧 v1 state、物理 marker namespace/symlink 均保守冲突且不越权删除；
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

当前自动化以纯 classifier、只读 platform adapter 与真实 IntelliJ Platform 组件覆盖以下语义；真实 Directory Mappings/Git Tool Window 的最终行为由 macOS GUI 验收补足。生产零 mutation 是硬性门禁，不以 fake adapter 的“没有调用”单独代替源码/平台/文件证据。

- exact directory last-wins canonicalization，保留完整 VCS 类型与 `rootSettings`；
- active present repository 的精确 Git mapping 分类为 configured；missing、wrong-VCS 分别产生可操作诊断；
- workspace 直系、仍存在且带普通 `.git` 目录的 retained repository mapping 分类为 review required；不存在、普通目录、嵌套路径与 workspace 外 mapping 不误报 retained；
- workspace-root/default 的宽范围 Git mapping 单独提示 review required；workspace 外 unrelated/extra mapping、顺序和 custom `rootSettings` 原样保留且不制造 ownership 诊断；
- snapshot present 的 filesystem/.git 在检查前实时重查，present→missing 产生降级诊断且不触发 setter；snapshot missing 后目录才出现时，本 candidate 仍保持 missing，下一次 manifest refresh 或 `Sync Now` 读取新 candidate 后恢复，避免 Project Model/UI 分叉；
- trusted、Safe Mode、startup/restart、manifest add/remove/re-add、automatic refresh、manual `Sync Now` 均不调用 mapping mutation API；
- 生产源码和 plugin bytecode 不引用 `setDirectoryMappings`、`setDirectoryMapping` 或内部 mapping writer；
- 纯 inspection、配置事件和只读复核前后 mapping 与 `.idea/vcs.xml` bytes/hash 不变；manifest 驱动 Project Model 后若 GoLand 原生 auto-detection 改变配置，记录 IDE 设置、事件和差异，不归因于 ReqWS writer；
- `.idea/reqws-vcs-ownership.json` 与 lock 不创建、不读取、不迁移、不改写、不自动删除；预置任意内容也不得影响诊断或 mapping；
- 配置 listener 同步注册 callback 时依赖已经初始化；事件只提交后台只读复核；用户应用 Settings 后状态自动更新；
- 事件丢失时 `Sync Now` 重新读取当前 mappings，但仍不写 VCS；
- Settings writer、`ModuleVcsDetector` 或其他插件在读取前后改变 mapping 时，ReqWS 最多短暂展示旧诊断，绝不覆盖未知 mapping/`rootSettings`；后续事件或 `Sync Now` 收敛视图；
- Git plugin disabled 或读取失败时给出明确 degraded 诊断，不 crash、不回滚成功的 Project Model。

上述自动化与 GUI 共同证明 ReqWS 没有 VCS writer，因此不再需要 CAS、ownership、pending/tombstone、self-event 或 merge-retry 协议。不得把用户手动 Settings 操作造成的 `.idea/vcs.xml` 变化归因于插件。

### 6.3 Safe Mode

- manifest visible；
- no model mutation；
- no external process；
- trust/dispose 在 Workspace Model transaction commit boundary 翻转时回滚 model/state；model 已在 trusted 时提交而随后 gate 翻转时不得 mint ownership 或推进 digest；
- service terminal dispose 与 `Project.isDisposed` 都必须贯穿 Project Model 投影、VCS 读取、refresh 与 overall digest；VCS 没有写阶段；
- 只在 blocked 期间通过稳定 trust probe 低频检测，trust transition triggers one sync；
- repeated trust event idempotent。

### 6.4 Tool Window

Swing 像素级 UI 不作为主自动化目标。测试 view model：

- absent manifest 的普通项目在初始 `READING` 期间也保持 hidden；
- 已有 snapshot 的 refresh/`READING` 期间保持 visible；
- synchronized；
- pending；
- degraded；
- VCS missing/wrong-VCS/retained 手动配置诊断；
- Git integration unavailable 与 inspection failure 均显示 `Git Root Status Unavailable`，分别保留 `GIT_PLUGIN_UNAVAILABLE` / `VCS_DIAGNOSTIC_FAILED` 稳定码，不误显示为 Active；
- invalid manifest；
- missing repo；
- copy diagnostics redaction；
- sync action enabled rules。

组件级回归同时锁定：manifest 文本不触发 Swing HTML、状态文字与辅助色同时存在、状态徽标保持内容宽度且不横向拉满、workspace 摘要与 repository 列表分别位于独立卡片、repository header 的标题与 count 分列、repository row 使用紧凑固定高度和主题分隔线、列表内容高度按可见行数计算、1–6 行无滚动条且 7 行起在固定六行高度内滚动、长文本提供完整 tooltip、VCS 手动配置说明可达、`Sync Now` 全宽且只重新检查而不暗示自动修改 VCS，以及两个次级动作在常用窄宽度居中且均可访问。像素、主题和最终排版仍由真实 GUI 对照原型验收。

### 6.5 VFS / lifecycle wiring

- project 初开 manifest 不存在时 watcher 已安装，后续 create 自动进入读取与同步；
- symlink 或 macOS top-level alias 打开的 project 使用 canonical manifest target，不漏 VFS event；
- Tool Window 对 file-based project 可动态激活，但 ordinary project 不闪现 UI；
- `shouldBeAvailable` 在 manifest absent 时初始为 false，service state controller 在内容尚未创建时也能在 EDT 完成 absent → create availability transition；
- state listener 的 initial/publish/dispose 并发按队列顺序通知；`DISPOSED` 后 state 永不回退，晚到 publish 被拒绝；
- VCS external listener 只能在 callback 依赖全部初始化后注册；registrar 同步触发 callback 时不得访问半初始化 service 或递归注册；
- 并发 refresh 必须等注册完成后才进入读取；registration 与 dispose 交错时 late handle 恰好关闭一次，终态后不得泄漏 listener 或重新进入读取；
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
- repo-c 默认不在 Project/Search；若仍有 Git mapping，Tool Window 显示待用户复核而不自动删除；
- 初始缺失 Git Roots 时显示 Settings → Version Control → Directory Mappings 手动步骤；
- 用户手动添加 repo-a/repo-b 的精确 `Git` mappings 后，配置事件自动把 VCS 状态复核为 configured；
- no repeated indexing loop。

### 8.3 增删

在 Desktop：

1. 添加 repo-c；
2. 观察 GoLand 自动加入项目内容，并显示 Git Root 待手动配置；
3. 在 Directory Mappings 手动添加 repo-c，确认配置事件自动更新状态；
4. 移除 repo-b；
5. 确认 repo-b 目录仍在 Finder，且退出 Content Root 和 Find in Files 默认范围；
6. 确认插件未删除 repo-b mapping，而是提示用户复核；由用户手动移除后状态自动更新；
7. 重新添加 repo-b；
8. 确认项目内容恢复、Git Root 再次显示待配置，且插件未制造重复 root/module/mapping；
9. 手动恢复 repo-b mapping 并确认最终状态。

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
- 每次 `Sync Now` 分别记录 Project Model 阶段与只读 VCS 阶段；确认插件未调用 mapping writer，并将可能的 GoLand 原生 auto-detection 单独归因。

### 8.6 Tool Window 视觉与可用性

- 保存真实 GoLand PNG 到需求包 `ui/` 目录，并在视觉设计文档中与原型同屏对照；候选截图不得写成 `PASS` 或 `GO`；
- 逐项对照状态徽标位置和宽度、摘要卡边界与活动仓库数量、仓库卡 header/count/行分隔/内容高度，以及全宽主按钮和居中次级动作；仅“元素都存在”不能判定为接近原型；
- synchronized、degraded、error 和 Safe Mode 均同时展示文字与辅助色，错误态保留稳定错误码和上次有效模型说明；
- 浅色和深色主题使用平台颜色，无硬编码浅色背景导致的对比度问题；
- 常用最窄宽度下 workspace/branch/repository 长文本安全截断并可查看完整值，仓库行不纵向拉伸，`Sync Now`、`Open Manifest File`、`Copy Diagnostics` 都可达；
- Tab/Shift+Tab 焦点顺序和按钮 enable 状态符合 view model，屏幕阅读器不依赖装饰状态圆点；
- content 已实例化后 close project 与退出 IDE 不产生 `AlreadyDisposedException` 或晚到 availability 更新。

### 8.7 用户配置保护

- 增加用户 VCS mapping，并记录顺序、VCS 类型、`rootSettings` 与 `.idea/vcs.xml` hash；
- 增加非 ReqWS module/content root；
- 同步；
- 验证纯 VCS inspection/配置事件不改变 mapping、顺序、`rootSettings` 或 `.idea/vcs.xml`；Project Model 可能触发的 GoLand 原生 auto-detection 另行记录；
- 制造所有权冲突；
- 验证插件只报告手动配置差异而不是强制覆盖；
- 预置旧 `.idea/reqws-vcs-ownership.json` 与 lock，验证插件不读、不改、不删且结果不受其内容影响。

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
| S-09 | Safe Mode | 无模型/进程副作用，VCS 仍只读。 |
| S-10 | malicious app candidate | 不执行未经验证的任意 binary。 |
| S-11 | Project Model state tamper | 保守恢复，不删除 unknown entries。 |
| S-12 | real workspace deletion request | 插件没有该能力。 |
| S-13 | old VCS ownership/lock tamper | 文件保持 inert，不影响诊断、不触发迁移/清理或 mapping 写入。 |

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
- 同一 live coordinator 的自动 clean same-digest no-op；手动 same-digest 与 reopen/new service 仍重新 reconcile；
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

2026-08-18 当前源码候选已在 JDK 21 下通过 220 项插件测试、项目配置/结构检查、GO-261.25134.147 / GO-262.8665.270 Plugin Verifier（均 `Compatible`）和 ZIP 构建；ZIP SHA-256 为 `d4ee9ee6352cf8a8c0ee3ca7e198fb37357f967ca881644dcd0c1790136b7652`，大小 394,894 bytes；Desktop `npm run check` 通过 31 个测试文件、335 项测试。这些是本轮自动化证据，但尚未形成满足下列格式、绑定推送后 exact commit 的完整 GUI 报告。真实 GoLand 场景和 verdict 仍须在同一候选上记录；局部单元测试、旧工作树 Verifier、Gradle 配置成功或 CI YAML 已提交都不能单独填补 GUI 缺口。

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
