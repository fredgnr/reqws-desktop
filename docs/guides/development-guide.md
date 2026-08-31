---
title: ReqWS 开发指南
type: guide
status: active
updated: 2026-08-31
---

# ReqWS 开发指南

本指南说明 ReqWS 的本地开发环境、进程职责、验证基线，以及修改 IPC、状态、界面、国际化、文档和 macOS 交付流程时必须保持的契约。

## 1. 环境准备

ReqWS 是 macOS-only 的 Electron、TypeScript 和 React 项目。开发环境需要：

- macOS；部分集成测试和全部 package/install 流程依赖 Darwin 工具与语义。
- Node.js 24.x；版本范围由 `.nvmrc` 和 `package.json#engines` 共同约束。
- npm 和 Git。
- 构建 GoLand 插件时需要 JDK 21；本机真实 GUI smoke 需要 GoLand 2026.1.3 或验证矩阵指定的 exact build。
- 首次安装依赖和 Electron runtime 时可访问 npm registry 与 GitHub。
- GUI smoke 按改动范围安装 VS Code、Cursor 或 GoLand。

初始化 checkout：

```bash
nvm use
npm ci
npm run check
```

必须使用 `npm ci` 和已提交的 lockfile，不用未审查的依赖漂移代替可复现安装。不要编辑或提交 `node_modules/`、`.vite/`、`out/`、`dist/`、`coverage/`。

## 2. 启动与日常命令

```bash
# Electron Forge + Vite 开发实例
npm start

# 完整质量基线
npm run check

# 单项或分层验证
npm run typecheck
npm run lint
npm test
npm run test:unit
npm run test:integration
npm run test:renderer
npm run test:watch

# 专项一致性检查
npm run i18n:check
npm run docs:check

# 独立的 GoLand 插件检查与 ZIP
npm run check:goland
npm run package:goland
```

`npm run check` 依次执行 TypeScript、ESLint、i18n、文档检查和完整 Vitest。它不隐式启动 Gradle；GoLand 插件使用单独的 `check:goland`。Desktop `package:macos` 也不把 `integrations/goland/` 源码或构建输出打入 Electron app。提交评审前运行与变更范围对应的两套检查；迭代中可以先运行最接近改动层的测试。

`npm start` 的 Main 日志输出到启动终端。应用使用 single-instance lock；调试新实例前先退出已有 ReqWS，否则第二个进程会退出并聚焦原窗口。

## 3. 代码结构与进程边界

```text
src/
  main/
    ipc/                 handler、输入校验和依赖装配
    services/            state、Git、branch、workspace、path、editor、settings
    create-window.ts     BrowserWindow 安全配置
    index.ts             Electron 生命周期与 single-instance
  preload/               窄化的 contextBridge API
  renderer/              React 页面、组件、本地化资源和样式
  shared/                跨进程类型、Zod schema、channel、错误和纯函数
tests/
  unit/                  服务、schema、安全与跨进程契约
  integration/           真实临时 Git remote、workspace 和安装脚本
  renderer/              jsdom + Testing Library 用户交互
scripts/                 i18n/docs 检查和 macOS package/install 脚手架
integrations/goland/     独立 Kotlin/Gradle GoLand 插件、资源与平台测试
docs/                    指南、需求包、规范和冻结历史资料
```

职责规则：

- Renderer 不接触 Node、文件系统、Git 或任意 IPC channel。
- Preload 只把固定、typed 的 `window.reqws` 方法映射到固定 channel。
- Main 是所有权限操作的信任边界；IPC 入参必须用 shared Zod schema 再校验。
- 跨进程类型、错误码和纯校验集中在 `src/shared/`，避免 Main/Renderer 各自定义相似契约。
- Git、路径、状态和工作区原子性放在 service 层，不把业务逻辑堆进 IPC handler。

Renderer 保持 `sandbox`、`contextIsolation`、`webSecurity` 开启且 `nodeIntegration` 关闭。窗口导航和 popup 默认拒绝；不要为便利放宽这些设置。

## 4. 核心数据流

典型调用链：

