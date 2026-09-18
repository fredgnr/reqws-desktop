---
title: S1：核心同步语义解耦
type: technical-design
status: active
updated: 2026-09-18
---

# S1：核心同步语义解耦

本任务删除 Go 对 ReqWS 同步成功的限制，在不改变仓库业务、安全与目录范围契约的前提下，形成可编译、可局部验证的主路径。

## 1. 任务状态与输入

- 计划状态：active；核心实现、生产/测试编译与 S1 必要回归已完成。实际证据与 S2 剩余项见第 8 节。
- 前置：开发/测试规范已确定，不依赖 S2 先实现。
- 历史核对基线：`main@409b30e573d47348618620bbc0a52c0dd0710954`；这不是本次新读取的远端 HEAD，实施前按实际 checkout 确认路径、引用与测试名称。
- 输出：Go 主门禁已删除的代码、直接受影响测试及 S2 剩余项，不是可发布候选。

从本文件开始即可组织 S1；执行遵守仓库 `AGENTS.md`。共用规则见[开发与测试规范](../../../standards/ide-plugin-development-testing.md)，架构依据见[总技术方案](../technical-design.md)。不要求先通读 S2 或最终 GUI 清单。

## 2. 必须实现与必须保留

实现后，manifest 仍决定活动 Git 仓库集合；有无顶层/子目录 `go.mod`、内容是否有效，不改变 ReqWS 成员关系或同步判定。主路径不再查询 `VgoModulesRegistry`，不等待 Go 服务、不为 Go 不一致补偿通知，也不产生 `GO_MODULES_REGISTRY_NOT_CONVERGED`。

```text
有效 manifest → 当前仓库路径与身份
→ 受管 Workspace Model / excludes 更新
→ ProjectFileIndex 活动/排除范围验证
→ 最终 trust / project / service 存活检查
→ 协调器接受成功并提交 digest、发布状态
```

PFI 指 `ProjectFileIndex`。PFI 失败仍产生 `PROJECT_CONTENT_NOT_CONVERGED / PROJECT_FILE_INDEX` 并保持 baseline dirty；只有成功重验后才可以恢复 clean。`Synced` 与 `Active` 不承诺 Go 模块、索引分析、构建或运行配置已就绪。

必须保留 ownership/marker、原子状态、稳定目录句柄、路径安全、用户条目保护、正常模型事件、取消原实例传播和最终生命周期检查。Desktop 是 manifest、Git/workspace 生命周期的唯一 writer；插件仍只读 VCS mappings，不改用户语言配置、不执行外部进程、不删除仓库。

## 3. 文件与修改边界

以下路径以 `integrations/goland/` 为根；Kotlin 简写路径省略 `src/main/kotlin/com/reqws/goland/`。以真实调用图判断改动，不按文件名全量删除。

| 入口 | S1 必须完成 | 留给 S2 的边界 |
|---|---|---|
| `projectmodel/ReqwsGoModulesSynchronizer.kt` | 删除 synchronizer、projection/view/waiter、registry adapter、`go.mod` 探测、轮询及专用 helper。 | 不能留下空类、恒真实现或可选 Go 分支。 |
| 同文件 `ReqwsProjectRootsChangeNotifier` / `PlatformReqwsProjectRootsChangeNotifier` | 仅为 Go 补偿服务时一起删除及调整对应测试；有独立通用用途才提取保留并直接验证。 | 不保留无调用点的 notifier 等待 S2；第 5 节规定例外。 |
| `projectmodel/ReqwsProjectModelAdapter.kt` | 删除 Go projection 注入/调用、Go 专属 error enum、主路径 `REGISTRY` 阶段；保留真实模型更新与 PFI。 | 不借机改 ownership、state schema 或目录策略。 |
| `project/ReqwsProjectionApplier.kt` | 删除 Go 错误/field 映射；删除已失去意义且可随签名一起清理的参数。 | 通用 PFI 错误码、异常和生命周期逻辑不能删除。 |
| `project/`、`sync/` 的直接调用方及测试 | 删除类型/签名导致的调用方、合并分支及测试改动必须在 S1 一起完成。 | 不影响当前正确性的剩余调度简化可交 S2；不能交接编译错误或已知行为缺陷。 |
| `diagnostics/`、`ui/`、资源中的直接 Go 分支 | 按实际引用移除主链专用错误显示/追踪，受影响断言同步修改。 | 共享但已不影响主链正确性的残留枚举/字段可列入 S2；不重做布局。 |
| `src/test/kotlin/com/reqws/goland/` 对应测试 | 删除纯 Go 功能测试；混合测试保留通用断言，改用 PFI/普通模型失败注入。 | 有效通用测试保持可编译，不禁用后推。 |

