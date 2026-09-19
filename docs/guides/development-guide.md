---
title: ReqWS 开发指南
type: guide
status: active
updated: 2026-09-19
---

# ReqWS 开发指南

本指南说明 ReqWS 的本地开发环境、进程职责、验证基线，以及修改 IPC、状态、界面、国际化、文档和 macOS 交付流程时必须保持的契约。

按任务阅读相关章节即可，不要求每次编辑前通读；任务提示词、技能选择与执行授权见[Agent 协作指南](agent-workflow.md)。

## 1. 环境准备

ReqWS 是 macOS-only 的 Electron、TypeScript 和 React 项目。开发环境需要：

- macOS；部分集成测试和全部 package/install 流程依赖 Darwin 工具与语义。
- Node.js 24.x；版本范围由 `.nvmrc` 和 `package.json#engines` 共同约束。
- npm 和 Git。
- OpenSSL 3；原生 P12 导入回归使用 Homebrew 的 `openssl@3` 或 PATH 中的 OpenSSL 3，也可用 `REQWS_OPENSSL` 指定已有可执行文件。测试生成一次性身份和临时钥匙串，不读取发布 Secrets、不修改证书信任，结束后恢复搜索列表并清理。
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

### VS Code 工作区

用 VS Code 打开仓库根目录后，安装工作区推荐的 ESLint 与 Vitest 插件：

```bash
code .
code --install-extension dbaeumer.vscode-eslint
code --install-extension vitest.explorer
```

仓库的 `.vscode/` 配置会使用 `node_modules/typescript/lib` 中的项目 TypeScript、启用显式的 ESLint 保存修复、隐藏生成目录，并提供 `ReqWS: start`、`ReqWS: check`、`ReqWS: test (watch)` 和 `ReqWS: check GoLand plugin` 任务。项目未配置统一 formatter，因此工作区不启用 format-on-save；以 ESLint 和现有代码风格为准。

若 VS Code 已安装但终端找不到 `code`，在命令面板运行 `Shell Command: Install 'code' command in PATH`，然后重开终端。修改 GoLand 插件生产代码和运行 IntelliJ Platform 调试仍以 GoLand 2026.1.3 + JDK 21 为准；VS Code 只承担通用文本编辑和根项目任务入口。

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

`npm run check` 依次执行 TypeScript、ESLint、i18n、文档检查和完整 Vitest。它不隐式启动 Gradle；GoLand 插件使用单独的 `check:goland`。Desktop `package:macos` 也不把 `integrations/goland/` 源码或构建输出打入 Electron app。Desktop 代码候选交付前在环境支持时运行 `npm run check`，插件的最终代码/构建候选运行 `npm run check:goland`，共享 manifest 契约变化需要两侧检查。分阶段开发的中间子任务只做生产/测试编译和直接受影响方法/类的回归，不把每个检查点当作完整交付候选；实际受影响的安全和并发分支仍须当步验证。纯文档改动运行 `npm run docs:check`，不额外要求应用全量测试或 Gradle。既有 CI 与发布工作流不变；插件 GUI 验收范围按[IDE 插件开发与测试规范](../standards/ide-plugin-development-testing.md)收敛，不再沿用旧 Go 工具链门禁。

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

`src/renderer/locales/zh-CN.json` 是中文源 catalog，`en-US.json` 是经独立复核的英文翻译。新增或修改用户文案、key、占位符、复数或本地化映射，以及翻译检查报告陈旧时，使用项目级 [reqws-i18n Skill](../../.agents/skills/reqws-i18n/SKILL.md)。普通 Markdown 文字、无文案变化的内部重构不触发翻译。

流程为中文源文案与引用更新 → `npm run i18n:scan` → 使用与主 Agent 相同模型的只读翻译 subagent → 主 Agent 校验并写回 → `npm run i18n:apply` → `npm run i18n:check` 和受影响测试。模型/reasoning 门禁、JSON、术语和复数/占位符复核以[翻译契约](../../.agents/skills/reqws-i18n/references/translation-contract.md)为准，不在多个文档复制模型清单。

