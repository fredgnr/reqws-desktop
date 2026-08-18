---
title: GoLand 插件支持技术方案
type: technical-design
status: active
updated: 2026-08-18
---

# GoLand 插件支持技术方案

本文定义 ReqWS 本次 GoLand 支持的实现方式，并为 macOS 本地 Codex agent 保留明确的探索门禁。方案只覆盖跨 IDE manifest 契约、Desktop GoLand 入口和 GoLand 插件 v0.1，不建立范围外能力的协议或代码骨架。

## 1. 方案结论

本次交付采用以下单向架构：

```text
ReqWS Desktop（唯一控制面）
  ├─ repository catalog
  ├─ clone / checkout / add / logical remove
  ├─ 原子写入 .reqws/workspace.json
  ├─ 继续维护 .code-workspace
  └─ 探测并打开 GoLand workspace root
                 │
                 │ 只读文件契约
                 ▼
ReqWS GoLand Plugin（IDE adapter）
  ├─ 读取并校验 manifest
  ├─ 计算活动仓库目标状态
  ├─ 同步受管项目内容
  ├─ 同步受管 Git VCS mappings
  ├─ 监听 manifest 原子替换
  └─ 提供 ReqWS Tool Window 与诊断
```

关键决策：

1. Desktop 继续是 workspace、Git 生命周期和 manifest 的唯一 writer。
2. 复用现有 `.reqws/workspace.json` schema v1，不增加第二份 IDE manifest，也不升级 schema major。
3. 插件只读 manifest，不 clone、不切分支、不删除目录、不执行仓库命令。
4. Desktop 打开 workspace root；插件把 manifest 中的活动仓库投影到 GoLand 项目模型。
5. 项目模型采用策略 B：保留 GoLand 创建的 workspace-root Content Root，只管理 `.reqws` 与已识别保留 Git repository 的 excludes；策略 A 因无法证明默认 root 的 ReqWS ownership 而未通过破坏性变更安全门禁。
6. 插件源码与 Desktop 同仓，放在 `integrations/goland/`，使用独立 Gradle 构建。
7. 本次产物是本地可安装 ZIP；不实现签名、Marketplace、自动更新或发布服务。
8. 本次不引入 Desktop 与插件的双向通信，不生成或修改 `go.work`。

本文状态为 active，表示上述设计是当前实现依据，不表示功能已完成交付。Desktop 自动化、插件单元/平台测试、261/262 Plugin Verifier、ZIP 哈希和真实 GUI 分属不同证据层；在同一 exact commit 的 Verifier 与 GUI 结果形成前，不给出兼容性或 `GO` 结论。

## 2. 现有实现基线

当前仓库已经具备可复用基础：

- manifest 路径固定为 `<workspace-root>/.reqws/workspace.json`；
- manifest 和 `.code-workspace` 由原子 JSON writer 写入；
- workspace 创建先在 staging 完成 clone 和分支切换，再发布最终目录；
- workspace mutation 由进程内 FIFO coordinator 串行化；
- `addRepository` 完成 clone 和 branch 后更新 manifest；
- `removeRepository` 只更新 manifest 和 `.code-workspace`，有意保留磁盘目录；
- `sync` 以 manifest 重新生成 `.code-workspace`；
- `EditorLauncher` 已采用参数数组、`shell: false`，并检查标准和用户 Applications 目录；
- `SystemAvailability`、typed IPC、preload 和 renderer 已有编辑器扩展位置；
- shared Zod schema 已约束 manifest v1 的 repository ID、name、relativePath 和绝对路径。

因此本次不新增后台 daemon、额外状态数据库或跨进程 RPC。

## 3. 目录与构建边界

实施后的建议结构：

```text
reqws-desktop/
├── src/
│   ├── main/
│   │   ├── ipc/editor-handlers.ts
│   │   └── services/editor-launcher.ts
│   ├── preload/
│   ├── renderer/
│   └── shared/
│       ├── ipc-channels.ts
│       ├── schemas.ts
│       └── types.ts
├── integrations/
│   └── goland/
│       ├── README.md
│       ├── build.gradle.kts
│       ├── settings.gradle.kts
│       ├── gradle.properties
│       ├── gradle/wrapper/
│       ├── gradlew
│       ├── gradlew.bat
│       └── src/
│           ├── main/kotlin/com/reqws/goland/
│           ├── main/resources/META-INF/plugin.xml
│           ├── main/resources/messages/
│           └── test/kotlin/com/reqws/goland/
└── docs/changes/goland-plugin-support/
```

已固定标识：

```text
Kotlin package: com.reqws.goland
Plugin ID:      com.reqws.workspace
Display name:   ReqWS
Initial version: 0.1.0
```

插件版本独立于 Electron `package.json`。Gradle 依赖、sandbox 和构建输出不得进入 Desktop runtime bundle，也不得改变现有 `npm ci` 和 Electron packaging 依赖图。

## 4. 所有权边界

| 能力 | Desktop | GoLand 插件 |
|---|---:|---:|
| repository catalog | 唯一 owner | 不读取全局 state |
| clone / fetch / checkout feature branch | 是 | 否 |
| manifest 写入 | 唯一 writer | 只读 |
| `.code-workspace` 写入 | 是 | 忽略 |
| GoLand workspace root | 否 | 保留平台既有条目，不接管 |
| `.reqws` 与保留 Git repo excludes | 否 | 仅 owner 自己创建的条目 |
| GoLand 受管 VCS mapping | 否 | owner 自建项；只借用现存等价项 |
| `.idea` 中插件私有状态 | 否 | owner |
| 用户自定义 module/root/mapping | 否 | 只保留，不接管 |
| repository/workspace 删除 | 当前不自动执行 | 绝不执行 |
| `go.work` | 本次不写 | 本次不写 |
| 外部进程 | 仅 Desktop 启动 GoLand | 不启动 |

插件持久化状态只记录技术所有权和恢复所需摘要，不能成为 repository membership 的第二份事实来源。

## 5. Manifest 跨 IDE 契约

### 5.1 文件与 schema

本次继续使用：

```text
<workspace-root>/.reqws/workspace.json
```

支持的结构是现有 schema v1：

```json
{
  "schemaVersion": 1,
  "id": "ws_...",
  "name": "feature-login",
  "featureBranch": "feature/login",
  "rootPath": "/Users/example/workspaces/feature-login",
  "workspaceFilePath": "/Users/example/workspace-files/feature-login.code-workspace",
  "repositories": [
    {
      "catalogRepositoryId": "repo_...",
      "name": "service-a",
      "url": "git@github.com:org/service-a.git",
      "defaultBranch": "main",
      "relativePath": "service-a"
    }
  ],
  "createdAt": "2026-08-14T00:00:00.000Z",
  "updatedAt": "2026-08-14T00:00:00.000Z"
}
```

