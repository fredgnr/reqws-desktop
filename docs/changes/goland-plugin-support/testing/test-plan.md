---
title: GoLand 插件支持测试方案
type: test-plan
status: active
updated: 2026-08-31
---

# GoLand 插件支持测试方案

本方案定义 Desktop、插件、兼容性和真实 macOS GoLand 的分层验收，并明确尚未取得的 exact-head 证据不能写成通过。

## 1. 测试目标

验证 ReqWS Desktop 与 GoLand 插件在 macOS 本地环境中形成可靠的受管多仓库工作区：

- Desktop 正确探测并打开 GoLand；
- 插件把 manifest 活动仓库集合自动转换为项目内容，并只读检查用户维护的 Git Root；
- 项目内容只有在 authoritative Workspace Model、live `ProjectFileIndex` 和 GoLand `VgoModulesRegistry` 双重 live projection gate 均收敛后才算成功；Go package/run configuration 不能因 stale registry 与 Project/Search 结果分叉；
- 逻辑移除但仍留在磁盘的仓库不参与默认索引；若仍有 Git mapping，插件只提示用户在 Directory Mappings 中复核；
- manifest 原子替换、错误、快速连续更新和重启均可收敛；
- 插件不越权执行 Git、Go command、依赖下载、删除目录或覆盖用户无关配置，生产路径不调用 VCS mapping writer、不直接写 `.idea/vcs.xml`，不调用 `VgoIntegrationManager`、`VgoStatusTracker` 或任何 Go module schedule/update API，旧 VCS ownership/lock 为零写入；由一次公开普通 roots event 引起的 GoLand 原生 auto-detection、`go list` 或依赖下载必须独立归因；
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
- 在 `workspace.json` 属性校验与 channel open 之间把 `.reqws` rename，并在原名放置指向外部目录的 symlink：读取必须继续绑定原 `.reqws` POSIX descriptor、digest/内容来自内部文件，绝不能读取外部 manifest；系统不支持逐级 `openat` 或稳定 fd → `FileChannel` 时 fail closed；
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
- 外部 `ModuleRootListener` drift 以 `PROJECT_MODEL_CHANGE` 提交；即使 digest 与 clean baseline 相同也必须强制重放，成功后 ordinary automatic same-digest 才恢复 no-op；trigger 合并优先级保持 `MANUAL > TRUST_TRANSITION > PROJECT_MODEL_CHANGE > AUTOMATIC`；
- trust-transition force reconcile 在 clean same-digest baseline 下仍重放 apply；latest blocked read 在 post-registration inspection 前 arm intent，后到 automatic candidate/read failure 不能消费它；trusted automatic refresh 抢在 poll 前完成时仍必须第二次 apply 同 digest并修复 Safe Mode 期间的 model drift，迟到 poll 不得导致第三次强制重放；
- pending manual candidate 后到 automatic candidate 时使用最新内容并继承 manual；后到 automatic read failure 时发布最新失败但保留 intent，下一份 valid automatic candidate 必须以 manual trigger apply，不能应用旧 snapshot 或错误 no-op；
- exception does not deadlock queue；
- applier 抛出的 `ProcessCanceledException` / coroutine `CancellationException` 以同一实例形成非业务 cancellation event，不发布普通 `Failed`、不恢复 clean baseline；owner scope 仍 active 时同一 worker 必须接受并成功 apply 下一 candidate，真正的 scope cancellation/dispose 才关闭 coordinator；普通 observer exception 仍不能终止同步；
- 首次 blank `INACTIVE` cancellation recovery 最多追加一个 automatic successor；successor 的 source generation/attempt 必须可追踪，第二次 cancellation 不得继续排队；
- A 成功、B 部分 apply 后失败、manifest 回退 A 时必须重放 A，不能错误 no-op；
- 新 coordinator/service 即使读到相同持久摘要也必须重新 apply/reconcile。
- 新 service 即使从 PersistentStateComponent 恢复与 snapshot 相同的 `lastAppliedDigest`，transient `validatedProjectionDigest` 也必须为 null；只有该 service 成功 `Applied` 或基于其 clean baseline 的 `NoOp` 可以建立 live projection proof，manifest load/error transition 只能透传而不能从持久摘要制造 proof。

## 6. 插件平台模型测试

优先使用真实 IntelliJ Platform components，不用大面积 mocks。

### 6.1 Content Root / Workspace Model