模型、reasoning 或输出验证不可用时，停止英文与基线写回；不能由主 Agent 自行翻译、降级或把中文复制到英文占位。模型可通过运行时继承或显式使用主 Agent 当前配置来保持一致，reasoning 仍至少为 `high`。已有 key 的源文案变化同样需要复核；无翻译 delta 时不运行 apply 来重新确认基线。独立的非翻译工作可以继续完成。

## 7. 测试策略

| 层级 | 主要范围 | 何时运行 |
|---|---|---|
| Unit | schema、shared 工具、service、IPC/preload、安全与构建配置 | 修改对应模块时首先运行。 |
| Integration | 真实临时 Git、分支语义、workspace 生命周期、回滚和安装脚本 | 修改 Git、文件系统、状态或安装行为时运行。 |
| Renderer | 页面、对话框、i18n、错误与无障碍交互 | 修改 UI、文案或 preload 消费方时运行。 |
| GoLand unit/platform | Kotlin/JUnit + IntelliJ test framework | 修改 manifest、项目模型、VCS、VFS、trust、Tool Window 或 plugin descriptor 时运行。 |
| Plugin compatibility | configuration/structure checks + Plugin Verifier | 最终插件候选运行原 GoLand 2026.1.3/2026.2 矩阵；中间子任务按影响验证装配，不重复完整矩阵。 |
| Full check | 类型、lint、i18n、docs 和全部测试 | Desktop 代码候选交付前在环境支持时运行；不因纯文档改动重复全量测试。 |
| Documentation / skills | 索引、链接、metadata 和相关 skill 场景 | 文档运行 docs:check；skill 另查参考链接和行为场景，不把静态检查当作模型 eval。 |

测试文件使用 `*.test.ts` 或 `*.test.tsx`，`describe` 聚焦行为域，`it` 使用句子式行为描述。全局 setup 在 `tests/setup.ts`；Renderer 测试使用 jsdom，集成测试使用临时目录并自行清理。

不要通过放宽 schema、安全断言、path containment 或跳过失败测试来让检查通过。修复行为后补能证明回归的最小测试。

### GoLand 插件构建与调试

Gradle 按 Wrapper 的明确版本和官方 HTTPS `distributionUrl` 管理，当前仍为 9.3.0；不设置 `distributionSha256Sum`，不增加替代 checksum 文件或预期值。保留 URL 校验、超时、缓存及既有 Wrapper JAR 验证；这不等于验证下载 ZIP 的预期字节。升级只维护明确版本，不改为动态版本或个人二进制。

当前工具链固定为 IntelliJ Platform Gradle Plugin 2.18.1、Gradle 9.3.0、Kotlin 2.3.20、GoLand 2026.1.3 target 与 Java/JVM 21；plugin ID 是 `com.reqws.workspace`，`since-build` 为 261，不设置 `until-build`。直接命令：

```bash
cd integrations/goland
./gradlew test verifyForbiddenProductionSymbols verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin
./gradlew verifyForbiddenProductionSymbols buildPlugin
./gradlew runIde
```

`verifyPlugin` 对 GoLand 2026.1.3 和 2026.2 执行 Plugin Verifier。`buildPlugin` 的本地 ZIP 位于 `integrations/goland/build/distributions/`；Gradle cache、sandbox 和 build output 均不可提交。磁盘安装与 Tool Window 操作见[GoLand 插件使用指南](goland-plugin-guide.md)，需要真实安装/重启时仍遵守原授权边界。

### 插件开发与验收边界