不新增 `ide-workspace.json`，原因是重复 membership 会放大 create、add、remove 和 rollback 的一致性成本。现有字段已足够表达 GoLand 的目标状态，因此不升级 `schemaVersion`。

### 5.2 规范语义

- `repositories` 是有序的活动仓库集合；
- 不在该数组中的磁盘目录不是当前 IDE workspace 成员；
- `rootPath` 参与 workspace identity，必须与当前打开 project 的 canonical root 一致；
- `relativePath` 从 root 解析活动仓库目录；当前 v1 同时要求其等于安全 repository name；
- `url` 使用与 Desktop `isSafeRepositoryUrl` 相同的安全契约：接受无凭据 HTTPS、`ssh://` 和 SCP-like SSH，并以双方相同的纯文本 host/port/IPv4/IPv6 规则保留国际化域名和既有 inert path 文本；拒绝明文 HTTP、本地或 remote-helper 形式、C0/DEL、前导 option、无效 authority、密码及 credential-like query/fragment；`defaultBranch` 和 `featureBranch` 只校验。插件不据任何字段访问网络、执行命令或写入普通日志；
- `workspaceFilePath` 供 VS Code/Cursor 使用，插件忽略；
- `updatedAt` 只用于展示，不能作为唯一同步版本；
- 所有 Zod `.trim()` 字段在 Kotlin 侧使用显式 ECMAScript `TrimString` code-point 集合；包括 `U+FEFF`，但不把 `U+001C` 等 Java/Kotlin 扩展控制字符当作可裁剪空白；
- 未知附加字段可忽略；不支持的 major version 必须拒绝。

### 5.3 内容摘要

插件读取 manifest 原始 UTF-8 bytes 后计算：

```text
ManifestDigest = SHA-256(fileBytes)
```

摘要用于：

- 同一 live coordinator 生命周期内，上次完整 model/VCS apply 成功后，自动/VFS 触发的相同内容幂等 no-op；用户触发的 `Sync Now` 即使 digest 相同也强制重新核对实时 Workspace Model、VCS mappings 和文件系统投影；
- VFS 事件乱序或合并时重新识别最终内容；
- 项目冷启动重新收敛和诊断关联；
- 错误去重和诊断关联。

持久化的最近摘要只供 UI 和诊断展示，不作为 project reopen 后跳过 apply 的依据。每个新 project service 都从 manifest、当前 Workspace Model 和 ownership state 重新收敛，避免把进程外变化或部分持久化状态误判为 no-op。

不在 schema v1 中增加递增 revision，避免旧 Desktop、回滚内容和跨文件事务引入额外兼容路径。

### 5.4 Golden fixtures

建立仓库内共享 fixture，例如：

```text
integrations/goland/src/test/resources/manifests/
├── valid-minimal-v1.json
├── valid-full-v1.json
├── valid-unknown-fields-v1.json
├── valid-ecma-trim-v1.json
├── invalid-duplicate-name.json
├── invalid-non-ecma-trim-control-v1.json
├── invalid-relative-path.json
└── unsupported-v2.json
```

TypeScript tests 与 Kotlin tests 必须读取等价 fixture，并验证：

- Desktop 当前 writer 生成的 v1 可被插件读取；
- 插件接受的 valid v1 同时被 Zod schema 接受；
- duplicate、unsafe path 和 unsupported version 的判定一致；
- URL 永远不触发网络或命令行为。

URL 安全契约另使用双方直接读取的版本化 corpus：

```text
integrations/goland/src/test/resources/contracts/repository-url-safety.json
```

语料覆盖 raw 与 percent-encoded UTF-8 IDN、visible Unicode、zero-width host 字符、HTTPS/SSH/SCP-like、IPv4/IPv6、userinfo、编码 credential key、非法端口，以及畸形 percent、反斜杠和普通 path 原始字符的既有兼容语义。两端不调用各自不等价的 WHATWG/Java URI/IDNA parser 决定这份 manifest 安全契约；新增接受或拒绝规则必须先在该 corpus 中表达，再由 TypeScript 与 Kotlin 同时验证。

为避免校验收紧把整个旧 `state.v1` 判为损坏，`appStateSchema` 另有只读兼容边界：仅 persisted catalog 可按 GoLand 功能加入前的原始 Desktop URL policy 继续解析。新建/编辑仓库、连接测试和 workspace manifest 一律仍走当前共享严格 policy；legacy-only URL 因此可以列出、删除或改正，但在改正前不能进入新 manifest。这条 migration path 不进入 Kotlin，也不扩大插件接受集。

不引入跨 TypeScript/Kotlin 的代码生成步骤；共享的是 JSON 规范和行为证据。

### 5.5 写入时序

Desktop 保持当前原子写入时序。插件只在 manifest 成为稳定可读文件后同步，不等待 `.code-workspace` 或全局 state 一起提交。若 Desktop 后续恢复旧 manifest，插件根据新摘要再次收敛。

该方案允许跨进程最终一致，但不要求 Desktop 与 GoLand 之间存在多文件分布式事务。

## 6. ReqWS Desktop 设计

### 6.1 Shared types 与 API

扩展现有类型：

```ts
export interface SystemAvailability {
  git: AvailabilityItem;
  vscode: AvailabilityItem;
  cursor: AvailabilityItem;
  goland: AvailabilityItem;
}

export interface ReqwsAPI {
  editors: {
    // existing methods
    openGoLand(workspaceId: string): Promise<void>;
  };
}
```

对应修改：

- `src/shared/types.ts`；
- `src/shared/ipc-channels.ts`；
- 输入/响应 Zod schema；
- `src/main/ipc/editor-handlers.ts`；
- `src/main/ipc/create-main-services.ts`；
- `src/preload/index.ts` 和 preload contract；
- renderer availability state、按钮和错误展示。

`workspaceId` 继续由 Main 使用 `idSchema` 重新验证；Main 必须重新读取 workspace detail 并确认状态为 `ready`，不能信任 renderer 提供路径。

availability response 使用判别联合：`available:true` 必须携带经过 resolver 验证的绝对 candidate path，且不能同时携带 `NOT_FOUND`；`available:false` 不得携带 path。Git resolver 按 PATH 目录顺序展开稳定的绝对 `git` 候选，再追加固定 macOS fallback，并对返回候选执行 `git --version`；不得把裸命令名或验证后未经执行的替代路径写入响应。GoLand 等依赖 bundle identity 的 resolver 仍按各自章节完成 canonical 校验。renderer 在首次响应尚未返回时只禁用操作，不把 unknown 状态宣告为“未安装”。

### 6.2 GoLand 安装探测

生产 resolver 只读取三个边界明确的候选来源：

