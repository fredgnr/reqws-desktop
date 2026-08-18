---
title: GoLand 插件支持探索与实施计划
type: technical-design
status: active
updated: 2026-08-18
---

# GoLand 插件支持探索与实施计划

本文供在 macOS 本地运行的 Codex agent 使用。任务涉及 Electron、macOS LaunchServices、JetBrains Platform、Workspace Model、VFS、VCS 和 Go 多 module 行为，允许存在探索和失败试验，但所有试验必须受控、可证伪、可清理。

## 1. 本次实施目标

只实现以下闭环：

```text
manifest v1 跨 IDE 契约
        +
Desktop GoLand availability/open flow
        +
GoLand plugin v0.1 项目内容、VCS、监听和 Tool Window
        +
真实 macOS GoLand 验收
```

不建立或试验：

- GoLand 到 Desktop 的双向通信；
- 插件内仓库增删或分支操作；
- ReqWS managed `go.work` 或运行配置生成；
- 插件签名、Marketplace、自定义插件仓库或 updater；
- Remote Development、Windows、Linux 或其他 IDE。

若探索过程中发现这些能力可能有帮助，也只能记录为范围外观察，不能在本分支加入协议、stub、TODO 实现或隐藏入口。

## 2. 分支与起点

- 工作分支：`feat/goland-plugin-support`；
- 文档起点：创建分支时的 `main` exact head；
- 不在 `main` 上直接开发；
- 本地开始前核对远端 branch head 和当前文档 commit；
- 后续实现提交只进入该 feature 分支；
- 不自动创建、合并 PR，不自动发布 plugin、release 或 tag；
- 若 `main` 前进，先审查冲突，再使用普通 merge 或 rebase；不得未经授权 force-push 覆盖他人实现提交。

本地初始化：

```bash
git fetch origin
git switch feat/goland-plugin-support
git pull --ff-only origin feat/goland-plugin-support
git status --short
git log --oneline --decorate -5
```

开始代码修改前必须完整阅读：

```text
AGENTS.md
.agents/skills/reqws-documentation/SKILL.md
docs/README.md
docs/standards/documentation-standard.md
docs/changes/goland-plugin-support/README.md
docs/changes/goland-plugin-support/requirements.md
docs/changes/goland-plugin-support/technical-design.md
docs/changes/goland-plugin-support/testing/test-plan.md
```

## 3. 探索规则

### 3.1 先证伪，再生产化

每个不确定点先建立最小实验：

```text
假设
固定环境
固定 fixture
最少代码
执行步骤
通过条件
失败条件
退出条件
```

只有满足通过条件的路径才能进入 production adapter。探索失败不等于任务失败，应保留结论、测试命令和必要日志，然后删除死代码。

### 3.2 实验代码边界

- spike 可放在独立短期 commit、测试 source set 或明确的实验目录；
- 不创建长期保留的 `old`、`backup`、`prototype2`、`final-new` 目录；
- 方案选定后删除未选实现和无用依赖；
- 不把 reflection、`@Internal`、`@Experimental` 或 private API 当作快速通路；
- runtime fallback 只有在单一路径具有真实失败证据时保留；
- fixture 只使用 `mktemp` 或 test sandbox，不读取真实私有 repository。

### 3.3 提交边界

建议提交类型：

```text
chore(goland): scaffold plugin build
spike(goland): validate content root strategy
feat(goland): add manifest reader and tool window
feat(goland): synchronize managed project roots
feat(goland): synchronize git roots
feat(desktop): add GoLand launcher
fix(goland): recover from atomic manifest replace
test(goland): cover project model ownership
docs(goland): record verified implementation
```

每个提交只包含一个可解释目标。不要在同一提交中同时重写 Desktop workspace transaction、插件项目模型和 UI。

### 3.4 证据格式

每个 spike 至少记录：