```text
React event
  -> window.reqws typed preload API
    -> fixed IPC channel
      -> Main handler + Zod validation
        -> service
          -> Git / filesystem / state
        <- structured result or Reqws error payload
      <- IpcResult
    <- Promise
  -> localized UI state / progress / error
```

全局 state 位于 Electron `userData/reqws/state.v1.json`。每个工作区还有 `.reqws/workspace.json`，以及可能位于另一目录的 managed `.code-workspace`。状态、manifest 和 managed 文件均通过同目录临时文件和原子发布或替换写入；修改持久化代码时必须保留损坏备份、no-overwrite 与公开工件不自动删除的语义。

`.reqws/workspace.json` 同时是 GoLand 插件的只读契约。Desktop 仍是唯一 writer；TypeScript Zod 与 Kotlin parser 必须共享 manifest golden fixtures，并共同读取 `integrations/goland/src/test/resources/contracts/repository-url-safety.json`，保持 schema v1、路径、重复 identity、UTF-8/size 和安全 Git URL 的接受/拒绝结果一致。插件不得访问或记录 manifest 中的 repository URL。

Git 子进程必须使用参数数组和 `shell: false`，清理继承的 `GIT_*` 重定向变量，并维持非交互凭据策略。路径写入前必须重新做 realpath、父路径 containment 和 symlink 检查。

## 5. 常见变更清单

### 新增或修改 IPC

同一次变更至少核对：

1. `src/shared/types.ts` 中的请求、响应与 API 类型；
2. `src/shared/schemas.ts` 中的 Main 端输入 schema；
3. `src/shared/ipc-channels.ts` 中的固定 channel；
4. `src/main/ipc/` handler、错误归一化和依赖装配；
5. `src/preload/index.ts` 与 `src/preload/global.d.ts` 的窄化暴露；
6. Renderer 调用方；
7. schema、handler、preload contract 和 UI 测试。

不要暴露原始 `ipcRenderer`、动态 channel、`fs`、`path` 或 state 文件位置。

### 修改状态或持久化

- 先定义旧 state 的读取和标准化策略，再改变写入结构。
- 可选兼容字段不必机械提升 schema 版本；破坏性变化必须明确迁移和回滚。
- 更新不能丢失仓库、工作区或设置中的无关字段。
- 覆盖缺字段、非法字段、round-trip、写入失败原文件保持和损坏备份测试。
- 不在 handler 或 Renderer 中绕过 `AppStateStore` 直接写 JSON。

### 修改 Git、路径或工作区

- 所有仓库保持完整、独立 `.git`；拒绝 gitfile、alternates、`commondir` 或 symlink 逃逸。
- 可控 Git 参数必须验证，必要时使用 `--` option terminator。
- workspace mutation 共用进程内 FIFO 协调器，不能用新的 service 实例绕开串行化。
- 在 integration 测试中使用临时本地 bare remote；不依赖开发者账号或真实外部仓库。
- 覆盖成功、已存在目标、部分失败、回滚失败、重试和磁盘工件保留语义。

### 修改 Renderer

- 使用现有页面、对话框和 toast 模式，保持键盘关闭、焦点和 aria label。
- 行为测试使用 Testing Library，按用户可见结果断言，不耦合内部 state。
- 所有用户可见文本和错误码映射进入 locale catalog，不在 JSX 中增加单语文案。
- 视觉行为变化在 PR 中附截图；macOS 交付行为变化附 package/install 证据。

## 6. 国际化流程

简体中文和英文资源分别位于：

```text
src/renderer/locales/zh-CN.json
src/renderer/locales/en-US.json
```

新增或修改文案时：

1. 更新中文源文案和实际引用；
2. 使用项目级 [`reqws-i18n` Skill](../../.agents/skills/reqws-i18n/SKILL.md)；它会先运行 `npm run i18n:scan`，并以 GPT-5.6 Sol/Pro、reasoning `high` 或更高调用指定翻译 subagent；
3. 翻译 subagent 只返回结构化 JSON，不直接编辑文件；主 Agent 校验 key、中文源文案、`{{placeholder}}`、复数形式和术语后，才写入 `en-US.json`；
4. 复核两套 catalog 的限定 diff，然后运行 `npm run i18n:apply` 更新同步基线；
5. 运行 `npm run i18n:check` 和相关 Renderer 测试。