1. `/Applications/GoLand.app`；
2. `~/Applications/GoLand.app`；
3. 最多 1 MiB、最多 1024 条记录的 `~/Library/Application Support/JetBrains/Toolbox/state.json` 中声明的 GoLand 安装项。

安全规则：

- 不递归扫描 home、不调用 Spotlight，也不信任 PATH 中任意 `goland`；
- Toolbox 的 launcher 必须是绝对路径，并只能反解到 `.app/Contents/MacOS/<executable>` 结构；
- Toolbox 来源在执行任何 `plutil` 前按 source/app/launcher 去重，最多接受 64 个唯一 GoLand 候选；全部候选使用固定 4-worker 验证，避免损坏 state 触发无界子进程或文件描述符；
- 候选 app 先 canonicalize，再校验 bundle、`CFBundleIdentifier == com.jetbrains.goland`、声明的 `CFBundleExecutable`、实际普通 executable 和执行权限；
- 多个候选按稳定版优先、版本降序、来源优先级和 canonical path 做确定性排序；
- Toolbox state 缺失、过大、损坏或条目无效时只忽略该来源，不执行未验证路径。

标准路径和实际 Toolbox layout 仍需进入 exact-head GUI 证据；发现规则不会为提高命中率扩大为全盘扫描。

### 6.3 启动策略

启动目标始终是 `manifest.rootPath`，不是 `.code-workspace`。

生产启动命令固定为：

```text
/usr/bin/open -a <canonical-GoLand.app> <validated-workspace-root>
```

所有路径：

- Main 先按 workspace ID 重新加载 workspace detail 并要求状态为 `ready`，不信任 Renderer 提供路径；
- root 来自重新读取的 manifest，必须是绝对普通目录，固定 manifest 必须是普通文件；除 macOS 系统维护的第一层别名（例如 `/tmp → /private/tmp`、`/var → /private/var`）外，root 和 manifest 的更深层 symlink/alias 均拒绝；
- app 使用 canonical path，`open` 的 root 参数保留已经验证过的原始安全拼写，使其与 Desktop `PathService` 的 workspace identity 一致；
- command 和 args 分离；
- `shell: false`、`stdio: ignore`；
- 处理同步 spawn exception、child `error` 和非零 exit；
- 不把 manifest 字段拼接到 shell command；
- 不静默把已选择 app 的失败伪装成成功，也不回退到 PATH executable。

GoLand 在已有项目窗口时如何打开新项目由用户的 IDE 设置决定。Desktop 不使用私有或未记录参数强行覆盖“New window / Current window / Ask”。

### 6.4 Renderer 与 i18n

工作区卡片和详情增加 GoLand 操作：

- unavailable：禁用，并显示本地化原因；
- workspace 非 `ready`：沿用现有禁用逻辑；
- launching：阻止重复点击；
- availability 尚未返回：保持禁用但不显示“未找到编辑器”；
- failure：进入现有错误面板，保留稳定错误码和 detail；
- Desktop 不显示未经握手验证的“插件已安装”状态。

任何新增中文文案以 `zh-CN` 为 source catalog，并遵循仓库 `reqws-i18n` skill 完成英文翻译和一致性检查。

## 7. GoLand 插件架构

### 7.1 组件

```text
ReqwsProjectDetector
  └─ 定位 <project-root>/.reqws/workspace.json

ManifestReader
  ├─ 普通文件与大小门禁
  ├─ UTF-8 / JSON / schema validation
  ├─ canonical path containment
  └─ SHA-256 digest

ReqwsProjectService
  ├─ current snapshot
  ├─ last applied digest
  ├─ managed ownership
  ├─ lifecycle state
  └─ diagnostics

ManifestVfsListener
  └─ exact path / parent filtering + debounce

SyncCoordinator
  ├─ single-flight
  ├─ latest-wins coalescing
  ├─ background read/validate
  └─ serialized apply

ProjectModelAdapter
  ├─ selected Content Root strategy
  ├─ diff planner
  ├─ ownership protection
  └─ write transaction

VcsMappingAdapter
  ├─ borrow equivalent user mapping
  ├─ add missing active roots
  └─ remove ReqWS-owned inactive roots

ReqwsToolWindowFactory
  ├─ workspace summary
  ├─ repositories
  ├─ lifecycle status
  ├─ warnings/errors
  └─ Sync Now / Open Manifest / Copy Diagnostics
```

### 7.2 项目生命周期

1. 每个有 file-based root 的 project opened 后启动轻量项目服务，并在后台 canonicalize root；
2. watcher 在第一次读取前安装，因此固定 manifest 尚不存在时也能接收后续 create；项目状态保持 inactive，Tool Window 不显示；
3. manifest 存在：后台读取、digest 和校验；
4. Safe Mode：保存只读 snapshot，但不执行 model/VCS apply；
5. trusted：提交首次同步；
6. project-level VFS listener 只关注 manifest exact path 及直接父目录；
7. project close/dispose：取消 debounce、IO 和 pending sync；
8. reopen：不使用持久摘要作 skip，从 manifest、当前模型和插件 ownership state 强制重新收敛。

### 7.3 状态机

建议项目级状态：

```text
INACTIVE
READING
SAFE_MODE_BLOCKED
SYNCHRONIZING
SYNCHRONIZED
DEGRADED
ERROR
DISPOSED
```

转换原则：

- invalid manifest、root mismatch 和 path escape 进入 `ERROR`，保留上次有效模型；
- 单个活动仓库 missing 可同步其他有效仓库，整体为 `DEGRADED`；
- project model 成功但 VCS mapping 失败为 `DEGRADED`，允许重试；
- model 和 VCS 均成功后才更新持久化 `lastAppliedDigest` 和 coordinator 的内存 no-op baseline；
- 相同 digest 只在同一个 coordinator 生命周期、内存 baseline 仍为 clean 且触发源不是手动 `Sync Now` 时 no-op；
- 手动 `Sync Now` 始终进入 apply；若重放失败，内存 baseline 保持 dirty，使后续自动或手动同 digest 请求继续尝试恢复；
- 手动 reconcile intent 跨 manifest read generation 与 pending coalescing 保留：后到的自动 candidate 仍以最新内容为准但继承 manual trigger；后到的 read failure 仍作为最新失败发布，同时 intent 保留到下一份 valid candidate 真正开始 apply，不能通过应用旧 snapshot 隐藏读取错误；
- 开始应用不同 digest 时先使内存 no-op baseline 失效；若后层失败，回退到先前 digest 也必须重放，不能把部分提交状态误判为 no-op；
- 文件恢复后自动重新进入 `READING`。
- service state 通过锁内排队、锁外串行通知的 terminal publisher 发布；`DISPOSED` 一旦进入队列即成为永久终态，随后到达的 read/apply 回调不能覆盖终态或产生晚到的非终态通知；listener 注册与 dispose 使用同一线性化边界。