S1 不主动清理无编译耦合的 Gradle/descriptor Go 依赖或重写整个 follow-up 状态机；把这类剩余项明确交给 S2。若依赖、调度或文案必须与当前改动一起变化才能保证正确，就当步完成并追加相应最小回归，不为坚持边界增加适配层。

## 4. 实施步骤

**第一步：确定一次性的影响清单。** 核对上述类型、调用点及实际存在的对应测试，选出第 6 节各项需要的方法/类。只需建立本任务清单，不要求每轮重新扫描整仓代码引用或字节码。

**第二步：原子删除主门禁。** 连同类型、注入、调用、错误映射、直接引用和对应测试一起修改。保持模型更新 → PFI → 最终生命周期 gate → 成功提交的顺序。不要吞 Go 异常来伪装删除，也不要直接写入 clean digest 跳过正常提交路径。

**第三步：迁移仍有效的回归。** 旧 Go 超时/异常用例若用于证明通用 dirty baseline、取消、dispose、恢复或状态发布，在当前阶段改成 PFI/普通模型失败注入；不得因注入源已删而整批删除。用户文案实际发生 delta 时走仓库现有 i18n 流程，纯 Markdown 说明不触发翻译。

**第四步：完成本阶段必要回归并交接。** 修复时只跑失败和直接关联用例；完成前在最终 S1 改动上确认第 6 节集合有效，不用追加完整兼容矩阵或 GUI。更新实际变更所对应的说明，并列明仍待 S2 清理的调度、依赖、共享追踪项。

## 5. 正常 roots event 与 Go 补偿的分界

默认删除 Go 专属额外 notifier，但保留 `WorkspaceModel.update()`、正常目录通知与自事件防循环保护。不得把删除 Go 补偿替换成“每次刷新无条件再发一次事件”。

只有找到可复现的语言无关目录适配需求，且正常模型提交不足以满足该需求时，才提取通用 notifier；记录具体调用点/条件，并用普通文本 fixture 证明其必要性和有界性。不允许以 Go registry 滞后或 Go run 配置失效本身作为保留依据。

若直接删除必须连带改 `allowRootsChangeNotification`、`PROJECT_MODEL_FOLLOW_UP` 或相关队列合并分支，就把那部分调用闭环提前到 S1。被改的 manual/trust/latest-wins/自事件分支必须立即回归，不能归类为 S2 后再测。

## 6. S1 最小回归

### 6.1 测试数据与用例