```text
假设
exact commit
macOS version / architecture
GoLand version / build / install source
JBR and build JDK
Gradle / Kotlin / plugin target
fixture layout
commands and steps
observed result
pass / fail
selected approach
rejected approach and reason
remaining risk
```

形成可复现真实验证后，新增：

```text
docs/changes/goland-plugin-support/testing/verification-YYYY-MM-DD.md
```

并更新 `testing/README.md`。不要提前创建空报告。

### 3.5 停止探索条件

达到当前工作包 exit criteria 后立即停止横向比较。单个问题最多保留：

- 一个首选方案；
- 一个有明确触发条件的 fallback；
- 一个最后 fallback，仅在前两者都有失败证据时执行。

## 4. 固定测试 fixture

建立可重复生成的本地 fixture：

```text
<tmp>/reqws-goland-fixture/
├── .reqws/workspace.json
├── repo-a/        # active, independent Git repo, go.mod
├── repo-b/        # active, independent Git repo, go.mod
├── repo-c/        # retained, not in manifest, independent Git repo
└── notes/         # ordinary non-repository directory
```

要求：

- repo-a、repo-b 在 manifest；
- repo-c 存在磁盘但不在 manifest；
- 三个仓库各自 `git init`，不使用 worktree；
- repo-a、repo-b 各有可运行的最小 Go package、test 和 main package；
- 仓库默认互不依赖，避免把跨仓 dependency management 混入本次门禁；
- 可另生成一个包含用户已有 root `go.work` 的 fixture，只验证插件不修改该文件；
- generator 能重置到初始状态；
- 额外 fixture 覆盖空格、NFC/NFD Unicode、missing repo、symlink escape 和 50+20 规模；
- fixture 不提交凭据、真实 remote 或真实 workspace path。

## 5. 工作包总览

| 工作包 | 目标 | 当前状态 |
|---|---|---|
| W0 | 锁定工具链和可用公开 API | baseline 与 JDK 21 toolchain 已锁定；当前修复候选对 GO-261.25134.147 / GO-262.8665.270 fresh Verifier 均为 Compatible，exact-head 报告待 GUI 后形成。 |
| W1 | 建立只读 plugin 骨架 | parser、digest、Tool Window、Safe Mode 与自动化整合检查已完成。 |
| W2 | 选择项目模型策略 | A 因 ownership 安全门禁失败，已选择 B；companion-marker adapter 以 `.idea/reqws-managed-project-model.json` verified atomic managed/recovery claims 为权威，legacy PSC 仅迁移，异常窗口、cold recovery 与 JPS serialization 需由最终自动化确认，真实 IDE reopen 待 GUI。 |
| W3 | 建立可靠自动同步 | manifest 尚不存在时即安装 canonical watcher；350 ms、latest-wins、自动触发的同生命周期 clean digest no-op、跨 read failure 保留的手动强制 reconcile、部分失败回退重放与 reopen 强制收敛已实现/test。 |
| W4 | 完成 VCS 与 Desktop 启动 | 保守 created/borrowed VCS 改用 verified atomic v2 pending/tombstone，并以 mapping revision/full-snapshot quiescent merge-retry 同时覆盖 Settings 与 pooled auto-detect writer；安全 GoLand launcher 已实现，最终自动化与真实启动待验证。 |
| W5 | 完成功能、规模和安全验证 | 当前 Desktop/插件自动化、结构检查、插件 ZIP 与 fresh 261/262 Verifier 已通过；GUI、Go 功能、reopen 和规模证据仍未完成。 |
| W6 | 整合 CI、指南与验收材料 | 独立 CI job 与指南已更新；exact-head GUI、验证报告和最终 handoff 待 W5。 |

工作包的决策依赖仍是 `W0 → W1 → W2 → W3 → W4 → W5 → W6`；实现可在边界稳定后并行，但 W5/W6 的完成结论必须基于整合后的 exact-head，而不是各工作包的局部结果。

## 6. W0：环境盘点与构建基线