模型或 reasoning 门禁不可用时必须停止，不要由主 Agent 自行翻译或降级模型。不要只改一套语言后更新基线，也不要把中文复制到英文作为临时占位。已有 key 的源文案变化、复数形式和占位符变化同样触发完整流程。

## 7. 测试策略

| 层级 | 主要范围 | 何时运行 |
|---|---|---|
| Unit | schema、shared 工具、service、IPC/preload、安全与构建配置 | 修改对应模块时首先运行。 |
| Integration | 真实临时 Git、分支语义、workspace 生命周期、回滚和安装脚本 | 修改 Git、文件系统、状态或安装行为时运行。 |
| Renderer | 页面、对话框、i18n、错误与无障碍交互 | 修改 UI、文案或 preload 消费方时运行。 |
| GoLand unit/platform | Kotlin/JUnit + IntelliJ test framework | 修改 manifest、项目模型、VCS、VFS、trust、Tool Window 或 plugin descriptor 时运行。 |
| Plugin compatibility | configuration/structure checks + Plugin Verifier | 每个插件候选对 GoLand 2026.1.3 与 2026.2 运行。 |
| Full check | 类型、lint、i18n、docs 和全部测试 | 每次交付前运行。 |

测试文件使用 `*.test.ts` 或 `*.test.tsx`，`describe` 聚焦行为域，`it` 使用句子式行为描述。全局 setup 在 `tests/setup.ts`；Renderer 测试使用 jsdom，集成测试使用临时目录并自行清理。

不要通过放宽 schema、安全断言、path containment 或跳过失败测试来让检查通过。修复行为后补能证明回归的最小测试。

### GoLand 插件构建与调试

当前工具链固定为 IntelliJ Platform Gradle Plugin 2.18.1、Gradle 9.3.0、Kotlin 2.3.20、GoLand 2026.1.3 target 与 Java/JVM 21；plugin ID 是 `com.reqws.workspace`，`since-build` 为 261，不设置 `until-build`。直接命令：

```bash
cd integrations/goland
./gradlew test verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin
./gradlew buildPlugin
./gradlew runIde
```

`verifyPlugin` 对 GoLand 2026.1.3 和 2026.2 执行 Plugin Verifier。`buildPlugin` 的本地 ZIP 位于 `integrations/goland/build/distributions/`；Gradle cache、sandbox 和 build output 均不可提交。磁盘安装与 Tool Window 操作见[GoLand 插件使用指南](goland-plugin-guide.md)，实现边界和待验证矩阵见[GoLand 插件支持需求包](../changes/goland-plugin-support/README.md)。

生产代码使用公开 261 API：保留 GoLand 既有 workspace-root Content Root；每个插件创建的 target exclude 都配一个虚拟 companion marker exclude。Project Model ownership 的权威文件是 `<workspace-root>/.idea/reqws-managed-project-model.json`：每次 mutation 前 verified atomic 写入 managed + recovery claims，同一 JVM 不清 recovery，legacy PSC 只作一次迁移；进程重启后的 cold service 若 target+marker pair 仍完整就保留 recovery 并完成精确删除，只有两者都不存在时才压缩 recovery，partial proof 必须冲突。Manual 与 trust-transition force intent 都要跨后到的 automatic candidate/read failure 保留到下一份有效 candidate 开始 reconcile；latest Safe Mode blocked read 必须在可能阻塞的注册后 VCS inspection 前 arm trust intent，低频 poll 只提交 automatic wake-up，因此 automatic 抢先时不会 same-digest NoOp，迟到 poll 也不会重复强制重放。project service 的 terminal dispose probe 贯穿模型、VCS 读取、refresh 与 digest gate。Safe Mode 只通过稳定 `TrustedProjects.isProjectTrusted` 查询，并仅在 blocked 期间低频检查 trust transition；禁止使用 `@Internal`、`@Experimental`、反射或私有 API。