- initial active roots；
- add；
- remove retained；
- re-add；
- target/companion marker 同事务成对增删；
- remove exclude 激活 repository 时必须对 owned target/marker 执行显式 entity removal，并同时断言 authoritative snapshot 中 pair 消失、`ModuleRootListener` roots-change 计数前进、public `ProjectFileIndex` 在有界等待内满足 `isInContent && !isExcluded`；随后还必须通过第 6.2 节 Go Modules registry gate；再次逻辑移除和 re-add 重复验证反向/正向转换，不得只检查 `.iml`、entity snapshot 或 Project/Search 任一单层；
- `.idea/reqws-managed-project-model.json` 在单一 verified `.idea` handle 上先取得绑定 directory inode 的非阻塞跨 JVM exclusive lock，再完成 state 读取、atomic-replace 能力探针、私有 temp write/force、move、directory force 与回读；temp/write/move/readback 任一失败时不得进入 Workspace Model mutation；
- repository A 持有稳定 `.idea` directory lock 后，rename/unlink 并重建同名 legacy lock 子文件；repository B 用同一 expected generation 写入必须立即失败，A 释放后 B 的旧 generation 仍被拒绝，证明目录项替换不能产生第二把锁或覆盖已撤销 claim；
- 在 verified `.idea` descriptor 已打开、但 lock 尚未打开前，把 `.idea` rename 并以外部 symlink 替换原路径：事务最终必须以 `INVALID_OWNERSHIP_STATE` 失败，随后 lock 打开、state 读取、temp 创建、move 与回读仍全部留在 detached 原目录，外部 state 不变且不创建外部 lock；独立 atomic-file 用例在真实 temp 创建前执行同类替换；
- 每次模型变更前 verified 落盘下一份 managed + recovery claims；model commit 失败或 post-commit trust/dispose gate 失败后，同 JVM state 与磁盘都保留 recovery，不得提前清理；
- 进程重启后的 cold service 从 verified atomic state + 独立最小 `.idea`/`.iml` fixture 的 target/marker proof 收敛：pair 仍完整时保留 recovery 并完成精确删除，target 与 marker 都不存在时才压缩，partial proof 必须冲突；平台测试的 in-memory project 不冒充真实 close/reopen，真实 reopen 仍由 GUI 验收；
- legacy PSC v2/v3 只在 atomic 文件不存在时迁移；atomic 文件存在、损坏、版本不支持或回读不一致时不得回退 PSC 重新取得删除权；
- marker/target partial 或重复、claim/token 跨 managed/recovery 重复、旧 v1 state、物理 marker namespace/symlink 均保守冲突且不越权删除；persisted exact target 缺失但 filesystem-equivalent user alias 与 marker 仍存在时也必须冲突并保留 alias，不能把 identity 等价转化为删除权；
- 现存 exact target exclude 只借用；remove/re-add 生成新 marker，不泄漏旧 marker；filesystem symlink alias 不得冒充 live target exclusion，必须保留 user alias 并增加 owned exact target+marker；
- 大小写、NFC/NFD identity 用于 active/retained 判定；symlink alias 指向 active/current exclude 时仍执行安全冲突检查，活动仓库不得被任何不可移除 entry 覆盖；
- 同 JVM 保留 recovery claims 是预期 crash-recovery 行为，不能把 claim 存在当作当前 exclude 或失败；必须单独断言其不会让已激活 repository 留在 live excluded 状态；
- 合法 retained 名称与 synthetic ownership label（例如 `candidate:.reqws`）不得因 map-key 碰撞而绕过 nested Content Root 冲突；
- unknown module preserved；
- unknown content root preserved；
- owned root manually changed；
- transaction failure；
- no index loop；
- 只测试选定的 workspace-root + owned-excludes 策略；保留 root，排除 `.reqws` 和非活动 Git repository，不排除普通目录。

### 6.2 Go Modules registry

`ProjectFileIndex` 成功不是 Go package 可用的充分条件。平台测试必须通过公开、只读的 `VgoModulesRegistry.getModules(module)` 观察 GoLand registry，并覆盖：