### 6.1 目标

在修改业务代码前锁定真实 GoLand、JDK、Gradle 和插件依赖基线，并验证最小插件可运行。

### 6.2 已锁定结果

| 项目 | 结果 |
|---|---|
| IntelliJ Platform Gradle Plugin | 2.18.1 |
| Gradle wrapper | 9.3.0 |
| Kotlin | 2.3.20 |
| GoLand target | 2026.1.3 |
| build JDK / Java release / JVM target | 21 |
| build range | `since-build: 261`，无 `until-build` |
| Plugin Verifier | 配置 GoLand 2026.1.3 与 2026.2 |
| plugin identity | `com.reqws.workspace`；Kotlin package `com.reqws.goland` |
| 本机候选 | GoLand 2026.1.3，build `GO-261.25134.147` |

直接任务为 `test`、`verifyPluginProjectConfiguration`、`verifyPluginStructure`、`verifyPlugin`、`buildPlugin` 和用于调试的 `runIde`。IntelliJ Platform Gradle Plugin 2.18.1 由 `verifyPlugin` 执行配置的 Plugin Verifier，不另设第二个 verifier 任务。

### 6.3 Exit criteria

- 最小插件能在隔离 sandbox GoLand 加载；
- build、test、verifier 和 ZIP 命令可复现；
- 选定一套 production baseline；
- 选择理由和 exact versions 有证据；
- 未使用 Gradle IntelliJ Plugin 1.x；
- 未引入 internal/private API；
- `integrations/goland/` 骨架不影响 Desktop `npm ci` 和 package。

## 7. W1：插件骨架与只读 manifest

### 7.1 目标

建立完整 lifecycle、parser 和诊断，但暂不修改项目模型或 VCS。

### 7.2 实现

- Gradle project、wrapper、plugin.xml 和 resource bundle；
- `ReqwsProjectDetector`；
- `ManifestReader`、1 MiB size gate、UTF-8 JSON parser；
- schema v1 data model；
- root identity、relative path、duplicate 和 symlink validation；
- SHA-256 digest；
- project-level service 和 disposable scope；
- Tool Window view model；
- 先归档 Tool Window 高保真原型，再以稳定 261 Swing API实现状态徽标、工作区摘要、紧凑仓库列表、诊断摘要和分层操作区；
- `Sync Now` 暂时只重新读取并展示；
- Safe Mode 只读 gate；
- golden fixtures；
- parser/path/digest 单元测试；
- diagnostics redaction。

### 7.3 Exit criteria

- 普通项目无 Tool Window 干扰或重逻辑；
- valid fixture 展示 workspace、branch 和 active repositories；
- Tool Window 与归档原型具有同一信息层级，状态不只靠颜色表达，窄宽度下仓库行不拉伸且三个动作都可达；
- malformed、oversized、unsupported、root mismatch 和 path escape 有稳定错误；
- Safe Mode 无 model/VCS/external-process 副作用；
- project dispose 后无未管理 task；
- 尚未修改 Content Root、module 或 VCS mapping；
- plugin tests、verifyPlugin、buildPlugin 通过。

## 8. W2：项目模型策略 spike

### 8.1 目标

在真实 GoLand 中选择一个可生产化的项目模型策略，并建立所有权保护。

### 8.2 EXP-02A：单 module、多 Content Root（安全门禁失败）

已完成的公开 API spike 证明 Workspace Model 可以 add/remove/re-add Content Root，并保留 module 的 SDK/dependency。但生产化还要求先移除 GoLand 创建的 workspace-root Content Root：

- workspace root 打开后取得现有 module；
- repo-a、repo-b 作为活动 Content Root；
- workspace root 不作为代码 Content Root；
- repo-c、`.reqws` 和 notes 不进入项目内容；
- 使用公开 Workspace Model API；
- 验证 Project View、Search Everywhere、Find in Files、Go completion/navigation/test/debug；
- 模拟 add repo-c、remove repo-b、re-add repo-b；
- 重启 GoLand；
- 增加用户非 ReqWS module/root 并确认保留。