### 7.4 线程与事务

- 文件读取、JSON、digest、realpath、directory check：后台线程或 coroutine；
- VFS listener callback 不做阻塞 IO；
- Workspace Model snapshot 按平台 API 要求读取；
- 项目模型 apply 在 write action / Workspace Model update transaction 中执行；
- VCS mapping 的文件系统身份、候选 plan 与 verified atomic ownership checkpoint 都在后台执行；EDT closure 只做最终完整 mapping equality/revision gate 和整表 `setDirectoryMappings`，避免 Settings writer 在 final read/set 之间插入且不把文件 IO 带上 EDT。项目级 mapping change tracker 同时维护单调 revision 与待确认的完整 external snapshot，用来恢复不走 EDT 的 pooled auto-detect writer 并进入有界 quiescent merge-retry；
- trust 与 dispose 不只在 orchestration 起点检查：project service 自身的 terminal dispose probe 与 `Project.isDisposed` 一起贯穿两层投影；Workspace Model transaction 的写入/提交边界、VCS ownership pre-revoke、mapping set、最终 ownership record、refresh 后以及 overall digest 前都重新 gate；事务内翻转通过异常回滚；
- Workspace Model 与 ownership 文件不是同一事务，因此模型 authoritative state 位于 `<workspace-root>/.idea/reqws-managed-project-model.json`，通过同目录临时文件、原子替换和回读校验形成 verified atomic 持久化边界；legacy `PersistentStateComponent` 只作为一次迁移来源，不再承担 mutation journal 的 durability；
- 每次 Workspace Model mutation 前先把下一份 managed claims 与 recovery claims 一起落盘。当前 JVM 即使已经复核 model commit，也不清除 recovery claims；进程重启后的 foreign-JVM cold load 若仍看到同 token 的完整 target+marker pair，就必须保留 recovery 并完成精确删除，只有同时确认 target 与 marker 都已不存在时才可压缩该 recovery。partial、重复、跨集合或校验失败一律 fail closed，model 提交后的 trust/dispose gate 失败不推进 digest；
- Tool Window view model 转 Swing component 在 EDT 更新；
- 同一项目最多一个 apply；pending 只保存最新 candidate。

示意：

```kotlin
onManifestEvent {
  debounce.submit {
    val candidate = manifestReader.readLatest()
    syncCoordinator.offer(candidate)
  }
}

syncCoordinator {
  while (hasPendingCandidate()) {
    val latest = takeLatestCandidate()
    if (trigger != MANUAL && latest.digest == inMemoryCleanAppliedDigest) continue
    inMemoryCleanAppliedDigest = null
    val plan = planner.plan(currentModel(), state.ownership, latest)
    applyProjectModel(plan)
    applyVcsMappings(plan)
    inMemoryCleanAppliedDigest = latest.digest
    state.markApplied(latest)
  }
}
```

新 service 的 `inMemoryCleanAppliedDigest` 从 `null` 开始；持久化摘要不注入它。任一不同 digest 的 apply 可能已提交前一层，因此进入 apply 前先清空 baseline，只有两层完全成功才恢复。

生产实现必须使用当前 JetBrains 推荐的 coroutine/lifecycle API；伪代码不授权阻塞 EDT 或使用全局 unmanaged executor。

## 8. 项目模型策略与实验结论

Workspace Model 使用 261 的公开 `WorkspaceModel.update`、entity DSL 和 `VirtualFileUrlManager`。生产代码不使用 obsolete `updateProjectModel`，也不使用 `@Internal`、`@Experimental`、反射或私有 API。

### 8.1 策略 A 未通过所有权门禁

策略 A 原计划移除 GoLand 打开目录时生成的 workspace-root Content Root，再为活动仓库增加多个 Content Root。API spike 证明这些实体可以通过公开 Workspace Model API 更新，且 module 的 SDK/dependency 可以保留；但默认 workspace root 不是 ReqWS 创建的条目，也没有可验证的 ReqWS ownership。

因此 ReqWS 无法在满足“所有权不确定时不做破坏性移除”的同时删除该 root。按 A → B 门禁，策略 A 在用户配置保护和 restart ownership 条件上失败，不进入 production，也不通过名称或路径启发式接管默认 root。

### 8.2 选定策略 B：workspace root + owned excludes

唯一 production path 保留既有 workspace-root Content Root，并只调整它的 excludes：

- 固定排除 `.reqws`；
- 扫描 workspace root 的直接子目录，只把不在活动集合且包含普通 `.git` 目录的子目录识别为保留 repository 并排除；
- 普通 notes、未知目录和嵌套路径不排除；
- 活动 repository 不得仍被任何不可移除 exclude 覆盖，否则报 `OWNERSHIP_CONFLICT`；
- nested Content Root 冲突检查使用 URL collection 而不是由不可信相对名拼成的 map key，避免合法目录名与 synthetic ownership label 碰撞后漏检；
- 不新增、不删除 module，不修改 SDK、library、source root 或其他 Content Root。

Workspace Model 不持久化单个 exclude 的自定义 entity-source tag，因此 production 不把 runtime `EntitySource` 当作 restart ownership 证据。每个由插件创建的 target exclude 都在同一 Workspace Model transaction 中配一个 companion marker exclude：

```text
<workspace-root>/.reqws/.goland-ownership/<32-lowercase-hex-random-token>
```

marker URL 是故意不存在的虚拟路径，和 target 一样使用现有 Content Root 的 entity source，从而由标准 `.iml` exclude 序列化保存；插件不在磁盘创建 marker directory 或文件。`<workspace-root>/.idea/reqws-managed-project-model.json` 保存 target module、workspace-relative target、128-bit random marker token、managed claims 和 recovery claims；写入必须原子发布并回读验证。legacy PSC v2/v3 只允许在 atomic 文件尚不存在时迁移，迁移成功后所有权判断只读 atomic 文件。删除权必须同时由 verified managed/recovery claim、唯一 target entity 和唯一 marker entity 三者证明，target 与 marker 在同一事务中增删；任何变更前先落盘下一份 managed + recovery 集合，且同一 JVM 不清 recovery。marker namespace 真实存在、为 symlink，或 file/state/target/marker 缺失、重复、跨集合冲突时均 fail closed。用户已有的等价 exclude 只借用，不取得删除权。

该策略会保留 workspace root 的一般内容范围；被排除的保留 repository 在用户显式显示 excluded files 时仍可能可见。其真实 Project/Search/Go 行为仍必须由 GUI smoke 证明，不能仅由 API spike 推断。

### 8.3 策略 C 不执行