- 对每个存在、且顶层 `go.mod` 是 `NOFOLLOW` regular file 的活动仓库，registry 必须包含其 canonical module root；missing、目录或 symlink `go.mod` 不被插件擅自当作 Go module；
- registry 不得保留等于 retained/excluded 路径或位于其下的 module root；add、remove、re-add 和 Project Model no-op 都分别验证活动集合与排除集合；
- PFI gate 通过后先只读检查 registry；已收敛时不得发布额外 roots event；不一致时只能通过公开 `ProjectRootManagerEx.makeRootsChange` 发布一次 ordinary、`!isCausedByWorkspaceModelChangesOnly` 且 `NO_RESCAN_NEEDED` 的 roots event，单次 projection 无论异步等待多久都不得重复发布；
- ordinary roots event 只允许在 trusted 且 project/service 未 dispose 时发布，并在 EDT write action 的最终 gate 再次检查；Safe Mode、trust 翻转或 dispose 后事件数必须为 0；
- 事件后最多 30 秒只读轮询 registry；异步加入活动 module、移除 excluded module 均可在界内收敛，超时必须映射为 `PROJECT_CONTENT_NOT_CONVERGED`、保持 digest/baseline dirty，并使 Tool Window 不显示 `Synced`/`Active`；等待中的 PCE/coroutine cancellation 保留原实例；
- roots event 全程位于共享 Project Model mutation guard；ReqWS 自己的 Workspace Model mutation 和 ordinary roots event 均不能反触发 drift replay。GoLand/Go plugin 后续异步发布的非 guarded ordinary event 可触发一次 250 ms debounce 的 verify-only `PROJECT_MODEL_FOLLOW_UP`；origin digest、event epoch 与 notification policy 必须绑定到各自 read/candidate。两个重叠 follow-up read 交错时不得消费对方 lineage、恢复 ordinary notification 或形成第三次 replay；worker 被旧 apply 阻塞时，已入 pending 的 follow-up 又被同 digest automatic candidate 覆盖，最终 dequeue 的 candidate 仍必须继承 verify-only，随后同 digest automatic 才恢复 NoOp；follow-up read 失败后若 manifest digest 已变化，恢复 candidate 必须以 `AUTOMATIC` 取得新内容的 notification 权限；
- 生产源码与 plugin bytecode 只允许读取 `VgoModulesRegistry`；必须扫描并拒绝 `VgoIntegrationManager`、`VgoStatusTracker`、`trackModule`、`scheduleUpdatingDependenciesOfAllModules` 及其他 Go tracker/schedule/update API 引用。插件不得直接运行 `go list`、下载依赖或启动任何 Go 进程；平台因 ordinary roots event 自主产生的命令、下载和日志必须单独归因；
- 记录 registry read、ordinary roots event、等待轮次与最终结果，不把 event 已发布或 PFI 已收敛单独当作 Go package 成功。

### 6.3 VCS

当前自动化以纯 classifier、只读 platform adapter 与真实 IntelliJ Platform 组件覆盖以下语义；真实 Directory Mappings/Git Tool Window 的最终行为由 macOS GUI 验收补足。生产零 mutation 是硬性门禁，不以 fake adapter 的“没有调用”单独代替源码/平台/文件证据。

- exact directory last-wins canonicalization，保留完整 VCS 类型与 `rootSettings`；
- active present repository 的精确 Git mapping 分类为 configured；missing、wrong-VCS 分别产生可操作诊断；
- workspace 直系、仍存在且带普通 `.git` 目录的 retained repository mapping 分类为 review required；不存在、普通目录、嵌套路径与 workspace 外 mapping 不误报 retained；
- workspace-root/default 的宽范围 Git mapping 单独提示 review required；workspace 外 unrelated/extra mapping、顺序和 custom `rootSettings` 原样保留且不制造 ownership 诊断；
- snapshot present 的 filesystem/.git 在检查前实时重查，present→missing 产生降级诊断且不触发 setter；snapshot missing 后目录才出现时，本 candidate 仍保持 missing，下一次 manifest refresh 或 `Sync Now` 读取新 candidate 后恢复，避免 Project Model/UI 分叉；
- snapshot present 只捕获一次 live canonical identity；manifest 读取后 repository 入口被普通目录替换或 workspace 内 symlink 从 A retarget 到 B 时，旧 A mapping 不得把当前 B 错判为 configured，且旧 A 可按 live 状态报告 inactive；
- trusted、Safe Mode、startup/restart、manifest add/remove/re-add、automatic refresh、manual `Sync Now` 均不调用 mapping mutation API；
- 生产源码和 plugin bytecode 不引用 `setDirectoryMappings`、`setDirectoryMapping` 或内部 mapping writer；
- 纯 inspection、配置事件和只读复核前后 mapping 与 `.idea/vcs.xml` bytes/hash 不变；manifest 驱动 Project Model 后若 GoLand 原生 auto-detection 改变配置，记录 IDE 设置、事件和差异，不归因于 ReqWS writer；
- `.idea/reqws-vcs-ownership.json` 与 lock 不创建、不读取、不迁移、不改写、不自动删除；预置任意内容也不得影响诊断或 mapping；
- 配置 listener 同步注册 callback 时依赖已经初始化；事件只提交后台只读复核；用户应用 Settings 后状态自动更新；
- 事件丢失时 `Sync Now` 重新读取当前 mappings，但仍不写 VCS；
- Settings writer、`ModuleVcsDetector` 或其他插件在读取前后改变 mapping 时，ReqWS 最多短暂展示旧诊断，绝不覆盖未知 mapping/`rootSettings`；后续事件或 `Sync Now` 收敛视图；
- Git plugin disabled 或读取失败时给出明确 degraded 诊断，不 crash、不回滚成功的 Project Model。
- `ProcessCanceledException` 与 coroutine `CancellationException` 必须原样向上传播，不得转换为 `VCS_DIAGNOSTIC_FAILED` 或发布过期 degraded state。