默认 workspace root 没有 ReqWS entity source 或持久化 ownership，插件不能证明自己有权删除它。启发式采用该 root 会违反“所有权不确定时不做破坏性移除”，所以 A 在进入完整 GUI 对比前即按安全退出条件失败；不保留 production 代码。

### 8.3 EXP-02B：workspace root + owned excludes（已选择）

B 保留默认 workspace-root Content Root，只排除 `.reqws` 和 root 直接子级中已确认的非活动 Git repository，不排除普通未知目录。每个新 target exclude 在同一 Workspace Model transaction 中增加一个指向虚拟 `.reqws/.goland-ownership/<128-bit-token>` 的 companion marker exclude；权威 ownership 位于 `.idea/reqws-managed-project-model.json`，以同目录临时文件、原子替换和回读校验发布。每次 model mutation 前先保存下一份 managed + recovery claims；同一 JVM 不清 recovery，进程重启后的 cold load 若 target+marker pair 仍完整就保留 recovery 并完成删除，只有两者都不存在时才压缩恢复信息。旧 PSC v2/v3 仅在 atomic 文件不存在时迁移。已有等价用户 exclude 只借用，任何 partial、重复、跨集合或物理 marker namespace 冲突均保守失败。

### 8.4 EXP-02C：每活动仓库一个 module（未执行）

B 已成为唯一 production path，因此不继续探索 C，不增加 runtime fallback。若 exact-head GUI 证明 B 失败，应重新评审并记录新证据。

### 8.5 Production adapter

选定策略后实现：

- current model snapshot；
- desired active path set；
- ownership state；
- `add / keep / remove-owned / conflict` planner；
- write transaction；
- `.idea/reqws-managed-project-model.json` verified atomic store；
- legacy PSC migration、atomic state reload、独立 JPS serialization contract 与 reopen recovery；
- pre-mutation managed/recovery 落盘、同 JVM recovery 保留、post-commit trust/dispose 与 remove/re-add new-token recovery；
- user-owned entries preservation；
- ownership conflict 的保守失败。

### 8.6 Exit criteria

- 选择符合 active 技术方案所有权/API 门禁并最终通过完整验收矩阵的策略；
- 形成唯一 production `ProjectModelAdapter`；
- 未选 spike 代码、依赖和配置已删除；
- model-level tests 覆盖 initial/add/remove/re-add、marker tamper、verified atomic 发布/回读失败、legacy PSC migration、同 JVM recovery 保留、cold state reload 与 JPS serialization；真实 GoLand reopen 由 GUI 补足；
- 用户无关 module/root 不丢失；
- retained repo 不进入默认项目内容；
- Plugin Verifier 无禁止 API；
- 选择过程先记录在 active 技术方案；只有完整 Verifier 与 GUI 证据形成后才写入 dated verification report。

## 9. W3：VFS 监听与最终收敛

### 9.1 目标

在 Desktop 原子写入、事件 burst、临时缺失和错误恢复下，最终状态始终与最新有效 manifest 一致。

### 9.2 实现

- 每个 file-based project 在首次读取前安装 canonical exact manifest/parent watcher，使 initial absent → create 也可恢复；
- create/delete/move/rename/replace/content event 覆盖；
- background read/parse；
- 固定 350 ms debounce；
- single-flight coordinator；
- latest-wins pending candidate；
- 仅同一个 clean coordinator 生命周期内的自动/VFS same-digest 请求 no-op；手动 `Sync Now` 与新 service/reopen 均强制 reconcile；
- temporary missing 的有限 retry；
- invalid candidate 保留 last good model；
- project dispose cancellation；
- manual sync 复用同一 coordinator；
- lifecycle states 和错误去重。

### 9.3 实验

