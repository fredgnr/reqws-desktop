---
title: GoLand 插件支持技术方案
type: technical-design
status: draft
updated: 2026-08-14
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
5. 项目模型 API 路径必须通过真实 GoLand spike 决策，优先单 module 多 Content Root，不预先锁死未经验证的实现。
6. 插件源码与 Desktop 同仓，放在 `integrations/goland/`，使用独立 Gradle 构建。
7. 本次产物是本地可安装 ZIP；不实现签名、Marketplace、自动更新或发布服务。
8. 本次不引入 Desktop 与插件的双向通信，不生成或修改 `go.work`。

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

建议标识：

```text
Kotlin package: com.reqws.goland
Plugin ID:      com.reqws.goland
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
| GoLand 受管项目内容 | 否 | owner |
| GoLand 受管 VCS mapping | 否 | owner / 可采用现存等价项 |
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
- `url`、`defaultBranch` 和 `featureBranch` 供 Desktop 使用，插件只展示或校验长度，不据此访问网络或执行命令；
- `workspaceFilePath` 供 VS Code/Cursor 使用，插件忽略；
- `updatedAt` 只用于展示，不能作为唯一同步版本；
- 未知附加字段可忽略；不支持的 major version 必须拒绝。

### 5.3 内容摘要

插件读取 manifest 原始 UTF-8 bytes 后计算：

```text
ManifestDigest = SHA-256(fileBytes)
```

摘要用于：

- 相同内容幂等 no-op；
- VFS 事件乱序或合并时重新识别最终内容；
- 项目冷启动恢复；
- 错误去重和诊断关联。

不在 schema v1 中增加递增 revision，避免旧 Desktop、回滚内容和跨文件事务引入额外兼容路径。

### 5.4 Golden fixtures

建立仓库内共享 fixture，例如：

```text
integrations/goland/src/test/resources/manifests/
├── valid-minimal-v1.json
├── valid-full-v1.json
├── valid-unknown-fields-v1.json
├── invalid-duplicate-name.json
├── invalid-relative-path.json
└── unsupported-v2.json
```

TypeScript tests 与 Kotlin tests 必须读取等价 fixture，并验证：

- Desktop 当前 writer 生成的 v1 可被插件读取；
- 插件接受的 valid v1 同时被 Zod schema 接受；
- duplicate、unsafe path 和 unsupported version 的判定一致；
- URL 永远不触发网络或命令行为。

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

### 6.2 GoLand 安装探测 spike

安装布局存在不确定性，因此先建立 resolver spike，再固化生产规则。候选来源：

1. `/Applications/GoLand.app`；
2. `~/Applications/GoLand.app`；
3. JetBrains Toolbox 生成的用户级 app、symlink 或 command-line launcher；
4. 必要时通过固定 `/usr/bin/mdfind` 参数查询 GoLand bundle identifier，再校验结果。

安全规则：

- 不递归扫描整个 home；
- 不把 PATH 中任意 `goland` 当作唯一可信来源；
- 候选必须 canonicalize 到存在的 `.app` bundle 或经过验证的官方 launcher；
- 校验 bundle 结构、可执行文件和 bundle identifier；
- 多个版本时使用确定性排序，默认稳定版优先，并在诊断中记录选择结果；
- 任何外部命令使用固定路径、参数数组和 `shell: false`。

W0/W4 必须真实验证标准安装和 Toolbox 安装。若 Toolbox 布局无法形成稳定只读发现规则，则支持标准 app 和用户 Applications symlink，并把 Toolbox 的必要手工配置写入指南；不得用不受控全盘扫描换取“自动发现”。

### 6.3 启动策略

启动目标始终是 `manifest.rootPath`，不是 `.code-workspace`。

候选顺序由 spike 确认：

1. app bundle 内官方 launcher，若对当前 GoLand build 稳定；
2. `/usr/bin/open -a GoLand <rootPath>`；
3. `/usr/bin/open -a <absolute-app-path> <rootPath>` 或等价的绝对 bundle 打开方式。

所有路径：

- root 来自 Main 重新读取并校验的 workspace manifest；
- root 必须是绝对 canonical path，且实际存在；
- command 和 args 分离；
- `shell: false`、`stdio: ignore`；
- 处理同步 spawn exception、child `error` 和非零 exit；
- 不把 manifest 字段拼接到 shell command；
- 不静默把已选择 launcher 的失败伪装成成功。

GoLand 在已有项目窗口时如何打开新项目由用户的 IDE 设置决定。Desktop 不使用私有或未记录参数强行覆盖“New window / Current window / Ask”。

### 6.4 Renderer 与 i18n

工作区卡片和详情增加 GoLand 操作：

- unavailable：禁用，并显示本地化原因；
- workspace 非 `ready`：沿用现有禁用逻辑；
- launching：阻止重复点击；
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
  ├─ adopt equivalent mapping
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

1. project opened 后运行轻量 detector；
2. 固定 manifest 不存在：插件项目服务保持 inactive，Tool Window 隐藏或显示不可用；
3. manifest 存在：后台读取、digest 和校验；
4. Safe Mode：保存只读 snapshot，但不执行 model/VCS apply；
5. trusted：提交首次同步；
6. project-level VFS listener 只关注 manifest exact path 及直接父目录；
7. project close/dispose：取消 debounce、IO 和 pending sync；
8. reopen：不依赖旧进程内状态，从 manifest 和插件 ownership state 冷恢复。

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
- model 和 VCS 均成功后才更新 `lastAppliedDigest`；
- 相同 digest 在 `SYNCHRONIZED` 状态为 no-op；
- 文件恢复后自动重新进入 `READING`。

### 7.4 线程与事务

- 文件读取、JSON、digest、realpath、directory check：后台线程或 coroutine；
- VFS listener callback 不做阻塞 IO；
- Workspace Model snapshot 按平台 API 要求读取；
- 项目模型 apply 在 write action / Workspace Model update transaction 中执行；
- VCS mapping 合并在平台允许的项目级更新路径执行；
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
    if (latest.digest == state.lastAppliedDigest) continue
    val plan = planner.plan(currentModel(), state.ownership, latest)
    applyProjectModel(plan)
    applyVcsMappings(plan)
    state.markApplied(latest)
  }
}
```