上述自动化与 GUI 共同证明 ReqWS 没有 VCS writer，因此不再需要 CAS、ownership、pending/tombstone、self-event 或 merge-retry 协议。不得把用户手动 Settings 操作造成的 `.idea/vcs.xml` 变化归因于插件。

### 6.4 Safe Mode

- manifest visible；
- no model mutation；
- no external process；
- trust/dispose 在 Workspace Model transaction commit boundary 翻转时回滚 model/state；model 已在 trusted 时提交而随后 gate 翻转时不得 mint ownership 或推进 digest；
- service terminal dispose 与 `Project.isDisposed` 都必须贯穿 Project Model 投影、VCS 读取、refresh 与 overall digest；VCS 没有写阶段；
- 在 applier 返回与 digest commit 之间设置 barrier，并让 `dispose()` 先取得共享 lifecycle/commit 锁；此时持久与内存 digest 均不得推进、不得发布 `Applied`。反向顺序则 commit 明确线性化在 dispose 之前；
- 只在 blocked 期间通过稳定 trust probe 低频检测，trust transition triggers one sync；
- apply digest D → 进入 Safe Mode → 制造 owned Project Model drift → 恢复 trusted 时必须用独立 force intent 第二次 apply D 并修复漂移，不能走 automatic NoOp；
- 固定 trust poll 后，让 trusted automatic refresh 先完成并取消 poll：已在 blocked 接受边界 arm 的 intent 仍必须由该 automatic candidate 继承，第二次 apply D 恰好一次；
- repeated trust event idempotent。

### 6.5 Tool Window

Swing 像素级 UI 不作为主自动化目标。测试 view model：

- absent manifest 的普通项目在初始 `READING` 期间也保持 hidden；
- 已有 fixed manifest 的首次 read/apply 即使回滚到 hidden `INACTIVE`，也必须由 service-owned bounded retry 自动恢复，不把不可见 Tool Window 当作手动入口；
- 已有 snapshot 的 refresh/`READING` 期间保持 visible；
- synchronized；
- pending；
- degraded；
- VCS missing/wrong-VCS/retained 手动配置诊断；
- Git integration unavailable 与 inspection failure 均显示 `Git Root Status Unavailable`，分别保留 `GIT_PLUGIN_UNAVAILABLE` / `VCS_DIAGNOSTIC_FAILED` 稳定码，不误显示为 Active；
- PFI 或 Go Modules registry 任一 live projection gate 失败时整体进入 `DEGRADED / PROJECT_CONTENT_NOT_CONVERGED`、不记录 clean digest；present repository 显示“项目内容未生效”而非 `Active`，missing repository 仍优先显示目录缺失；只有 authoritative、PFI 与 registry 全部一致时才允许 `Synced`/`Active`；
- 冷启动从 PSC 恢复与 snapshot 相同的 `lastAppliedDigest` 时，Safe Mode、`READING` 与 `SYNCHRONIZING` 都必须把 present repository 显示为“项目内容未生效”，不能显示 `Active` 或 preserved model；已有本 service `validatedProjectionDigest` 时，`READING`/`SYNCHRONIZING` 仍 fail closed；
- 本 service 成功投影 digest D 后再发生 manifest read error，若 UI 保留的 snapshot 仍是 D，则 repository 可继续显示 `Active` 且错误详情显示 preserved model；换成不同 snapshot、projection/ownership/model error、applier cancellation 或新 service 后不得继承这项证明。另用 barrier 覆盖 same-D forced apply 阻塞时 malformed read 已排队、随后 apply failure/cancellation 先撤销 proof 的顺序；最终 read error 不得复活 D，repository 仍为“项目内容未生效”且 preserved model 为 false；
- invalid manifest；
- missing repo；
- copy diagnostics redaction；
- sync action enabled rules。

组件级回归同时锁定：manifest 文本不触发 Swing HTML、状态文字与辅助色同时存在、状态徽标保持内容宽度且不横向拉满、workspace 摘要与 repository 列表分别位于独立卡片、repository header 的标题与 count 分列、repository row 使用紧凑固定高度和主题分隔线、列表内容高度按可见行数计算、1–6 行无滚动条且 7 行起在固定六行高度内滚动、长文本提供完整 tooltip、VCS 手动配置说明可达、`Sync Now` 全宽且只重新检查而不暗示自动修改 VCS，以及两个次级动作在常用窄宽度居中且均可访问。像素、主题和最终排版仍由真实 GUI 对照原型验收。