- 使用 Desktop `writeJsonAtomically` 或等价 Node script 替换 manifest；
- 100 次 A/B 快速连续写入；
- invalid JSON → valid JSON；
- target delete/rename gap；
- same bytes 重复写入；
- apply 进行中再写入新目标；
- project close/dispose；
- sleep/wake 后自动或手动同步。

### 9.4 Exit criteria

- 最终项目模型等于最后一个 valid manifest；
- 同一 coordinator 生命周期内自动触发的 clean digest 不重复 apply；手动 same-digest 必须重放，且手动或部分 apply 失败后 baseline 保持 dirty；
- invalid/temporary missing 不清空 last good roots；
- 单项目最多一个 apply；
- coordinator exception 不死锁；
- 无持续 indexing loop 或 VFS event storm；
- 自动测试覆盖 debounce、latest-wins、dispose 和恢复。

## 10. W4：VCS Root 与 Desktop GoLand 启动

### 10.1 目标

完成用户从 Desktop 打开 GoLand，并让活动 repository 同时成为正确 Git Root 的端到端路径。

### 10.2 插件 VCS

实现：

- 读取现存 VCS mappings；
- apply 前重新检查 repository 实时 filesystem identity/`.git` 状态；expected/set/self-event/quiescence 全部使用平台 observable canonical form（exact directory last-wins，再按 directory 排序）。后台 plan 绑定 mapping change tracker 的 revision 与完整 snapshot，且不把 pending external 误当作最新 live：只保留不同 directory 的 live-only 完整对象；pending-only 或同 directory 不等对象均等待事件，达到上限就在任何 ownership/mapping 写入前零写入 fail closed，绝不自动恢复 pending-only。只有当前 plugin write 的同步回调可识别为 self-event，延迟的相同列表回调仍记作 external。EDT closure 内执行 final 完整 equality（含 root settings）/revision 检查与整表 set，Settings writer 不能插入其间；pooled auto-detect 的外部 revision/snapshot 进入下一轮有界 quiescent 重规划；
- 对同路径 Git mapping 记录为 `BORROWED`，不取得删除权；
- 添加缺失 active mappings；
- `.idea/reqws-vcs-ownership.json` v2 以 verified atomic write 保存 stable、pending add 与 pending remove；真正的 plugin add 按 actual live → expected whole-list 的真实变化在 mutation 前写 pending add，pending-only external 恢复不尝试写入。只有同 JVM 平台提交、同步 ack 和静止检查成功后才记录为 `CREATED`；本轮 observer 已观察到的 external event、snapshot mismatch 或 retry 都在继续前将 `CREATED` 持久降为 `BORROWED`，最终 verify 后才到达的事件则使 baseline dirty 并由下一轮 automatic reconcile 先降权。移除前先持久化已撤权 tombstone，cold pending 永不授权删除；legacy PSC v1 只在 atomic 文件不存在时迁移，atomic 文件存在但不可验证时禁止 fallback；
- 保留用户 mapping；
- workspace 内额外/root mapping 保留并进入 degraded，stale `CREATED` 删除权在 mapping 消失时丢弃；
- path identity normalization；
- Git plugin unavailable 的稳定诊断；
- apply failure 的 degraded state；
- Git repository manager refresh；
- Tool Window 展示 model/VCS 分层状态。

261 public API 没有 mapping CAS，也不暴露后台 auto-detect 使用的内部锁，因此 production 以公开 `VCS_CONFIGURATION_CHANGED` 的 revision/完整 snapshot capture 建立乐观并发协议；更晚 external event 使 digest baseline dirty 并强制 automatic reconcile。自动化用独立 pooled writer 的两种反序时序证明不会静默丢未知 mapping/`rootSettings`；exact-head GUI 仍验证真实平台事件和 Git UI，但不把后台 scanner 竞态降级为仅 GUI 才处理的残余风险，也不宣称平台级线性化。