沿用临时 workspace：`.reqws/workspace.json`、active 的 repo-a/repo-b、retained 的 repo-c 和普通 `notes/`。仓库只需本地 Git 与普通文本，不需 Go SDK。共用 fixture 细节见[总测试方案第 2 节](../testing/test-plan.md#2-最小-fixture)。

同一份有效 manifest 下，以无 `go.mod`、顶层无效 `go.mod`、移到子目录作一个小型对照；不运行 Go 命令或 registry 检查。fixture 生成不能放宽 Desktop 生产 URL/Git 路径契约。

| 本阶段项 | 必要断言与触发条件 | 对应总用例 / 优先层 |
|---|---|---|
| S1-R1 语言无关性 | 必做：普通文本仓库按 manifest 生效；对照改变 `go.mod` 不改变成员、目录范围和 ReqWS 成功判定，额外磁盘仓库不自动 active。 | T-01；最接近真实同步主路径的平台/服务测试。 |
| S1-R2 仓库范围 | 必做：添加、逻辑移除、重加后，模型与 PFI 均正确；普通目录、非 owned 配置保留，retained 仓库数据不删除。 | T-02；model adapter/planner。 |
| S1-R3 通用失败 | 必做：PFI 失败仍映射为正确 code/field，不误报成功；修复后同 digest 可恢复且 baseline 正确。 | T-03；applier + 必要 coordinator/service 方法，不必跑整个服务测试集。 |
| S1-R4 状态消费者 | 仅当相关 code/field、成功状态或 UI 消费分支被修改：验证不再要求 Go 成功，同时保留真实 PFI 失败和既有 VCS/missing 语义。 | T-03/T-05 相关部分；view model/state。 |
| S1-R5 受影响保护 | 按实际影响必做：被改入口的 trust/dispose、PCE/协程取消、成功提交或事件/意图分支；独立取消类型不能用一个正常路径替代。 | T-04/T-05 相关部分；直接受影响方法。 |

同一个已有测试覆盖多行时只需选一次；新增少量不足的覆盖，不为每个表项复制一套 fixture。若提前改了 S2 调度，则从 S2 用例中加入与实际差异对应的方法。

### 6.2 选择器与运行方式

历史基线中的候选类包括 `ReqwsProjectModelAdapterTest`、`ReqwsProjectionApplierTest`；必要时从 `LatestWinsSyncCoordinatorTest`、`ReqwsProjectServiceTest`、`ReqwsToolWindowViewModelTest` 选相关方法。这是定位入口，不是全部必跑清单；具体方法名必须从执行时源码确认，不能假定本方案已创建新测试方法。

优先使用 Gradle `test --tests` 选择真实的完整类名/方法名。同次命令可以包含多个选择器；生产与全部测试源码必须可编译，利用正常任务依赖完成，不为一次局部回归重复 clean/build。

确实影响两个核心类且无法稳定按方法隔离时，可退到以下类级回归；它不是默认最小命令，也不代表覆盖了所有条件项：

```bash
# 从仓库根目录开始；保留现有 JDK/Gradle 环境。
cd integrations/goland
./gradlew test \
  --tests 'com.reqws.goland.projectmodel.ReqwsProjectModelAdapterTest' \
  --tests 'com.reqws.goland.project.ReqwsProjectionApplierTest'
```

按选例表另补实际必要的 service/coordinator/UI 方法。记录每个选择器的实际执行数、失败、跳过及结果文件位置；零匹配、全部跳过不是通过。缓存/UP-TO-DATE 仅在能证明相同有效输入和同一选择集合时复用，不凭任务状态代替结果；必要时只重执行受影响测试任务，不清空依赖或 IDE cache。

文档变化在完整 checkout 中执行 `npm run docs:check` 与 `git diff --check`。本任务不默认运行 `npm run check:goland`、完整 Verifier、GUI、未改 parser/URL/持久化/VCS 全矩阵或 Desktop 全业务；实际 CI 仍照常运行，不规避其门禁。

## 7. 完成条件与阶段交接

S1 完成须同时满足：主同步链无 Go 探测/读取/轮询/错误门禁；生产和测试源码可编译；必做及所有被实际改动触发的条件回归在当前结果上有效通过；没有伪成功替身、越权写入、遗留编译错误或已知直接回归。环境阻塞要记录，不能以“留到 V”宣称 S1 完成。

交给 S2 的工作记录按以下内容填写，不需要另建阶段报告文件：

```text
阶段：S1
代码基线 / 实际提交：...
未提交差异（如有）：...
实际修改入口：...
已验证：S1-Rx → 真实选择器 → 执行/失败/跳过数 → 结果位置
未运行项与原因：...
提前完成的 S2 项：...
剩余 S2 项：调度 / 依赖 / 共享追踪 / 当前文档（仅实际存在项）
结论：S1 必要回归通过 / 未完成；不是最终功能 GO
```

## 8. 本轮实施记录（2026-09-18）

- 实施基线：`044c34c10e5a5bbf57c07bfe44c5140e460642eb`；本轮为该基线上的未提交工作树差异，不能仅以 HEAD 代表被测输入。
- 已实现：删除 Go synchronizer、projection/registry adapter、Go 专属 roots notifier、主路径 `REGISTRY` 阶段及 Go 错误映射；同步更新 adapter/applier 的签名与调用者，删除中英文旧错误文案及对应 UI 分支。
- 提前完成的 S2 项：随已删除 notifier 原子移除 `allowRootsChangeNotification` 参数及直接传递；保留通用 follow-up 调度，未重写状态机。
- 回归状态：生产与全部测试源码编译通过；S1-R1～R5 的实际受影响集合共执行 108 项，失败 0、错误 0、跳过 0。测试任务实际执行，未以缓存任务状态替代结果。测试完成后未再改动运行代码或测试输入。
- S2 剩余项：审查 service/tracker/coordinator 的通用 follow-up 与旧补偿残留；清理 Gradle/descriptor 的 Go API 依赖、共享 `REGISTRY_START`/`REGISTRY_END` 等追踪残留，并扩展现有符号门禁。上述残留不再参与当前主同步成功判定。
- 未执行范围：S2 开发与 V 最终整体验收；本轮不产生完整功能 GO，不安装或重启真实 IDE。

### 验证环境、选择器与结果

macOS arm64；JDK 21.0.12.1（现有 Homebrew JDK），Gradle Wrapper 9.3.0，既有 GoLand 2026.1.3 测试平台。仓库根目录执行 `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./integrations/goland/gradlew -p integrations/goland test`，为下表每个选择器追加一个 `--tests` 参数；含空格的方法名整体单引号引用。Gradle 正常任务依赖完成生产/全部测试编译，14 个任务实际执行，构建成功。

以下选择器均以 `com.reqws.goland.` 为完整包名前缀；各行失败、错误、跳过数均为 0。

| 覆盖 | 选择器（追加到公共包名前缀） | 执行数 |
|---|---|---:|
| S1-R1/R2/R3/R5 | `projectmodel.ReqwsProjectModelAdapterTest` | 49 |
| S1-R3/R5 | `project.ReqwsProjectionApplierTest` | 10 |
| S1-R4 | `ui.ReqwsToolWindowViewModelTest` | 19 |
| S1-R4 | `ui.ReqwsToolWindowPanelTest` | 21 |
| S1-R4 | `ReqwsBundleTest` | 1 |
| S1-R5：manual | `sync.LatestWinsSyncCoordinatorTest.manual same digest reapplies and restores a drifted projection` | 1 |
| S1-R5：trust | `sync.LatestWinsSyncCoordinatorTest.trust transition same digest reapplies and restores a drifted projection` | 1 |
| S1-R5：latest-wins | `sync.LatestWinsSyncCoordinatorTest.an apply in progress is followed only by the newest pending candidate` | 1 |
| S1-R5：同 digest follow-up | `sync.LatestWinsSyncCoordinatorTest.a same-digest automatic candidate cannot replace a queued verify-only follow-up` | 1 |
| S1-R5：不同 digest follow-up | `sync.LatestWinsSyncCoordinatorTest.a different-digest automatic candidate supersedes a queued verify-only follow-up` | 1 |
| S1-R5：自事件 | `project.ReqwsProjectServiceTest.testGuardedOwnedProjectModelChangeIsIgnored` | 1 |
| S1-R5：同 digest 外部漂移 | `project.ReqwsProjectServiceTest.testExternalProjectModelChangeForcesSameDigestReplay` | 1 |
| S1-R5：最终提交前 dispose | `project.ReqwsProjectServiceTest.testDisposeWinningBeforeCandidateCommitPreventsDurableDigestAdvance` | 1 |

模型与应用层多个入口、注入和生命周期断言同时变化，采用类级回归覆盖直接影响；service/coordinator 只选择上述实际相关方法，没有执行完整服务/调度测试集。结果为 `integrations/goland/build/test-results/test/TEST-<完整类名>.xml` 与 `integrations/goland/build/reports/tests/test/index.html`（本地生成输出，不提交）。

R1 的 `testManifestProjectionIsIndependentOfGoModContentsAndLocation` 使用本地 `git init`、普通文本仓库和真实 `.reqws/workspace.json`，每次由 `ManifestReader` 重读同一份 manifest，再验证模型/PFI。无 `go.mod`、顶层无效内容、移入子目录均不改变 digest、活动成员和范围；repo-c 保留排除，notes 与用户条目保留。R2 在既有范围用例中补充再次激活和 retained 文本数据不删除，正常受 guard 保护的 roots event 仍发生；模型 no-op 不额外发布事件。

R3 的 PFI 失败仍映射为 `PROJECT_CONTENT_NOT_CONVERGED / PROJECT_FILE_INDEX`；新增 applier/coordinator 用例验证失败后 clean digest 失效、未提交成功，修复后同 digest 经正常路径重新提交，随后才允许 NoOp。R5 分别验证 PCE 与协程取消按原实例传播，以及 PFI 后 trust/service 存活和协调器最终提交保护。

文档与文案检查：`npm run docs:check` 通过；`git diff --check` 通过；`npm run i18n:scan`、`npm run i18n:check` 通过（Desktop 270 keys）。插件只配对删除 `message.goModulesRegistryNotConverged`，未新增或改写译文，未触发翻译委派或基线 acknowledgement；插件中英文 bundle 由上表 `ReqwsBundleTest` 验证。

现有 `verifyForbiddenProductionSymbols` 已通过：扫描 45 个 `src/main` 文件及装配 JAR 的 314 个 class。使用同一生产输入，扫描任务实际执行；扩展为禁止所有 Go API 的规则仍属于 S2。

未运行 `npm run check:goland`、完整保留插件套件、双版本 Verifier、真实 GUI、Desktop 全业务：这些属于 S2 后的 V 或未受影响层，本次阶段验证不替代它们。S1 必要回归通过；不是最终功能 GO。

下一步是[S2：补偿调度与依赖清理](s2-scheduling-dependency-cleanup.md)。若所有 S2 调度已被原子修改消除且只剩零星收尾，可合并完成；仍核对 S2 剩余项并最后执行 V，不为文档拆分强造第二次验收。