策略 B 已满足当前公开 API 和所有权设计门禁，因此不继续评估“每活动仓库一个 module”。仓库不保留策略 A/C 的 runtime fallback 或隐藏切换项；若后续 exact-head GUI 证明 B 无法满足验收，应回到本需求重新评审，而不是自动采用未验证策略。

### 8.4 仍待完成的选择证据

当前结论是 production 设计决策，不等同于最终验收。GoLand 2026.1.3/2026.2 Plugin Verifier、add/remove/re-add、restart、Go completion/navigation/test/debug、Project/Search 隔离及规模行为必须在同一 exact-head 验证中记录；未取得这些证据前不创建 `GO` 报告。

## 9. 受管条目所有权

插件不得把当前全部项目 roots 或 VCS mappings 替换为 manifest 集合。

项目私有状态分为两个插件自有的 verified atomic ownership 文件与一个非授权性的同步摘要。示意：

```text
<workspace-root>/.idea/reqws-managed-project-model.json
  strategy + targetModuleName + managedClaims + recoveryClaims

<workspace-root>/.idea/reqws-vcs-ownership.json
  stateVersion: 2 + managedMappings + pendingAdds + pendingRemovals

ReqwsSynchronization PersistentStateComponent
  lastAppliedDigest（只供 UI/诊断，不授权删除或 reopen skip）
```

规则：

- 路径以 workspace-relative 形式保存，读取时重新 containment 校验；
- 不保存 repository URL、remote 或 Git credential；marker token 是本地随机 ownership nonce，不来自 manifest，也不是访问凭据；
- planner 计算 `add / keep / remove-owned / conflict`；
- 两个 ownership 文件均在 `.idea` 内使用同目录临时文件、原子替换、大小/版本/schema 校验和回读等值校验。Project Model 的下一份 intent 与 VCS 的 transition/pre-revoke 必须在对应平台 mutation 前验证；VCS final stable checkpoint 只在 mapping set 与 quiescence 之后发布。任一必需 checkpoint 失败都不得报告收敛或推进 digest，也不能退回只更新内存 PSC；
- Project Model 每次 mutation 前写入下一份 managed + recovery claims；model transaction 后同 JVM 保留 recovery，使进程在任意终止点都留下可 cold-load 验证的 proof。只有进程重启后的 cold service 从 verified 文件和已序列化 target+marker 得出完整一致结论后才清理 recovery；legacy PSC 只迁移，不再参与写入顺序；
- VCS state v2 在 add/remove 前先写入 pending；remove 前必须先从稳定 `CREATED` 权利移出并持久化 tombstone。只有同 JVM 确认 mapping set 成功后才能把 pending add 提升为 `CREATED`；cold-load 的 pending add/remove 都不授权删除，宁可遗留 mapping；
- legacy VCS PSC v1 也只在 `.idea/reqws-vcs-ownership.json` 不存在时作为一次迁移输入；atomic 文件一旦存在，损坏、版本不支持、workspace identity 不匹配或回读失败都必须 fail closed，不得 fallback 到 PSC 重新取得删除权；
- 只删除上次由插件记录并且当前不再需要的条目；
- 现存等价 exclude 或 VCS mapping 只标记为 borrowed，不变成可删除的 ReqWS-owned 条目；
- 用户手工修改使所有权不确定时，不做破坏性移除，状态转为 `DEGRADED` 并显示稳定诊断；
- 不删除其他 module、SDK、library、source root、exclude 或 VCS mapping。

模型删除使用 verified managed/recovery claim + target entity + companion marker entity 三份可重建证据；任一证据不唯一或不能验证 filesystem identity 都不删除。recovery claim 不是仅凭文件字段即可转移的删除权，必须和同 token 的完整 model proof 一起解释；partial proof 必须冲突。状态只保存相对路径、marker nonce 和恢复元数据，不保存 repository URL 或完整 manifest。

## 10. VCS Mapping

目标：每个有效活动仓库对应一个 Git directory mapping。

算法：

1. 从 validated manifest 得到目标 active repo paths，并在 apply 前按实时文件系统重新判定 present/missing/普通 `.git`；
2. mapping change tracker 对每次配置变化记录单调 revision 和对应完整 immutable snapshot；后台规划同时绑定 base revision/snapshot，进入 EDT 串行 write context 后重新读取完整列表，以包含 `rootSettings` 的 equality 与 revision 双重校验；任一变化都退出 EDT，把 observer 保存的非 ReqWS mapping/rootSettings 合入下一轮并等待有界 quiescence 后重规划；
3. 保留所有非 ReqWS-owned mappings；
4. 对已有等价 Git mapping 标记为 `BORROWED`，不制造重复，也不取得删除权；
5. 对缺失 active path 添加 `Git` mapping；
6. 对已不活动且明确 owned 的 mapping，先把 stable claim 转成 `PENDING_REMOVE` tombstone 并 verified atomic 落盘，再尝试移除 mapping；失败宁可遗留无删除权的 mapping，不能遗留可误删的陈旧权利；
7. 新增前先写 `PENDING_ADD`；final equality/revision check、mapping 提交和同 JVM 的最终 ownership transition 按固定顺序执行。Settings writer 由 EDT 串行；pooled auto-detect writer 由 revision/full-snapshot observer 检出并触发 quiescent merge-retry；任何更晚到达的 external event 都使 digest baseline dirty，并强制下一次 automatic reconcile；
8. 通过公开 API刷新 Git repository manager；
9. 验证 Git Tool Window 最终 root 集合。

VCS mapping failure 不得回滚或删除用户 mapping。项目内容已经成功时，状态显示 `DEGRADED`，同一 digest 可通过自动或手动同步重试 VCS apply。

插件自己添加的 mapping 记录为 `CREATED`，只有 verified v2 stable claim 与当前唯一、精确、为 Git 且 `rootSettings` 为空的 mapping 同时成立时，才可在 repository 退出活动集合后删除；用户添加或修改 `rootSettings` 即撤销插件的破坏性删除资格。`PENDING_REMOVE` 是已经撤权的 tombstone，cold load 不得据此继续删除；cold-load 的 `PENDING_ADD` 也不能提升为删除权，最多在重新核对后按非破坏性状态收敛。legacy PSC v1 仅在 atomic 文件不存在时迁移；已存在但不可验证的 v2 文件禁止 fallback。活动 repository 暂时 missing 或暂时不再呈现普通 `.git/` 时，也只在上述完整证据仍成立时保留既有 ownership；mapping 已消失或被定制则放弃陈旧删除权。`BORROWED` mapping 永不由插件删除。重复路径、VCS 类型变化、状态损坏、workspace 内额外 Git coverage 或空目录形式的 project-root mapping 都保留用户配置并转为 ownership/degraded 诊断。项目模型和 VCS 两层都成功且没有 degraded 诊断后才推进 `lastAppliedDigest`。