### 10.3 Desktop

实现：

- `SystemAvailability.goland`；
- standard/user/Toolbox resolver；
- 各来源候选独立验证后再 canonical 去重，损坏的同路径 Toolbox launcher 不得污染有效 standard/user app；
- bundle identifier 和 canonical path 校验；
- 启动 root 的 production strategy；
- 允许 macOS `/tmp`、`/var` 第一层系统别名，但拒绝更深层 symlink；
- `openGoLand` IPC、Main handler、preload；
- renderer button、disabled/loading/error；
- zh-CN source 文案和 en-US 翻译 workflow；
- unit、IPC、preload、renderer 和 integration tests；
- VS Code/Cursor/Finder 回归。

### 10.4 真实启动矩阵

| 状态 | 标准 `/Applications` | Toolbox / user Applications |
|---|---:|---:|
| GoLand 未运行 | 验证 | 验证 |
| GoLand 已运行且已有项目 | 验证 | 验证 |
| workspace 路径含空格 | 验证 | 验证 |
| workspace 路径含 Unicode | 验证 | 验证 |
| 插件未安装 | 记录降级 | 记录降级 |
| 插件已安装 | 端到端验证 | 端到端验证 |

### 10.5 Exit criteria

- Desktop 打开 manifest 绑定的正确 root；
- command/args 分离并使用 `shell: false`；
- app missing、root missing、spawn error、non-zero exit 有稳定错误；
- active repository Git roots 与 manifest 一致；
- retained repository 不在 Git roots；
- 用户 mapping 不丢失；
- verified atomic VCS state 在失败窗口不授予陈旧删除权，cold pending 不删除 mapping；
- Settings 与独立 pooled writer 的两种反序时序都由 canonical revision/full-snapshot merge-retry 保留不同 directory 的 live-only mapping 和 `rootSettings`；mutation 与 event publication 分离时，pending-only 或同 directory ambiguity 都零写入，actual plugin add 才先持久 pending add；本轮观察到的 external event 会在重规划前撤销 `CREATED`，最终 verify 后到达的事件使 baseline dirty并强制下一次 automatic reconcile；
- Tool Window 能区分 model 与 VCS degraded；
- Desktop 相关全量测试通过。

## 11. W5：功能、规模与安全验证

### 11.1 目标

证明所选模型不仅在 Project View 中可见，而且支持真实 Go 开发、恢复、边界保护和 GL-16 Tool Window 视觉可用性。

### 11.2 GoLand 功能

对每个活动 repository 验证：

- `go.mod` recognized；
- code completion；
- navigate to declaration；
- find usages；
- run test；
- run/debug main package；
- Git diff/log/commit root selection。

同时验证：

- 无 root `go.work` 的独立 modules；
- 用户已有 root `go.work` 不被插件修改；
- 如果 GoLand 原生行为存在限制，只记录现状，不生成或接管文件。

### 11.3 生命周期和恢复

- first open + trust；
- add repo-c；
- logical remove repo-b；
- repo-b 目录仍存在；
- re-add repo-b；
- GoLand restart；
- Desktop not running；
- malformed → valid manifest；
- manifest temporary missing；
- manual sync；
- plugin disable/enable；
- plugin ZIP reinstall；
- sleep/wake。

### 11.4 规模和性能

- 50 active + 20 retained；
- 10 次 add/remove sequence；
- 100 次 rapid rewrite；
- 观察 sync stage duration、EDT freeze、event/apply ratio、index completion、CPU、threads 和 memory；
- 同一 live coordinator 的自动 clean same-digest no-op、手动 same-digest 强制 reconcile，并记录 reopen 强制 reconcile 的额外 apply；
- Project/Git UI 保持可交互。

不设置脱离本机环境的绝对毫秒门限，但以下任一情况判失败：

- 可感知长时间 UI freeze；
- 持续 CPU；
- 无限 indexing；
- event burst 触发近似等量 apply；
- 重复同步后 thread 或 memory 持续增长。