一个候选只有在 authoritative Workspace Model、public `ProjectFileIndex` 与 GoLand public Go Modules registry 三层都收敛后，才可推进 clean digest 并显示 `Synced`；present repository 也只有同时进入 live project content 且其顶层普通 `go.mod` 已进入 registry 时才可显示 `Active`。活动 Go module 缺失或 excluded/retained Go module 仍在 registry，与 `ProjectFileIndex` 未收敛一样映射为 `DEGRADED / PROJECT_CONTENT_NOT_CONVERGED`，保持 baseline dirty。

trusted project 的 registry 不匹配时，生产路径只能在 Project Model mutation guard 内发布一次公开 ordinary roots event，然后有界、只读轮询 public registry；禁止直接调用 Go tracker/scheduler/downloader、Go command/process API，也禁止借 internal/private API 或反射触发刷新。ordinary roots event 可能让 GoLand 原生 Go integration 按 IDE 与 Go 环境设置自行执行 `go list`、依赖下载或网络访问，日志和验收必须把它归因于 GoLand，不能承诺此路径无网络。Safe Mode 不得发布 roots event。外部 project-roots drift 在已有有效 snapshot 后触发一次 force reconcile；ReqWS 自己的 guarded event 不反咬监听器，GoLand 随后的异步 roots event 最多再触发一次有界重放，不得形成循环。

Manifest 与 Project Model ownership 的安全文件访问必须绑定稳定目录 descriptor，不能在 attribute check 后回到可被替换的绝对父路径。实现只使用平台 classpath 已提供的公开 JNA 调 POSIX `open/openat/fstat/flock/renameat/unlinkat/fsync/fchmod/close`：从 `/` 逐级以 `O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC` 打开 manifest root、`.reqws` 或 ownership `.idea`，并要求 routed `Path` 遍历前后的 `unix:dev,ino` 与 descriptor 的 native `fstat` identity 相等；Project Model writer 直接在已经打开的 `.idea` descriptor 上取得 non-blocking exclusive advisory lock，再在同一 handle 内完成 state/temp/move/readback。lock 子文件不是互斥权威，rename/unlink/recreate 不得允许第二 writer 绕过 generation fence。普通文件以 `O_NOFOLLOW | O_NONBLOCK` 打开并由 `fstat` 确认 regular file 后，才通过绕过 NIO provider 的 `java.io` fd bridge 取得 `FileChannel`。路径在事务末尾必须重新逐级打开并比较 native descriptor identity；缺少 local file URI、受支持 64-bit macOS/JNA/openat、稳定 directory lock/identity 或已验证 atomic replace 能力时 fail closed。禁止引用 `MultiRoutingFileSystemProvider`、`MultiRoutingFsPath`、反射或其他 internal/private provider API；每个候选都要在 261/262 Verifier 证明该边界兼容。

refresh 进入 `READING` 前必须捕获最近稳定 state；latest 非取消异常发布 `ERROR / REFRESH_FAILED` 并恢复该 snapshot/digest，旧 generation 的失败不能覆盖更新结果。`ProcessCanceledException` 与 coroutine `CancellationException` 在 service、projection、coordinator apply/observer 和 VCS inspection 中都是当前操作的终止信号，必须保留同一实例，不能包装成普通 apply/read/diagnostic failure。latest read cancellation 在 request 仍有效且 service/project 存活时恢复最近稳定状态，不写 `lastError`；单次 applier cancellation 发布非业务 cancellation event、恢复稳定状态并保持 baseline dirty，owner scope 仍 active 时同一 coordinator 继续接受下一份 submission。若首次 read/apply 的稳定基线仍是无 snapshot 的 `INACTIVE`，project service 在原 cancellation completion 与 provisional-listener cleanup 后用 sibling coroutine 延迟提交一次 automatic successor；successor 绑定 predecessor generation、exact rollback state version 和 fixed manifest entry，且 cancellation attempt 上限为 1。测试必须通过一次 `ReqwsStartupActivity.execute` 分别覆盖 read/apply 的 PCE 与 coroutine cancellation，不能直接调用 refresh 伪造生产恢复。新的 generation、owner cancellation 或 service dispose 使 pending retry 失效，retry 再次取消不续订；owner scope cancellation/service dispose 仍关闭 worker，observer 自身的终止信号继续按既有边界原样结束 worker。