261 的公开项目级 mapping API 没有 compare-and-set，也没有与后台 auto-detect 共享的公开锁，因此本方案不宣称平台级线性化。mapping change revision/full-snapshot capture、有界 quiescent merge-retry，以及“更晚 external event 令 clean digest baseline 失效并触发 automatic reconcile”在公开 topic 能及时捕获完整列表的范围内避免把竞争静默当作成功；合并时保留 observer 捕获的完整 mapping 对象和 `rootSettings`，在 stable promotion 前连续变化超过上限则 fail closed。仍需保留三个边界：payload-less 回调若延迟到外部列表已被覆盖后才执行就无法重建原列表；final `CREATED` 后发现 drift 时若 durable demotion 本身发生 I/O 故障，刚写入的 stable claim 可能残留；VCS generation 校验不是跨 GoLand 进程 CAS。真实 GUI 仍验证平台事件、ModuleVcsDetector 与 Git Tool Window 的集成结果，但后台 auto-detect 不能再只作为 GUI 抽样残余，必须先有独立 pooled writer 的确定性自动化覆盖。

## 11. VFS 监听与收敛

Desktop 的原子写入可能在 VFS 中表现为临时文件 create、target delete、move、rename、replace 和 content change 的组合。

项目服务在所有 file-based project 上安装这个固定路径 watcher；manifest 不存在时只保持 inactive，不运行模型/VCS 同步，也不显示 Tool Window。监听器必须：

- 在后台把 project root canonicalize 后，只关注 canonical manifest path 及直接父目录；
- 支持 target create、delete、move、rename、replace 和 content change；
- 使用固定 350 ms 防抖；listener callback 只做事件翻译和路径过滤，不读取文件；
- 防抖后重新读取目标文件，不消费事件携带的中间内容；
- 文件暂时不存在时有限重试；
- 不因单个 delete 事件立即移除全部 roots；
- 连续变化 latest-wins，只 apply 最新完整 candidate；
- invalid candidate 保留上次有效模型；
- manifest 恢复后自动清除可恢复错误；
- “Sync Now” 绕过等待但进入同一串行 coordinator，并强制重放当前 candidate；它不能因 clean digest 相同而跳过对实时模型、VCS 与文件系统状态的 reconcile。

W3 必须用与 Desktop `writeJsonAtomically` 等价的脚本模拟连续替换、无效 JSON 恢复、100 次快速变化和 project dispose。

## 12. Manifest 校验

插件 Kotlin parser 至少执行：

- 目标存在时必须是普通文件，不跟随 manifest 自身的异常 symlink；
- size 不超过 1 MiB；
- UTF-8 JSON object；
- `schemaVersion == 1`；
- ID、name、branch 非空并有长度上限；
- `rootPath` 和 `workspaceFilePath` 是不含 NUL 的绝对路径；repository name/`relativePath` 同样拒绝 NUL；
- canonical `rootPath` 等于当前 project root；
- repository ID 和 name 在 macOS identity 规则下唯一；
- `relativePath` 非绝对、非空、无 `..`，v1 要求等于 safe name；
- resolve 后位于 canonical root 内；
- 已存在 repository path 不得通过 symlink 指向 root 外；
- project model 以 canonical filesystem identity 区分 active/retained repository，不依赖 manifest 与磁盘目录的大小写或 Unicode 拼写完全一致；
- missing active repository 不创建目录，只标记 missing；
- URL 除类型和 8,192 字符上限外，还执行与 TypeScript `isSafeRepositoryUrl` 对等的协议、authority、credential 和 remote-helper 拒绝规则；校验过程不解析 remote 内容、不访问网络、不打印 URL。

当前稳定错误码：

```text
MANIFEST_NOT_FOUND
MANIFEST_NOT_REGULAR_FILE
MANIFEST_TOO_LARGE
MANIFEST_INVALID_ENCODING
MANIFEST_INVALID_JSON
MANIFEST_SCHEMA_INVALID
UNSUPPORTED_MANIFEST_VERSION
WORKSPACE_ROOT_MISMATCH
REPOSITORY_DUPLICATE
REPOSITORY_PATH_INVALID
REPOSITORY_PATH_ESCAPE
REPOSITORY_MISSING
PROJECT_MODEL_APPLY_FAILED
VCS_MAPPING_APPLY_FAILED
OWNERSHIP_CONFLICT
SAFE_MODE_BLOCKED
```

错误码进入 Tool Window、测试和 diagnostics；普通通知应去重，避免同一 digest 重复弹窗。

## 13. Tool Window 与 UX

Tool Window 名称：`ReqWS`。

[Tool Window 视觉设计与实现对照](ui/tool-window-visual-design.md)是本节的视觉实施依据；原型只定义信息层级和交互优先级，不授权引入非原生主题、自绘 Web 控件或新的业务动作。

最小内容：

```text
Workspace: feature-login
Branch: feature/login
Status: Synchronized

Repositories
✓ api          active
✓ web          active
! worker       directory missing

Last applied: a1b2c3d4e5f6
[Sync Now] [Open Manifest] [Copy Diagnostics]
```

规则：

- 非 ReqWS project 隐藏或保持不可用；
- factory 以固定 manifest entry 决定初始 `shouldBeAvailable`；所有 file-based project 的轻量 startup controller 监听 service state，并在 EDT 动态切换 availability，因此普通项目不会先闪现 stripe，而 absent → create 可在内容尚未实例化时激活 Tool Window；
- Safe Mode 显示“信任项目后同步”，不自行修改 trust；
- invalid manifest 显示稳定错误码、文件位置和保留上次有效状态的说明；
- `Open Manifest` 只打开固定路径的 manifest；
- `Copy Diagnostics` 包含插件版本、GoLand build、strategy、digest、repository count、错误码和脱敏路径；
- 不提供 add/remove、branch、Git 或 Desktop 控制按钮；
- 首次错误可显示 balloon，同一 digest 的重复错误只更新 Tool Window；
- 插件文案使用 JetBrains resource bundle，不在 Kotlin UI 中散落硬编码字符串。
- 面板按状态徽标、workspace 摘要、紧凑 repository rows、诊断摘要和操作区分层；repository rows 顶部对齐且高度固定，不随 viewport 拉伸；只有 repository 区域滚动。
- 状态徽标保持内容宽度并靠右，不拉伸成整行边框；workspace 摘要使用独立卡片边界，并显示活动仓库数量。
- repository 区域使用独立卡片：标题与 count 分列，列表高度按可见行数计算，行间有主题感知分隔线；1–6 行不显示滚动条，7 行起固定显示六行并只在卡片内部滚动，少量仓库时卡片不吞满整个 Tool Window 高度。
- `Sync Now` 使用全宽主操作视觉，`Open Manifest File` 与 `Copy Diagnostics` 居中作为次级动作；业务行为、enable 规则和可访问名称保持不变。
- 状态色使用稳定的 JetBrains 主题颜色并始终伴随文字；浅色/深色主题、键盘焦点、窄 Tool Window、长 workspace/branch/repository name 和 tooltip 都进入 GUI 验收。
- manifest 文本继续设置 `html.disable=true`，避免 Swing 把不可信值解释为 HTML；颜色、图标和截断不得削弱现有错误码、Safe Mode 提示或动作 enable 规则。