生产实现必须使用当前 JetBrains 推荐的 coroutine/lifecycle API；伪代码不授权阻塞 EDT 或使用全局 unmanaged executor。

## 8. 项目模型策略与实验门禁

Workspace Model 自 IntelliJ Platform 2024.2 起可供第三方插件使用，并应优先于旧 Project Model API。本次仍需用真实 GoLand 验证 Go plugin 对多 Content Root、多 `go.mod` 和动态变更的行为。

### 8.1 策略 A：单 module、多活动 Content Root

首选方案：

- 使用打开 root 时已有的 workspace module；
- 将每个活动仓库目录作为该 module 的 Content Root；
- workspace root 本身不作为代码 Content Root；
- `.reqws`、普通 notes 和保留仓库自然不进入项目内容；
- 其他非 ReqWS module 不删除。

必须验证：

- 多个独立 `go.mod` 的 completion、navigation、test、run/debug；
- Project View 只呈现活动仓库；
- add/remove/re-add 后索引与 Git 正确；
- restart serialization 稳定；
- 不依赖 internal API。

### 8.2 策略 B：workspace root + 受管 excludes

仅当策略 A 有可复现失败证据时尝试：

- workspace root 保持 Content Root；
- 对 `.reqws` 和识别出的非活动独立 Git repository 增加插件拥有的 exclude；
- 重新添加时移除对应 owned exclude；
- 不排除普通未知目录。

该策略的缺点是 root 范围更大、用户显示 excluded files 时仍可看到保留仓库，并增加 exclude ownership 复杂度。

### 8.3 策略 C：每活动仓库一个 module

只有 A、B 均无法满足 Go 分析、隔离和公开 API 要求时评估。它会增加 module naming、SDK、serialization、remove 和用户配置保护成本，不作为默认路径。

### 8.4 选择门禁

W2 必须按 A → B → C 顺序使用同一 fixture。选择第一个同时通过以下条件的策略：

1. 活动和保留仓库的默认可见性、索引和搜索范围正确；
2. Git Root 与 manifest 一致；
3. Go completion、navigation、test 和 debug 可用；
4. add/remove/re-add 和 restart 幂等；
5. Plugin Verifier 无禁止 API；
6. 用户无关 module/root/mapping 不被删除；
7. ownership 可持久化并在重启后安全恢复；
8. 没有持续 indexing loop 或明显 EDT freeze。