### 6.6 VFS / lifecycle wiring

- project service 生命周期内只注册一次 `ModuleRootListener`；没有 valid manifest snapshot、service/project 已 dispose 或共享 mutation guard 正在 active 时，roots event 必须被忽略，不得启动 debounce、读取或 apply；
- 外部 roots-change burst 使用固定 250 ms debounce 合并成一次 `PROJECT_MODEL_CHANGE` forced replay；即使 digest 已 clean 也必须重放并修复 drift，随后 ordinary automatic same-digest 恢复 no-op；
- ReqWS Workspace Model mutation 与第 6.2 节 ordinary roots event 都在同一个可嵌套 guard 内执行，listener 不得消费自己的同步事件；同步或事件发布抛错/取消后 guard 计数必须归零；
- GoLand/Go plugin 在 ReqWS ordinary event 之后异步发布一个未 guarded roots event 时，允许一次额外 verify-only replay；该事件的 immutable lineage 必须随 debounce/read/candidate 传递，registry 已收敛时不得再发布 ordinary event。使用 barrier 交错两个事件/read，证明两次都保持 verify-only；再做长于 debounce/registry 窗口的 idle 观察，确认没有第三次 replay、持续 indexing 或 event loop；
- dispose 取消 pending 250 ms debounce，并恰好关闭一次 roots-listener registration；close 抛错也不能跳过 watcher、trust monitor、coordinator 等后续 cleanup。callback 已通过初始 gate、dispose 随后发布 terminal state、callback 再继续捕获 intent 时必须静默丢弃，不抛空 snapshot 异常，也不得重新提交 debounce/read；
- project 初开 manifest 不存在时 watcher 已安装，后续 create 自动进入读取与同步；
- watcher 对 canonical manifest parent 以 `recursive=false` 注册 native root watch，dispose 时 handle 恰好关闭一次；
- watcher refresh 注入包含绝对 workspace/home path 的异常时，production 默认 warning 只包含稳定码与异常类型，不包含 Throwable message、stack trace 或原始路径；连续失败仍只报告一次，成功后可重新报告；
- lifecycle-owned pump 每 1,000 ms fixed-delay 只刷新 fixed manifest 与直接父目录，不递归扫描 workspace；refresh 普通异常后下一 tick 继续，同一连续失败段只报告一次、成功后 re-arm，dispose 后不再刷新；
- 使用 IDE VFS API 之外的真实 hidden temp write + `ATOMIC_MOVE/REPLACE_EXISTING` 替换 manifest；可在替换前完成 fixture VFS binding，但替换完成后不得测试侧显式 refresh，而是放行 production pump tick，再验证 exact-manifest/direct-parent relevant event、350 ms 防抖与单次共享 sync callback；
- symlink 或 macOS top-level alias 打开的 project 使用 canonical manifest target，不漏 VFS event；
- Tool Window 对 file-based project 可动态激活，但 ordinary project 不闪现 UI；
- `shouldBeAvailable` 在 manifest absent 时初始为 false，service state controller 在内容尚未创建时也能在 EDT 完成 absent → create availability transition；
- state listener 的 initial/publish/dispose 并发按队列顺序通知；`DISPOSED` 后 state 永不回退，晚到 publish 被拒绝；
- latest refresh 的 registrar/inspection 等非取消异常必须发布 `ERROR / REFRESH_FAILED`，首读失败不伪造 snapshot/digest且允许下一次重新注册；已有 good state 时保留进入 `READING` 前的 snapshot/digest。PCE/coroutine cancellation 以同一实例结束当前 read、不发布普通错误，并在仍为 latest 且 service/project 存活时恢复最近稳定状态；已有可见 state 的回归必须通过公开 `refresh()`/Tool Window `Sync Now` 路径证明后续成功，不能直接调用内部 automatic refresh；
- 对首次 read PCE、首次 read coroutine cancellation、首次 apply PCE、首次 apply coroutine cancellation 分别运行 platform integration test：只调用一次 production `ReqwsStartupActivity.execute`，不直接调用 `service.refresh*()`、不改写 manifest、不制造 VFS event，最终必须自动进入 `SYNCHRONIZED`、Tool Window 可见且 Sync 可用、`lastError` 为空；
- initial cancellation retry 必须绑定 predecessor generation 与 exact rollback state version并延迟执行；连续两次取消只产生 initial + 1 retry，retry delay 期间 dispose/owner scope cancellation不产生第二次读取，newer read/apply publication 胜出后旧 timer 不发布 `READING`。post-registration read cancellation 仍先清理/接力 provisional listener；apply cancellation 发生后更新 read 已发布 `READING` 时，旧 rollback/retry 均不得覆盖更新 generation；
- VCS external listener 只能在 callback 依赖全部初始化后注册；registrar 同步触发 callback 时不得访问半初始化 service 或递归注册；
- VCS external listener 只在首个有效 candidate 后 provisional 注册，并在注册完成后立即复检同一 snapshot；latest-selection 边界只预约 epoch，平台 registrar、等待接力与 handle close 在两把 lifecycle 锁外执行，只有通过最终 latest gate 的 valid generation 才接受它。普通非 ReqWS project 永不注册，手工发布 VCS event 也不触发 `READING`、manifest read 或 state churn；
- 首个 valid read 的 registrar 或注册后 inspection 阻塞时：更新 inactive/error generation 胜出必须立即撤销 provisional epoch，迟到 handle 返回后恰好关闭一次；更新 valid generation 胜出必须在 STARTING/STARTED 阶段接力同一 epoch，成功 registrar 只调用一次，首个 registrar 失败时由接力 generation 重试；释放旧 read 后不得重复 close 或误关已接力 listener；
- post-registration inspection 抛 cancellation/异常，或更新 generation 在进入 listener preparation 前取消/失败时，latest generation completion cleanup 必须关闭 provisional handle；后续 valid read 可用新 epoch 重新注册。旧 callback 即使已被 publisher snapshot 捕获，其 epoch 校验与 read generation 创建也必须在线性化边界内完成，关闭/重注册后不得触发刷新；
- 并发 refresh 若跨过首次 listener registration，必须通过 registration version 识别窗口并在发布前复检当前 snapshot；registration 与 dispose 交错时不等待外部 registrar，late handle 恰好关闭一次，终态后不得泄漏 listener 或重新进入读取；
- dispose 后 native watch、refresh pump、VFS connection、debounce、trust probe 和 coordinator 均停止；任一 cleanup 失败不得跳过其他资源清理，构造中途失败也不得泄漏已取得资源。