## 14. Trusted Project / Safe Mode

Safe Mode 下允许：

- 检测固定 manifest；
- 读取、校验和展示只读 workspace 信息；
- 生成不含凭据的诊断。

Safe Mode 下禁止：

- 修改 Content Root、module、exclude 或 VCS mapping；
- 启动外部进程；
- 自动执行 repository 内任何代码或构建工具；
- 以插件代码把项目直接标记为 trusted。

261 的 trust listener 带 `@Experimental`，生产实现不订阅它。插件只使用稳定的 `TrustedProjects.isProjectTrusted(project)`：有效 ReqWS project 在 `SAFE_MODE_BLOCKED` 期间以 1 秒间隔低频检查，观察到用户通过 JetBrains 原生流程完成信任后停止检查，并只向同一串行 coordinator 提交一次同步。项目 trusted、inactive 或 dispose 后没有常驻轮询；插件不会自行修改 trust。

## 15. Go module 与 `go.work`

本次规则：

- 不生成、不修改、不删除 `go.work`；
- 不在插件状态中声明 `go.work` ownership；
- 用户已有 `go.work` 继续由 GoLand 和用户管理；
- W2/W5 验证多个独立 `go.mod` 在选定项目模型策略下的基础分析、测试和调试；
- 有 root `go.work` 的 fixture 只用于证明插件不会覆盖，并记录 GoLand 原生识别行为；
- 如果跨仓本地依赖必须依赖 `go.work` 才能解析，本次记录为已知限制，不通过生成文件或私有 API规避；
- 独立仓库的基础 GoLand 使用能力若因此完全不可用，则返回 W2 重新评估项目模型策略。

## 16. 构建与兼容策略

### 16.1 构建系统

- Kotlin；
- Gradle wrapper；
- IntelliJ Platform Gradle Plugin 2.x；
- target product 为 GoLand；
- plugin descriptor 声明实际使用的平台、VCS 和 Go plugin 依赖；
- 不引用 Go-specific API，除非平台通用 API 无法完成本次目标且有 spike 证据；
- 禁止 production 依赖 `@Internal`、`@Experimental`、反射或私有类。

### 16.2 Java 与 build baseline

W0 已锁定以下 production baseline：

| 项目 | 固定值 |
|---|---|
| IntelliJ Platform Gradle Plugin | 2.18.1 |
| Gradle wrapper | 9.3.0 |
| Kotlin | 2.3.20 |
| target product | GoLand 2026.1.3 |
| build JDK / Java release / Kotlin JVM target | 21 |
| plugin `since-build` | 261 |
| plugin `until-build` | 不设置 |
| Plugin Verifier IDEs | GoLand 2026.1.3、GoLand 2026.2 |

本机 GoLand 2026.1.3（build `GO-261.25134.147`）及 bundled JBR 用于开发和 GUI 候选环境；Gradle 通过 `jvmToolchain(21)` 固定编译工具链，并把 Java release 与 Kotlin JVM target 固定为 21。Kotlin 使用 `JvmDefaultMode.NO_COMPATIBILITY`，避免为 IntelliJ 接口生成插件未调用的兼容 override；这些 synthetic stubs 会让 Plugin Verifier 把平台默认方法的 deprecated/experimental 标记误归因到插件。若 262 verifier 暴露二进制不兼容，必须修复或重新评审 build range，不能把 261 本机加载当成 262 兼容证据。

### 16.3 Gradle 任务

至少提供：

```bash
cd integrations/goland
./gradlew test verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin
./gradlew buildPlugin
./gradlew runIde
```

在 IntelliJ Platform Gradle Plugin 2.18.1 中，`verifyPlugin` 执行配置的 Plugin Verifier 矩阵；本项目不另设第二个 verifier 任务。`verifyPluginProjectConfiguration` 和 `verifyPluginStructure` 分别检查项目配置和 ZIP/descriptor 结构。

根项目可增加窄化脚本：

```bash
npm run check:goland
npm run package:goland
```

CI 建议：

- 现有 Desktop job 保持 `npm run check` 和 macOS package smoke；
- 新增独立 `goland-plugin` job，在 macOS + JDK 21 下执行 `test`、两项项目/结构检查、`verifyPlugin` 和 `buildPlugin`；
- 根 `npm run check` 与 `package:macos` 不隐式启动 Gradle；根级 `check:goland`、`package:goland` 是显式入口；
- GUI smoke 由本地 macOS + 真实 GoLand 执行，不伪装成 headless unit test；
- 任何 verifier incompatibility 必须修复或明确缩小支持范围，不使用 `continue-on-error` 掩盖。

### 16.4 本地产物

产物示例：

```text
integrations/goland/build/distributions/reqws-goland-0.1.0.zip
```

ZIP 不提交 Git。验证报告记录 SHA-256，并通过 GoLand Settings → Plugins → Install Plugin from Disk 安装。插件不签名；仓库本次不建立 Marketplace、custom repository、updater 或 Release asset 配置。

## 17. 测试设计

### 17.1 Desktop

- standard/user/Toolbox resolver；
- 同路径的无效 Toolbox launcher 不得污染已验证的 standard/user candidate；
- bundle/launcher canonical validation；
- 固定 `/usr/bin/open -a <canonical-app> <validated-root>` 参数，并覆盖 macOS 第一层系统别名与更深层 symlink 拒绝；
- spawn exception、child error、non-zero exit；
- `SystemAvailability.goland`；
- typed IPC、preload contract、renderer button 和 i18n；
- workspace non-ready/root missing/manifest missing；
- manifest golden fixture；
- VS Code、Cursor、Finder 回归。

### 17.2 插件纯逻辑

- parser、digest、size、duplicate、unknown field；
- TypeScript/Kotlin 共同消费 versioned URL safety corpus；
- root identity、relative path、Unicode、symlink escape；
- planner、ownership、created/borrowed 和 conflict；
- debounce、single-flight、latest-wins 和 dispose；
- diagnostics redaction。

### 17.3 平台模型