选择结果写入 dated verification report。未选 spike 代码必须删除；只有报告证明单一 production path 无法覆盖支持矩阵时，才保留最小 runtime fallback。

## 9. 受管条目所有权

插件不得把当前全部项目 roots 或 VCS mappings 替换为 manifest 集合。

建议 persistent state：

```xml
<component name="ReqwsProjectState">
  <option name="stateVersion" value="1" />
  <option name="strategy" value="content-roots" />
  <option name="lastAppliedDigest" value="..." />
  <option name="managedModuleName" value="..." />
  <option name="managedContentRoots">
    ... workspace-relative paths ...
  </option>
  <option name="managedVcsRoots">
    ... workspace-relative paths ...
  </option>
</component>
```

规则：

- 路径以 workspace-relative 形式保存，读取时重新 containment 校验；
- 不保存 repository URL、token、remote 或 Git credential；
- planner 计算 `add / keep / remove-owned / conflict`；
- 只删除上次由插件记录并且当前不再需要的条目；
- 与现存等价 mapping/root 可采用，但必须记录 adopted ownership 的边界；
- 用户手工修改使所有权不确定时，不做破坏性移除，状态转为 `DEGRADED` 并提示重新同步或重置受管配置；
- 不删除其他 module、SDK、library、source root、exclude 或 VCS mapping。

如果公开 Workspace Model `EntitySource` 能稳定表达插件所有权，可减少 XML 列表，但必须经 W2 验证；不得依赖 internal entity source。

## 10. VCS Mapping

目标：每个有效活动仓库对应一个 Git directory mapping。

算法：

1. 从 validated manifest 得到 canonical active repo paths；
2. 读取当前 `ProjectLevelVcsManager` mappings；
3. 保留所有非 ReqWS-owned mappings；
4. 对已有等价 Git mapping 执行 adopt，不制造重复；
5. 对缺失 active path 添加 `Git` mapping；
6. 对已不活动且明确 owned 的 mapping 移除；
7. 一次提交合并后的 mapping 集合；
8. 通过公开 API刷新 Git repository manager；
9. 验证 Git Tool Window 最终 root 集合。

VCS mapping failure 不得回滚或删除用户 mapping。项目内容已经成功时，状态显示 `DEGRADED`，同一 digest 可通过自动或手动同步重试 VCS apply。

## 11. VFS 监听与收敛

Desktop 的原子写入可能在 VFS 中表现为临时文件 create、target delete、move、rename、replace 和 content change 的组合。

监听器必须：

- 只关注 canonical manifest path 及直接父目录；
- 支持 target create、delete、move、rename、replace 和 content change；
- 对事件做 250–500 ms 防抖，最终值由 W3 实测确定；
- 防抖后重新读取目标文件，不消费事件携带的中间内容；
- 文件暂时不存在时有限重试；
- 不因单个 delete 事件立即移除全部 roots；
- 连续变化 latest-wins，只 apply 最新完整 candidate；
- invalid candidate 保留上次有效模型；
- manifest 恢复后自动清除可恢复错误；
- “Sync Now” 绕过等待但进入同一串行 coordinator。

W3 必须用与 Desktop `writeJsonAtomically` 等价的脚本模拟连续替换、无效 JSON 恢复、100 次快速变化和 project dispose。

## 12. Manifest 校验

插件 Kotlin parser 至少执行：

- 目标存在时必须是普通文件，不跟随 manifest 自身的异常 symlink；
- size 不超过 1 MiB；
- UTF-8 JSON object；
- `schemaVersion == 1`；
- ID、name、branch 非空并有长度上限；
- `rootPath` 和 `workspaceFilePath` 是绝对路径；
- canonical `rootPath` 等于当前 project root；
- repository ID 和 name 在 macOS identity 规则下唯一；
- `relativePath` 非绝对、非空、无 `..`，v1 要求等于 safe name；
- resolve 后位于 canonical root 内；
- 已存在 repository path 不得通过 symlink 指向 root 外；
- missing active repository 不创建目录，只标记 missing；
- URL 只限制类型和长度，不打印、不访问。

稳定错误码建议：