[IDE 插件开发与测试规范](../standards/ide-plugin-development-testing.md)是开发和验收的统一入口；[语言解耦总方案](../changes/ide-plugin-language-decoupling/technical-design.md)定义共同契约，逐文件实施和最小回归直接见[独立任务文档](../changes/ide-plugin-language-decoupling/tasks/README.md)。S1 已删除 `ReqwsGoModulesSynchronizer`、Go 成功门禁及直接错误链；阶段回归见 [S1 实施记录](../changes/ide-plugin-language-decoupling/tasks/s1-core-sync-decoupling.md#8-本轮实施记录2026-09-18)。S2 已清理补偿调度与显式 Go 依赖，当前验证见 [S2 实施记录](../changes/ide-plugin-language-decoupling/tasks/s2-scheduling-dependency-cleanup.md#8-本轮实施记录2026-09-18)；V 的完整自动化、兼容、打包、必要真实 GUI 及同候选关闭重开已通过，详见 [V 验收记录](../changes/ide-plugin-language-decoupling/testing/acceptance-2026-09-19.md)。

Desktop 保持 manifest 和 Git/workspace 生命周期的唯一 writer。插件只读消费仓库集合，进行必要的受管项目范围适配和 VCS 诊断。同步主路径不依据 `go.mod` 等语言文件判断成员或成功，不查询 Go registry，也不等待 SDK、依赖、运行配置或语言分析就绪。旧指南中的 Go registry 三层成功门禁和 Go test/run/debug 验收要求已由新规范替代。

仍保留 workspace-root Content Root 与 owned-excludes 策略、公开 ProjectFileIndex 的目录归属验证，以及其真实失败/恢复。`Synced` 只描述插件负责的仓库视图和项目范围，`Active` 不承诺 Go module 可用。只读业务输入不等于不修改任何 IDE 模型；正常公开模型更新和必要通知不能随 Go 补偿一起误删。

通用保护继续按[既有技术方案](../changes/goland-plugin-support/technical-design.md)维护：target/companion marker 与 ownership 证明、稳定 directory descriptor、verified atomic state、generation/恢复、trust/dispose 最终 gate、PCE/coroutine cancellation 原实例传播、latest-wins、手动/信任 intent、startup readiness 和外部 model drift 的有界处理。现有安全及并发回归不因语言解耦失效；不在本次变更中迁移 schema、简化路径安全或改写整个协调器。禁止 `@Internal`、`@Experimental`、反射和私有 API。

VCS 始终只读：生产代码不得调用 mapping writer、主动调用可改写配置的内部 detector 或直接写 `.idea/vcs.xml`。继续保留完整 `rootSettings`、live path identity、configured/missing/wrong-VCS/retained 分类、配置事件订阅与重查、取消和生命周期保护。旧 `.idea/reqws-vcs-ownership.json` 及 lock 为 inert 文件，不读取、不迁移、不自动清理。IDE 原生行为与 ReqWS 调用区分归因，不能承诺整个 IDE 无网络或不产生进程。

### 插件测试选择

使用普通文本 Git fixture，覆盖 manifest、仓库增删重加、自动/手动刷新、项目范围、错误恢复和配置保护。[任务入口](../changes/ide-plugin-language-decoupling/tasks/README.md)采用 S1 核心语义 → S2 调度/依赖收尾 → V 最终回归，默认同分支串行。直接打开 [S1](../changes/ide-plugin-language-decoupling/tasks/s1-core-sync-decoupling.md)或 [S2](../changes/ide-plugin-language-decoupling/tasks/s2-scheduling-dependency-cleanup.md)即可查看该步实施、实际受影响方法/类的最小回归和交接要求；最后按[独立 V 文档](../changes/ide-plugin-language-decoupling/testing/final-acceptance.md)在最终组合代码上执行完整插件与兼容检查，不再从总方案拼接步骤。代码引用扫描由现有构建门禁按有效输入集中执行，不在每个 GUI 动作后人工重复。

真实 GUI 仅补自动化无法证明的必要集成，按[最终验收文档](../changes/ide-plugin-language-decoupling/testing/final-acceptance.md)执行最小链。GoLand 原生 Git 全流程、代码补全/引用、Go Modules、GOROOT、用户项目 `go test`、运行或调试不再是常规门禁。移除仓库不是禁止运行磁盘上的代码；重新加入不保证语言运行配置即时恢复。发现可归因于 ReqWS 的真实范围错误仍需修复。

首次打开/恢复、安全与并发回归仍按受影响层验证；版本特有 GUI、50+20 规模、sleep/wake、视觉/无障碍或长时间 idle 按改动风险选择，不机械重复旧全矩阵。测试、工件和实际 GUI 证据绑定候选；未运行项明确记录，不从历史 GO 或 ZIP 构建推出新候选通过。

### GoLand 同步追踪

当前代码提供可选 `-Dreqws.sync.trace=true`；沿用[原追踪契约](../changes/goland-plugin-support/technical-design.md#182-可选同步追踪)中的通用格式和脱敏边界，S2 已删除其中旧 registry/follow-up 事件与字段。只在事件、恢复或性能问题需要时启用，不作为每个验收动作的固定流程。该参数要进入实际 GoLand JVM，修改真实 VM options 或重启前先取得对应授权，保留用户原参数，不更改 app 内默认配置。

在一次明确的观察区间中记录 service/request/source 与事件序号，区分收到、匹配、防抖、apply/no-op，不重复累计 collector 重放。可用以下命令定位已授权运行产生的日志：

```bash
reqws_trace_log='/absolute/path/to/current/idea.log'
rg -n 'REQWS_SYNC_TRACE schema=1 ' "$reqws_trace_log"
```

测量后移除该选项或设为 false，并按授权流程核对新会话；不清空旧日志制造零记录。追踪仍只含固定枚举和数字，不输出 workspace 路径、digest、URL 或异常文本，不新增网络/IPC 导出，也不改变同步判定。S1 已移除主路径 `REGISTRY` 阶段；S2 已删除 `REGISTRY_START`/`REGISTRY_END`、`EVENT_EPOCH` 与 follow-up 专属字段和判定。当前 schema 仍为 1，通用事件与字段仍以名称输出，不将枚举序号作为日志契约；roots 事件通过普通 `PROJECT_MODEL_CHANGE` 记录。通用追踪继续保留。

## 8. 文档工作流

已知文档可以直接阅读相关章节；定位或权威性不明时使用[文档总索引](../README.md)。`docs/reference/` 是冻结历史输入，不是当前需求。改变已记录的行为、验收条件或开发流程时，按[项目文档规范](../standards/documentation-standard.md)更新必要材料；只有改变实现契约的决策需要先于代码更新，轻量修正不必逐项填写完整生命周期表。

源码用 Git commit/diff 定位，测试报告用命令、结果和 CI run/工件入口定位。不要提交源码逐文件哈希清单，或把测试期间算出的 SHA-256 值抄进方案、指南和报告。历史台账可从当前树移除并指向 Git 历史；正式 Release 校验、依赖 lockfile integrity 和程序运行时摘要仍保留。完整边界见[插件开发与测试规范](../standards/ide-plugin-development-testing.md#7-git测试证据与-gradle-版本管理)。

新增、移动、重命名、删除文档或改变状态、摘要时，同步最近一级及必要的父级 `README.md`。文档改动完成后运行 `npm run docs:check`；无法运行时记录原因，不能声明已通过。适用时使用 [reqws-documentation Skill](../../.agents/skills/reqws-documentation/SKILL.md)，不用为只读检索预加载完整文档栈。

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

默认 `REQWS_BUILD_PROFILE=local` 保持 ad-hoc 签名，不复制更新 feed，并拒绝覆盖带更新配置的目标 App。`personal-release` 需要持久公开 DER 证书、匹配 pin、签名身份与钥匙串，缺项立即失败；正式包仅支持 arm64。生产更新服务、Settings 手动更新、安装活动互斥和 Release 工作流已接入；长期身份和 GitHub Environment 已配置，公开 CER 已放入实施工作树，真实 CI 签名及 Release 两版本验收仍待完成，见[实施记录](../changes/macos-self-update/implementation-2026-09-19.md)。

安装脚本会在目标目录进行 staging、旧版备份、整体替换和尽力回滚；不要用 `sudo` 包裹整个 npm 命令，也不要弱化遗留 lock/staging/backup 的 fail-closed 检查。

GitHub Actions 的 branch/PR 检查和 tag Release 契约见 [CI 与 Release 需求包](../changes/github-actions-ci-release/README.md)。发布只接受默认分支上与 `package.json`、`package-lock.json` 一致的 `vMAJOR.MINOR.PATCH`。历史 ad-hoc 资产保持不变；后续工作流采用[个人自签名方案](../changes/macos-self-update/technical-design.md)，带 Hardened Runtime，但没有 Developer ID 或 Apple 公证。首个可更新版本需要手动 bootstrap。

CI 保留只读权限的 `goland-plugin` job，在 macOS + JDK 21 上执行全部既有插件检查及 261/262 Verifier，并校验候选 ZIP 的 ID/版本。后续 Release 在 tag 校验后并行执行完整 Desktop 检查、arm64 app 打包和完整插件检查/打包，全部成功后才发布 `ReqWS-<version>-macos-arm64.zip`、`ReqWS-<version>-goland-plugin.zip`、`latest-mac.yml` 与覆盖前三个资产的 `SHA256SUMS`；不再构建 x64 app。插件作为 unsigned 独立附件，不嵌入 app，也不自动安装或上传 Marketplace。

Desktop package job 使用受保护的 `macos-release` Environment，私钥只传给 `with-macos-signing.mjs` 的单一步骤。其子命令 `build-macos-release.mts VERSION` 在临时信任有效期间完成签名、ZIP 解压复验和元数据生成，随后清理信任、钥匙串及 P12。publish job 校验精确资产集，并下载 draft 校验实际字节后才公开；不能只依据非零大小。正式 CER 与 Secrets 配置见[技术方案 §4](../changes/macos-self-update/technical-design.md#4-一次性生成密钥与证书)。

P12 导入必须使用 `security import -f pkcs12`；macOS 的自动格式识别可能把有效 OpenSSL 3 P12 误报为密码错误。wrapper 日志只显示固定失败阶段，不回显秘密或原生异常。`tests/integration/macos-signing-import.test.ts` 使用真实系统命令覆盖正确密码导入、身份匹配和错误密码拒绝；不会运行发布 wrapper 或伪造 GitHub 上下文。CI 与 Release 的项目检查在一次性 runner 缺少 `openssl@3` 时通过 Homebrew 安装，并显式选择其路径；本地检查只使用已有工具，缺失时报告错误。

证书有效期核对、P12/PEM 包装密码更新、Secrets 修复和身份迁移使用项目级 [reqws-signing-maintenance](../../.agents/skills/reqws-signing-maintenance/SKILL.md)。既有身份从指定私有备份的明确 commit 临时恢复；不重新建立固定 home/桌面备份。只更新包装密码不改公开 CER 或 pin；续签/换密钥按身份迁移处理，不能预设旧客户端继续接受。完成备份读回恢复后清理本地秘密材料，保留源码公开 CER；技能不自动授权发布或真实安装。

CI/Release 使用 `-PreleaseVersion` 将插件内嵌版本绑定到项目/tag 版本；无参数本地构建仍保留默认版本。Electron 下载和稳定 GoLand IDE 缓存只用于加速，不能跳过 `npm ci`、`npm run check` 或插件验证。发布脚本回归使用 `python3 -m unittest discover -s tests/workflows -p 'test_*.py' -v`；真实 tag 发布及 GUI 验收仍需单独取证，历史版本资产不被改写。

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
- 有实际影响的需求、设计、测试、交付或指南已更新，并同步必要索引；小型修正无需空文档或完整影响台账；
- 已按第 2、7 节完成相关检查并报告无法执行的部分；涉及 macOS package/install 时额外执行相应 smoke，不把打包成功视为完整 GUI 验收；
- Renderer 变化准备截图，交付变化记录签名、公证、迁移、回滚和已知限制；
- `git diff --check` 通过，生成目录和凭据没有进入变更。

## 12. 设计依据与追溯

- [全局设置需求包](../changes/global-settings/README.md)记录 settings、持久化兼容、typed IPC、启动语言解析和验证证据。
- [CI 与 Release 需求包](../changes/github-actions-ci-release/README.md)记录 GitHub Actions 触发器、权限、缓存与并行、arm64 应用/独立插件资产和发布限制。
- [GoLand 插件支持需求包](../changes/goland-plugin-support/README.md)保留原实现、通用 manifest/ownership/VCS 契约及原工件验证记录；其 Go 成功条件不再约束后续候选。
- [IDE 插件语言解耦](../changes/ide-plugin-language-decoupling/README.md)定义当前开发/测试边界；S1/S2 已实施，V 结果与适用范围见 [2026-09-19 验收记录](../changes/ide-plugin-language-decoupling/testing/acceptance-2026-09-19.md)。
- [MVP 实现快照](../changes/mvp/README.md)保存初始范围、交付与验证历史；其状态为 archived，只用于理解演进背景。
- [历史参考](../reference/README.md)是冻结输入，不作为当前开发决策。没有 active 设计覆盖的现状必须回到代码与测试核实。