- selected Content Root/Workspace Model strategy；
- add/remove/re-add；
- target/companion marker 成对增删、state reload 和独立 JPS exclude 序列化契约；真实 IDE close/reopen 仍由 GUI 覆盖；
- `.idea/reqws-managed-project-model.json` 的 verified atomic write/readback、managed+recovery pre-mutation 持久化、同 JVM recovery 保留、cold-load 压缩与 legacy PSC migration；
- marker/target 缺失或重复、旧 state version、物理 marker namespace 和 filesystem alias 均 fail closed；
- VCS mapping merge/borrow/remove-created；
- `.idea/reqws-vcs-ownership.json` v2 pending add/remove、remove-before tombstone、写入失败无平台 mutation，以及 cold pending 永不授权删除；
- final EDT equality-check/set 与用户 Settings writer 串行；mapping revision/full-snapshot 测试以独立 pooled writer 的两种反序时序覆盖 final-read 前后变化，窗口内用户 mapping/rootSettings 必须被 merge 保留，持续 revision churn 时不得写 stable ownership，更晚 external event 必须使 digest baseline dirty 并强制 automatic reconcile；
- user module/root/mapping preservation；
- Safe Mode gate；
- trust/dispose 在 model transaction 和每个 VCS/ownership commit boundary 翻转时 fail closed；
- malformed manifest no mutation；
- model/VCS failure recovery。

### 17.4 真实 macOS GUI

- active repo-a/repo-b + retained repo-c；
- 从 Desktop 打开 GoLand；
- first trust flow；
- Project、Search、Git Tool Window；
- add repo-c、remove repo-b、re-add repo-b；
- Go completion/navigation/test/debug；
- restart、Desktop not running、plugin disable/enable/reinstall；
- existing user `go.work` 不被修改；
- 50 active + 20 retained；
- rapid manifest rewrite 和 malformed → valid recovery。

完整矩阵见[测试方案](testing/test-plan.md)。

## 18. 可观测性与诊断

插件 logger category：

```text
com.reqws.goland
```

默认日志可以记录：

- workspace ID 的短 hash；
- manifest digest 前 12 位；
- repository count；
- selected strategy；
- stage duration；
- stable error code。

默认不得记录：

- repository URL；
- Git remote、token 或 credential；
- manifest 全文；
- 完整 home path；
- repository 文件内容。

用户主动执行 `Copy Diagnostics` 时可包含 canonical root，但 UI 必须提示路径可能敏感，并优先提供 home 脱敏形式。

Desktop 继续使用现有 `ReqwsError` payload；GoLand 启动失败只增加必要的 launcher/path detail，不创建新的凭据日志。

## 19. 故障与恢复

| 故障 | 行为 |
|---|---|
| manifest 原子替换间隙暂时不存在 | 保持上次有效模型，有限重读；持续缺失则告警。 |
| manifest JSON 损坏 | 不应用部分数据，保持上次有效模型。 |
| unsupported schema | 不修改模型，显示明确版本错误。 |
| root mismatch / path escape | 全量拒绝本次 candidate。 |
| 单个活动目录缺失 | 不创建目录；同步其他有效仓库并标记 degraded。 |
| 项目模型更新失败 | 使用平台事务保证不提交部分 model 变更，记录 error。 |
| VCS 更新失败 | 保留成功项目内容，状态 degraded，同 digest 可重试。 |
| VFS 丢事件 / Mac sleep | 手动 Sync Now；reopen 自动恢复。 |
| plugin ownership state 损坏 | 从 manifest 与当前 model 进入保守恢复，不批量删除 unknown entries。 |
| Desktop 未运行 | 插件继续只读和冷启动，不受影响。 |
| 插件未安装 | Desktop 仍可打开 root，但不承诺受管隔离；指南明确安装要求。 |
| GoLand launcher 失败 | Desktop 返回稳定错误，不静默切换到不可信 PATH executable。 |

## 20. 预计代码改动面

Desktop：

```text
src/shared/types.ts
src/shared/ipc-channels.ts
src/shared/schemas.ts                 # 仅新增 IPC/契约校验时
src/main/services/editor-launcher.ts
src/main/ipc/editor-handlers.ts
src/main/ipc/create-main-services.ts
src/preload/index.ts
src/renderer/App.tsx 或对应页面/组件
src/renderer/locales/zh-CN.json
src/renderer/locales/en-US.json
tests/unit/editor-launcher.test.ts
tests/unit/ipc-handlers.test.ts
tests/unit/preload-contract.test.ts
tests/unit/schemas.test.ts
tests/renderer/*
```

Plugin：

```text
integrations/goland/**
```

文档：

```text
docs/changes/goland-plugin-support/**
docs/changes/README.md
docs/guides/user-guide.md              # 实现验收后
docs/guides/development-guide.md       # 构建流程稳定后
```

不应修改：

- `docs/reference/**`；
- 用户 workspace 内各 repository 的源代码和配置；
- 用户已有 `go.work`；
- 与 GoLand 支持无关的 Electron sandbox、context isolation 或 preload 安全设置；
- Release/Marketplace 配置。

## 21. 明确不建立的架构

本次方案不建立以下组件或接口：

```text
GoLand -> Desktop command channel
reqws:// URL scheme
Unix domain socket / local HTTP daemon
plugin-side Git/workspace mutation service
managed go.work writer
run configuration generator
plugin signing/publishing/updater pipeline
Remote Development frontend/backend split
```

这样可避免探索期提前形成长期兼容承诺。后续若出现新的业务需求，应在新的需求包中重新选择协议和安全边界。

## 22. 外部依据

- [GoLand command-line interface](https://www.jetbrains.com/help/go/working-with-the-ide-features-from-command-line.html)
- [GoLand opening projects](https://www.jetbrains.com/help/go/open-close-and-move-projects.html)
- [GoLand content roots](https://www.jetbrains.com/help/go/content-root.html)
- [GoLand Go workspaces](https://www.jetbrains.com/help/go/go-workspaces.html)
- [IntelliJ Platform Gradle Plugin 2.x](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)
- [GoLand plugin project setup](https://plugins.jetbrains.com/docs/intellij/goland.html)
- [IntelliJ Platform Workspace Model](https://plugins.jetbrains.com/docs/intellij/workspace-model.html)
- [Workspace Model usage examples](https://plugins.jetbrains.com/docs/intellij/workspace-model-usages.html)
- [Plugin compatibility](https://plugins.jetbrains.com/docs/intellij/plugin-compatibility.html)
- [IntelliJ Platform API changes 2026](https://plugins.jetbrains.com/docs/intellij/api-changes-list-2026.html)
- [Trusted Projects](https://plugins.jetbrains.com/docs/intellij/trusted-projects.html)
- [Plugin testing overview](https://plugins.jetbrains.com/docs/intellij/testing-plugins.html)