## 7. Plugin Verifier 与构建

每个候选提交至少：

```bash
cd integrations/goland
./gradlew test verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin
./gradlew buildPlugin
```

构建门禁另对 `src/main` 与 ZIP 内 plugin JAR 做符号审计：允许 `VgoModulesRegistry` 的只读查询和公开 `ProjectRootManagerEx.makeRootsChange`，但出现 `VgoIntegrationManager`、`VgoStatusTracker`、`trackModule`、`scheduleUpdatingDependenciesOfAllModules` 或其他 Go tracker/schedule/update 调用即失败；测试 fixture 中的字符串断言不计入 production 引用。

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
- repo-a/repo-b 同时出现在 authoritative Workspace Model、live `ProjectFileIndex`/Project/Search 与 Go Modules registry；各自既有或新建的 `Go Test Package` 配置在点击 Run 前不得显示 `Cannot find package`，运行不得依赖 `Continue Anyway`；
- repo-c 默认不在 authoritative active projection、Project/Search 和 Go Modules registry；若仍有 Git mapping，Tool Window 显示待用户复核而不自动删除；
- 初始缺失 Git Roots 时显示 Settings → Version Control → Directory Mappings 手动步骤；
- 用户手动添加 repo-a/repo-b 的精确 `Git` mappings 后，配置事件自动把 VCS 状态复核为 configured；
- no repeated indexing loop。
- `.reqws` 已成为 excluded content 后，再由 Desktop 原子替换 manifest，确认无需 `Sync Now` 仍自动更新 digest/仓库计数。分别记录 Desktop atomic replace 完成、generic automatic log、authoritative、PFI/Project/Search、Go Modules registry/PACKAGE 与 Tool Window 四层收敛时间；端到端耗时以实测为准，不能把 1,000 ms cadence + 350 ms debounce 直接当作总时限。
- 初始状态先记录每个 fixture repository 的 `go.mod`/`go.sum` SHA-256（不存在的 `go.sum` 记录为 absent），并开启 roots event、GoLand 原生 `go list`/Go process 与 dependency download 的时间线记录；后两者只作为平台行为归因，不作为 ReqWS 主动执行能力。

### 8.3 增删

在 Desktop：