VCS 是强制只读边界：production 不得调用 `setDirectoryMappings`、`setDirectoryMapping` 或任何直接/间接 mapping writer，不得主动刷新可改写 Directory Mappings 的内部 detector，也不得直接写 `.idea/vcs.xml`。允许使用公开 API 读取 canonical mappings、保留完整 `rootSettings`、计算 configured/missing/wrong-VCS/retained 诊断；repository present 时一次捕获 lexical + live canonical identity并复用于 containment、普通 `.git` 与 mapping 比对，不能混入 manifest snapshot 的旧 canonical target。`VCS_CONFIGURATION_CHANGED` 只在首个有效 manifest candidate 后 provisional 订阅，注册后立即复检同一 snapshot；latest-selection 边界内只预约单调 epoch，平台 registrar、等待与 handle close 必须在 read-selection / VCS lifecycle 锁外。latest valid generation 接受 listener，更新 valid generation 可在 STARTING/STARTED 阶段等待并接力同一 epoch，首个 registrar 失败时由仍有效的接力 generation 重试；更新 inactive/error generation或任何 latest generation 在 acceptance 前取消/失败时撤销未接受 epoch，迟到 handle 拒绝提交并恰好关闭一次。callback 的 registration epoch 校验与 read generation 创建必须使用统一 `read-selection → VCS-lifecycle` 锁序在线性化边界内完成，旧 callback 即使已通过前置检查，在关闭/重注册后也不能触发 ReqWS 读取；普通项目不得因 VCS event 进入 ReqWS 读取。inspection 必须原样传播 IntelliJ/coroutine cancellation。`Sync Now` 会重放 Project Model/live projection reconcile，并在 VCS 阶段只重新读取当前 mappings。用户按 Tool Window 提示在 Settings → Version Control → Directory Mappings 手动配置；事件丢失时才用 `Sync Now` 重查。Project Model 更新可能使 GoLand 按其原生用户设置自行运行 VCS auto-detection；ordinary roots event 也可能使原生 Go integration 运行 `go list` 或下载依赖。插件既不直接调用也不禁用这些平台机制，验证时必须区分 GoLand 原生变化与 ReqWS API 调用。

当前实现不建立 VCS ownership 或删除权。未发布开发候选可能留下的 `.idea/reqws-vcs-ownership.json` 与匹配 lock 是 inert 文件：production 不读取、不迁移、不压缩，也不自动清理。测试必须证明源码/bytecode 无 ReqWS mapping writer、纯 VCS inspection/配置事件/只读重查不改变 mappings，以及配置事件能自动刷新诊断；manifest 驱动的 Project Model 变化若触发 GoLand 原生 auto-detection，必须单独标记为平台行为，不能伪装成 ReqWS 写入或声称插件能阻止。

真实 GUI、Go completion/navigation/test/debug、add/remove/re-add、restart、50+20 规模和 ZIP SHA-256 不能由平台单测或 `runIde` 代替。PACKAGE run configuration 若仍显示 `Cannot find package`，即使选择 `Continue Anyway` 后底层 Go 命令碰巧成功，也不得记为通过；必须先证明配置校验无警告且正常 test/run/debug。只有同一 exact commit 完成 261/262 Verifier 与真实 GoLand GUI 后，才在需求包中新增 dated verification report。

## 8. 文档工作流

文档搜索从[文档总索引](../README.md)开始，再进入相关分类和需求包索引。`docs/reference/` 是冻结历史输入，不是当前需求。

需求开发或行为修复前，按[项目文档规范](../standards/documentation-standard.md)分别判断 requirements、technical design、test material、delivery 和 evergreen guides 是 `create`、`update` 还是 `none`。新增、移动、重命名、删除文档或改变状态、摘要时，同步最近一级及必要的父级 `README.md`，完成后运行：