### 11.5 安全与对抗

- root mismatch；
- absolute relativePath；
- `..` path；
- symlink escape；
- oversized manifest；
- invalid UTF-8/JSON；
- duplicate repo identity；
- URL/field 伪装 command；
- malicious PATH `goland`；
- plugin state tamper；
- Safe Mode；
- ownership conflict；
- 用户 mapping/module/root 保护；
- 插件不存在任何 delete/clone/checkout 入口。

### 11.6 Exit criteria

- requirements GL-01 至 GL-14 与 GL-16 均有 exact-head 证据；
- Go development basics 通过；
- 没有严重性能或事件问题；
- 安全负向场景符合设计；
- 所有已知限制有明确影响和恢复建议；
- 无范围外协议、`go.work` writer、发布或远程开发代码。

## 12. W6：整合、CI、文档和交付候选

### 12.1 实现

- 根级 `check:goland`、`package:goland` 脚本，名称以实际仓库习惯为准；
- GitHub Actions 独立 `goland-plugin` job；
- `.gitignore` 覆盖 Gradle sandbox/build；
- `integrations/goland/README.md`；
- `docs/guides/user-guide.md` GoLand 安装和使用步骤；
- `docs/guides/development-guide.md` plugin build/test/debug 流程；
- dated verification report；
- 受版本控制的 Tool Window 原型和真实 GoLand 实现候选截图；
- plugin ZIP SHA-256；
- 当前需求包和索引状态评估；
- 文档与代码 traceability。

`goland-plugin` job 只做 branch/PR/dispatch 质量门禁，不修改现有 tag Release 工作流，也不把 plugin ZIP 加入 ReqWS Desktop Release 资产。根 `npm run check` 和 `package:macos` 保持与 Gradle 构建隔离。

### 12.2 最终命令候选

```bash
nvm use
npm ci
npm run check
npm run package:macos
npm run check:goland
npm run package:goland
```

插件目录同时保留直接 Gradle 命令，便于排障：

```bash
cd integrations/goland
./gradlew test verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin
./gradlew buildPlugin
```

### 12.3 Exact-head GUI 验收

必须在实现候选 exact commit 上：

1. 构建 plugin ZIP；
2. 记录 SHA-256；
3. Install Plugin from Disk；
4. 重启 GoLand；
5. 使用固定 fixture 完成 GL-04 至 GL-14；
6. 从 packaged 或开发态 Desktop 完成 GL-01 至 GL-03、GL-15；
7. 记录截图、关键日志、环境和未覆盖边界；其中 Tool Window 截图对照归档原型，覆盖浅/深主题、常用窄宽度和关键状态；
8. 给出 `GO` 或 `NO-GO`。

### 12.4 Exit criteria

- Desktop `npm run check` 和 macOS package smoke 通过；
- plugin tests、verifyPlugin、Plugin Verifier、buildPlugin 通过；
- local ZIP 可安装且 hash 已记录；
- exact-head macOS GUI verdict 为 `GO`；
- 文档索引、链接和 frontmatter 检查通过；
- 用户和开发指南反映实际命令，不含未经验证步骤；
- feature branch 无未解释 spike、TODO、sandbox、build output 或范围外实现；
- `git status --short` 干净。

## 13. 自动化测试分层

| 层级 | 工具 | 目标 |
|---|---|---|
| Desktop unit | Vitest | resolver、spawn args、availability、schema fixture |
| Desktop contract | Vitest | IPC、preload、errors、renderer/i18n |
| Desktop integration | Vitest + temp fixtures | workspace ready、root/manifest validation、回归 |
| Plugin pure unit | JUnit/Kotlin | parser、path、digest、planner、coordinator |
| Plugin platform | IntelliJ test framework | roots、VCS、persistent ownership、Safe Mode |
| Binary compatibility | verifyPlugin / Plugin Verifier | descriptor、dependency、API、bytecode |
| macOS GUI smoke | 真实 GoLand | Project、Search、Git、Go、launch、restart |
| Scale/adversarial | fixture generator + GoLand | 50+20、rapid rewrite、symlink、ownership conflict |