1. 添加 repo-c；
2. 不点击 `Sync Now`，观察 GoLand 自动加入项目内容；确认 authoritative excludes 已移除、PFI/Project/Search 可达、Go Modules registry 出现 repo-c，且 repo-c 的 `Go Test Package` 配置在 Run 前无 `Cannot find package`、不需要 `Continue Anyway` 并实际通过；
3. 在 Directory Mappings 手动添加 repo-c，确认配置事件自动更新状态；
4. 移除 repo-b；
5. 确认 repo-b 目录仍在 Finder，但进入 authoritative exclude、退出 live `ProjectFileIndex`/Project/Search 和 Go Modules registry；移除前保存的同一个 repo-b `Go Test Package` 配置必须变为无效/不可运行，不能继续解析 retained 磁盘目录；
6. 确认插件未删除 repo-b mapping，而是提示用户复核；由用户手动移除后状态自动更新；
7. 重新添加 repo-b；
8. 不点击 `Sync Now`，确认 authoritative、PFI/Project/Search 与 Go Modules registry 自动恢复；复用第 5 步的同一个 repo-b `Go Test Package` 配置，不重建配置即恢复有效，Run 前无 `Cannot find package`、不需要 `Continue Anyway` 并实际通过；Git Root 再次显示待配置，且插件未制造重复 root/module/mapping；
9. 手动恢复 repo-b mapping 并确认最终状态。

每次 add/remove/re-add 必须分别留下四层证据：

1. authoritative Workspace Model、序列化 `.iml` 与 managed ownership state；
2. live `ProjectFileIndex`、Project View 与 Search；
3. 只读 Go Modules registry roots，以及对应 `Go Test Package` 的 package validation/运行结果；
4. Tool Window digest、repository count、每仓状态、整体状态和稳定错误码。

同一时间线还必须记录 Desktop atomic replace、manifest SHA/mtime、generic automatic log、ordinary/后续 roots event 的时间、数量与 `isCausedByWorkspaceModelChangesOnly`，以及 GoLand 原生 `go list`/Go process/依赖下载是否发生。每个 repository 的 `go.mod`/`go.sum` 在 add 前、remove 后、re-add 后和最终状态重新计算 SHA-256（不存在记录 absent），任何变化都必须解释并归因；插件自身不得修改它们。记录端到端实测耗时、registry 最长等待和 `Sync Now` 点击次数（automatic 场景必须为 0）。`Sync Now` 只能在自动结果已判定并留证后作为独立恢复场景，不能填补 automatic 验收；四层任一不一致时不得写“已收敛”。

### 8.4 Go 功能

对初始 repo-a、add 后 repo-c、re-add 后 repo-b 分别使用 `Go Test Package` configuration 验证。每次都先打开 configuration 编辑界面完成 package validation，再点击 Run：活动仓库不得出现 `Cannot find package` 或“configuration is still incorrect”，不得选择 `Continue Anyway` 才能运行；必须记录配置使用的 package、Go Modules registry root、测试结果与 exit code。repo-b 在 remove 后的旧 configuration 必须失效，re-add 后必须复用同一 configuration 自动恢复，不能用删除并重建配置掩盖 registry stale state。

每个活动仓库还验证：

- `go.mod` 与 package 被 GoLand 原生识别；
- code completion；
- navigate declaration；
- find usages；
- run test；
- run/debug main package；
- Git diff/log/commit root selection。

如 roots event 后 GoLand 原生触发 `go list`、Go toolchain 进程或依赖下载，记录触发时间、命令类别、退出结果和网络目标是否为 fixture/本地缓存，并明确归因给 GoLand；ReqWS plugin 日志、源码和进程树中不得出现主动命令调用。运行前后再次核对 `go.mod`/`go.sum` hash。

### 8.5 恢复

- 重启 GoLand；
- Desktop 不运行时打开；
- malformed manifest 后恢复；
- 删除 manifest 后恢复；
- sleep/wake；
- plugin disable/enable；
- manual Sync Now。
- 首次 read/apply cancellation 自动恢复 smoke（若测试构建提供受控 fault injection）；不得通过修改 manifest 或手动 Sync 掩盖启动恢复缺口。
- restart、Desktop absent、malformed → valid 与 plugin enable 后都重新核对 authoritative、PFI/Project/Search、Go Modules registry/PACKAGE 和 Tool Window 四层；保存的 repo-a/repo-c/repo-b `Go Test Package` 配置仍应按当前活动集合正确有效或失效。
- 每次 `Sync Now` 分别记录 Project Model、PFI、Go Modules registry 有界验证与只读 VCS 阶段；确认插件未调用 mapping writer 或 Go tracker/schedule API，并将可能的 GoLand 原生 auto-detection、`go list` 和下载单独归因。

### 8.6 Tool Window 视觉与可用性