```bash
npm run docs:check
```

详细 Agent 流程见项目级 [reqws-documentation Skill](../../.agents/skills/reqws-documentation/SKILL.md)。

## 9. 打包、安装与发布

```bash
# clean install + check + 当前架构 package，仅生成 .app
npm run package:macos

# 完整构建、验证并安装到本机
npm run install:macos

# 复用已经安装的依赖和刚通过的检查
npm run package:macos -- --skip-ci --skip-check
```

产物位于 `out/ReqWS-darwin-<arch>/ReqWS.app`。脚手架校验 bundle ID、版本、Mach-O 架构和 codesign 结构。不要提交 `out/` 或 `.vite/`。

安装脚本会在目标目录进行 staging、旧版备份、整体替换和尽力回滚；不要用 `sudo` 包裹整个 npm 命令，也不要弱化遗留 lock/staging/backup 的 fail-closed 检查。

GitHub Actions 的 branch/PR 检查和 tag Release 契约见 [CI 与 Release 需求包](../changes/github-actions-ci-release/README.md)。发布只接受默认分支上与 `package.json`、`package-lock.json` 一致的 `vMAJOR.MINOR.PATCH`。当前 Release ZIP 仍为 ad-hoc 签名且未公证；面向外部分发前需要独立实现 Developer ID、Hardened Runtime 和 notarization。

CI 另有只读权限的 `goland-plugin` job，在 macOS + JDK 21 上验证 Gradle wrapper、测试、项目/结构、Plugin Verifier 和 ZIP 构建。它不改变 tag Release 工作流；ReqWS Desktop Release 仍只有双架构 `.app` ZIP 与 `SHA256SUMS`，不发布插件 ZIP。

## 10. 调试与安全操作

- 开发实例和安装版默认共享 ReqWS 的真实 userData。涉及迁移、损坏恢复或破坏性实验时，先退出应用并备份 state；优先用注入依赖或临时目录的测试，不拿真实工作区试错。
- 错误跨 contextBridge 后是结构化 payload，不依赖自定义 `Error` 原型。Renderer 使用统一错误映射和复制日志入口。
- 需要本地化的工作区缺失工件和回滚动作必须使用 shared 稳定枚举；Main 的自由文本只作诊断 fallback，Renderer 不用它推断用户可见语义。
- Settings 或 state 加载失败不应让 Renderer 先以错误语言闪烁；启动初始化必须在首屏渲染前完成可用 locale 的解析。
- 不在日志、fixture、截图或文档中写入 Token、带凭据 URL、私钥、本机敏感目录或真实用户数据。
- 不自动删除用户工作区。清理测试 fixture 和生成物时限定到已验证的临时路径。

## 11. 完成检查

交付变更前确认：

- 代码位于正确进程和模块，跨层契约同步更新；
- 新行为有相应层级的回归测试，用户文案完成双语同步；
- 需求、设计、测试、交付和常青指南的影响已经判断并更新必要索引；
- `npm run check` 通过；涉及 macOS package/install 时额外执行相应 smoke；
- Renderer 变化准备截图，交付变化记录签名、公证、迁移、回滚和已知限制；
- `git diff --check` 通过，生成目录和凭据没有进入变更。

## 12. 设计依据与追溯

- [全局设置需求包](../changes/global-settings/README.md)记录 settings、持久化兼容、typed IPC、启动语言解析和验证证据。
- [CI 与 Release 需求包](../changes/github-actions-ci-release/README.md)记录 GitHub Actions 触发器、权限、双架构资产和发布限制。
- [GoLand 插件支持需求包](../changes/goland-plugin-support/README.md)记录跨语言 manifest、Project Model ownership、只读 VCS/手动 Directory Mappings 契约、构建矩阵和待完成 GUI 证据。
- [MVP 实现快照](../changes/mvp/README.md)保存初始范围、交付与验证历史；其状态为 archived，只用于理解演进背景。
- [历史参考](../reference/README.md)是冻结输入，不作为当前开发决策。没有 active 设计覆盖的现状必须回到代码与测试核实。