不要用大面积 mocks 替代平台模型测试。纯逻辑可隔离测试，项目模型、VCS 和 lifecycle 必须尽量使用真实 platform components。

## 14. Codex 操作护栏

本地 Codex agent 必须：

- 先读 AGENTS、documentation skill 和本需求包；
- 对未知 JetBrains API 先搜索官方文档和本机 SDK source；
- 每个 spike 明确假设、退出条件和证据；
- 不使用 `@Internal`、`@Experimental`、反射或 monkey patch 作为 production 快捷方式；
- 不改变 Electron sandbox、context isolation 或 preload 安全边界；
- 不把 Git credential、真实 remote 或私钥写入 fixture、日志和 snapshot；
- 不运行 `sudo npm`、`sudo gradle` 或修改系统 GoLand 安装内容；
- destructive test 只作用于 `mktemp` fixture；
- 不删除真实 workspace 或 repository；
- 不自动 publish plugin、release、tag、PR 或 merge；
- i18n 变更遵循项目 translation workflow；
- 每个工作包先运行最小检查，W6 运行全量检查；
- 失败实验先记录可复现证据，再删除死实现；
- 不因未来可能需要而添加双向通道、`go.work` writer、updater 或 remote-development stub。

## 15. Review 检查点

### Checkpoint A：W1 后

审查：

- 构建 baseline 和 plugin dependencies；
- manifest 契约与 Desktop 当前 schema 是否一致；
- parser、path 和日志安全；
- Tool Window lifecycle；
- Safe Mode；
- 无项目模型副作用。

### Checkpoint B：W2 后

审查：

- 选定 strategy 的真实 GoLand 证据；
- Go analysis/test/debug；
- ownership 和用户配置保护；
- retained repository 隔离；
- internal/experimental API；
- 未选 spike 是否清理。

### Checkpoint C：W4 后

审查：

- Desktop resolver 与 launcher 安全；
- VCS mapping merge 与 created/borrowed ownership；
- VFS 并发与错误恢复；
- add/remove/re-add 端到端；
- Desktop 回归和 i18n。

### Checkpoint D：W6 合并候选

审查：

- exact-head automated checks；
- Plugin Verifier；
- GUI evidence 和 ZIP hash；
- 性能与对抗结果；
- active 文档和 evergreen guides；
- 无范围外实现或未解释 TODO；
- feature branch diff 和 commit history。

## 16. Definition of Done

本次只有同时满足以下条件才算完成：

- Desktop 有受测试的 GoLand availability/open flow；
- plugin ZIP 可构建并通过磁盘安装；
- manifest contract 由 TypeScript 和 Kotlin fixtures 覆盖；
- 活动项目内容和 Git Root 与 manifest 一致；
- retained repository 不进入默认项目范围或 VCS；
- add/remove/re-add 无需重启；
- manifest 原子替换、快速连续变化和错误恢复可收敛；
- Safe Mode、root identity、path containment 和 symlink 安全通过；
- 用户 module/root/mapping 不被越权删除；
- Go completion/navigation/test/debug 通过；
- restart 冷恢复且不依赖 Desktop；
- 50+20 规模无事件风暴、持续 CPU 或无限 indexing；
- Desktop、plugin、docs、package 和 verifier checks 全绿；
- 本机 GoLand 2026.1.3 exact build 的真实 GUI verdict 为 `GO`，且 2026.2 Plugin Verifier 通过；
- 指南、verification 和 ZIP hash 已提交；
- feature branch 不含双向通信、managed `go.work`、插件发布或远程开发实现；
- 无未解释 spike、TODO、生成物或工作树修改。