- 保存真实 GoLand PNG 到需求包 `ui/` 目录，并在视觉设计文档中与原型同屏对照；候选截图不得写成 `PASS` 或 `GO`；
- 逐项对照状态徽标位置和宽度、摘要卡边界与活动仓库数量、仓库卡 header/count/行分隔/内容高度，以及全宽主按钮和居中次级动作；仅“元素都存在”不能判定为接近原型；
- synchronized、degraded、error 和 Safe Mode 均同时展示文字与辅助色，错误态保留稳定错误码和上次有效模型说明；
- 分别受控制造 PFI 与 Go Modules registry 任一 live projection 不收敛；两种情况下整体都不得显示 `Synced`，present repository 不得显示 `Active`，而显示“项目内容未生效”与 `PROJECT_CONTENT_NOT_CONVERGED`；
- 带已持久化 `lastAppliedDigest` 重启后，在 Safe Mode 及首次 read/apply 进行中检查 present repository 仍为“项目内容未生效”；完成本 service live projection 后再制造 malformed manifest，保留的同 digest snapshot 才可继续显示 `Active` 与 preserved-model 提示；
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
- 预置旧 `.idea/reqws-vcs-ownership.json` 与 lock，验证插件不读、不改、不删且结果不受其内容影响；
- 对每个 repository 的 `go.mod`/`go.sum` 记录前后 hash，证明 Project Model、registry verification、roots event 和 `Sync Now` 都不由插件写入仓库文件；若 GoLand 原生下载改变 `go.sum`，必须有对应进程/日志时间线并单独归因，不能写成 ReqWS 同步副作用。

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
| S-14 | managed-model lock 子文件在持锁后被替换 | 第二 repository 仍被 stable directory inode lock 拒绝，不能并发提交同一 generation。 |
| S-15 | production bytecode 引用 Go module tracker/scheduler | 构建门禁拒绝 `VgoIntegrationManager`、`VgoStatusTracker`、`trackModule`、`scheduleUpdatingDependenciesOfAllModules` 等引用；只允许公开 registry 只读观察和 ordinary roots event。 |
| S-16 | roots event 后出现 `go list`/下载或 module 文件变化 | 记录 GoLand 原生进程/日志并单独归因；ReqWS 不启动 Go command、不下载依赖，`go.mod`/`go.sum` hash 不得出现无法解释的插件写入。 |

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
- `ModuleRootListener` event count vs 250 ms debounced `PROJECT_MODEL_CHANGE` forced replay count；ReqWS guarded Workspace Model/ordinary roots event 为 0 个 drift replay，GoLand 异步 follow-up event 不得形成持续循环；
- 每次 projection 的 ordinary roots event count（registry mismatch 时至多 1）、Go Modules registry read/wait 次数与最长等待；30 秒未收敛必须终止为稳定 degraded error，不能无限等待；
- GoLand 原生 `go list`/Go process/依赖下载次数、持续时间与归因；ReqWS plugin 自身进程启动数必须为 0；
- nominal idle 1 Hz（1,000 ms fixed-delay）targeted refresh count，以及 idle 时 sync callback/apply/indexing 必须保持为 0；
- 定向刷新范围始终只有 fixed manifest 与直接父目录，规模不得随 active/retained repository 数线性扫描；
- indexing 是否结束；
- memory/threads 在重复同步后是否稳定；
- 同一 live coordinator 的自动 clean same-digest no-op；手动 same-digest 与 reopen/new service 仍重新 reconcile；
- Project/Go/Git UI 是否仍可交互。

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

每个 GUI 转换的证据必须明确分成四层：① authoritative Workspace Model/序列化 `.iml`/ownership state；② live `ProjectFileIndex`/Project/Search；③ Go Modules registry roots 与 `Go Test Package` package validation/运行；④ Tool Window digest/count/repository/overall state。任意层缺失或层间不一致均不得判定模型收敛或给出 `GO`；PFI 成功不能代替 registry/PACKAGE，Tool Window 文字也不能反向证明前三层。

测试方案不固化某个历史候选的测试数量、ZIP hash 或 GUI 结论；这些可变证据只写入同次构建、安装和 GUI 验收绑定的 dated verification。每份报告必须记录 candidate 类型（exact commit 或 base HEAD + source fingerprint）、完整门禁结果、ZIP/JAR/installed JAR hash、manifest SHA/mtime、automatic log、四层收敛时间与 `Sync Now` 点击次数；同时逐次记录 ordinary/后续 roots event 的时间、数量、类型，registry 有界等待，GoLand 原生 `go list`/Go process/下载的独立归因，以及各 repository 在 add/remove/re-add 前后的 `go.mod`/`go.sum` SHA-256/absent 状态。局部单元测试、旧候选 Verifier/截图、Gradle 配置成功或 CI YAML 已提交都不能填补当前候选 GUI 缺口。

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