```text
MANIFEST_NOT_FOUND
MANIFEST_TOO_LARGE
MANIFEST_INVALID_ENCODING
MANIFEST_INVALID_JSON
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
- Safe Mode 显示“信任项目后同步”，不自行修改 trust；
- invalid manifest 显示稳定错误码、文件位置和保留上次有效状态的说明；
- `Open Manifest` 只打开固定路径的 manifest；
- `Copy Diagnostics` 包含插件版本、GoLand build、strategy、digest、repository count、错误码和脱敏路径；
- 不提供 add/remove、branch、Git 或 Desktop 控制按钮；
- 首次错误可显示 balloon，同一 digest 的重复错误只更新 Tool Window；
- 插件文案使用 JetBrains resource bundle，不在 Kotlin UI 中散落硬编码字符串。

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

插件订阅 trust state 变化；用户通过 JetBrains 原生流程信任项目后，仅触发一次串行同步。

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

JetBrains 平台规则要求：

- 2024.2+ 使用 IntelliJ Platform Gradle Plugin 2.x；
- target 2024.2–2026.1 使用 Java 21；
- target 2026.2+ 使用 Java 25。

W0 比较两个候选：

- **首选**：以 GoLand 2026.1 API / Java 21 编译，Plugin Verifier 对 2026.1 和 2026.2 验证，真实 GUI 使用本机当前稳定 GoLand；
- **fallback**：若所需公开 API 只有 2026.2 可用，则以 262 / Java 25 构建，将 `since-build` 收窄到 262，并记录原因。

`until-build` 初期不设置；通过 Plugin Verifier 和明确的测试矩阵控制兼容性。Gradle、Kotlin、JVM target、target IDE 和 plugin dependencies 在 W0 通过后锁定。

### 16.3 Gradle 任务

至少提供：

```bash
cd integrations/goland
./gradlew test
./gradlew verifyPlugin
./gradlew runPluginVerifier
./gradlew buildPlugin
./gradlew runIde
```

根项目可增加窄化脚本：

```bash
npm run check:goland
npm run package:goland
```

CI 建议：

- 现有 Desktop job 保持 `npm run check` 和 macOS package smoke；
- 新增独立 `goland-plugin` job，执行 tests、verifyPlugin、Plugin Verifier 和 buildPlugin；
- GUI smoke 由本地 macOS + 真实 GoLand 执行，不伪装成 headless unit test；
- 任何 verifier incompatibility 必须修复或明确缩小支持范围，不使用 `continue-on-error` 掩盖。

### 16.4 本地产物

产物示例：

```text
integrations/goland/build/distributions/reqws-goland-0.1.0.zip
```

ZIP 不提交 Git。验证报告记录 SHA-256，并通过 GoLand Settings → Plugins → Install Plugin from Disk 安装。仓库本次不建立签名、Marketplace、custom repository 或 updater 配置。

## 17. 测试设计

### 17.1 Desktop

- standard/user/Toolbox resolver；
- bundle/launcher canonical validation；
- `/usr/bin/open` fallback 参数；
- spawn exception、child error、non-zero exit；
- `SystemAvailability.goland`；
- typed IPC、preload contract、renderer button 和 i18n；
- workspace non-ready/root missing/manifest missing；
- manifest golden fixture；
- VS Code、Cursor、Finder 回归。

### 17.2 插件纯逻辑

- parser、digest、size、duplicate、unknown field；
- root identity、relative path、Unicode、symlink escape；
- planner、ownership、adoption 和 conflict；
- debounce、single-flight、latest-wins 和 dispose；
- diagnostics redaction。

### 17.3 平台模型

- selected Content Root/Workspace Model strategy；
- add/remove/re-add；
- restart serialization；
- VCS mapping merge/adopt/remove-owned；
- user module/root/mapping preservation；
- Safe Mode gate；
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
- [IntelliJ Platform Workspace Model](https://plugins.jetbrains.com/docs/intellij/workspace-model.html)
- [Workspace Model usage examples](https://plugins.jetbrains.com/docs/intellij/workspace-model-usages.html)
- [Plugin compatibility](https://plugins.jetbrains.com/docs/intellij/plugin-compatibility.html)
- [IntelliJ Platform API changes 2026](https://plugins.jetbrains.com/docs/intellij/api-changes-list-2026.html)
- [Trusted Projects](https://plugins.jetbrains.com/docs/intellij/trusted-projects.html)
- [Plugin testing overview](https://plugins.jetbrains.com/docs/intellij/testing-plugins.html)
