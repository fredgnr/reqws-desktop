---
title: S2：补偿调度与依赖清理
type: technical-design
status: active
updated: 2026-09-19
---

# S2：补偿调度与依赖清理

本任务接收 S1 的语言无关主路径，清理 Go 补偿机制留下的调度状态与依赖，完成针对性回归后交给统一最终验收。

## 1. 任务状态与前置结果

- 计划状态：active；已接收 S1 提交，调度/依赖清理核心代码已落实，必要回归已通过。阶段记录见第 8 节。
- 前置：[S1](s1-core-sync-decoupling.md)已形成可编译主路径，直接回归通过，并提供实际基线、修改项和剩余项。
- 输入：S1 的真实代码结果与阶段记录，而不是旧 main 或旧验证报告。
- 输出：清理完整的候选、S2 必要回归结果、V 所需工件/环境检查清单；不直接声明功能 GO。

先遵守仓库 `AGENTS.md`，阅读 S1 交接记录和本文件即可启动；边界不明确时查[总技术方案](../technical-design.md)，不要求为接手机械重跑 S1 全套。若代码与交接基线不同，先比较差异再确定补测范围。S1 未消除编译错误或直接失败时先处理前置问题，不能把 S2 当成绕过阶段完成条件的方式。

## 2. 本任务目标与不可变边界

删除仅为 Go 补偿服务的 `allowRootsChangeNotification`、`PROJECT_MODEL_FOLLOW_UP`、verify-only / origin digest / event epoch 传递和专用追踪，但仅删除失去用途的部分。通用请求身份、generation、取消或防陈旧提交机制即使名字相近，也不能按文本一并清除。

必须保留最新内容生效、手动/信任 intent 在合并及读取失败后不丢、同 digest 的真实项目范围漂移修复，以及正常模型事件与自事件防循环。仍保留 PFI gate、ownership/原子状态、路径安全、trust/dispose 和 VCS 只读边界。

不恢复 Go registry 查询、探测构建文件、轮询语言状态或引入可选 Go adapter；不改变 Desktop/manifest 业务、插件 ID、数据 schema、GoLand 产品范围、版本、工具链、CI/Release 或用户配置。

## 3. 剩余项与文件清单

Kotlin 简写路径省略 `integrations/goland/src/main/kotlin/com/reqws/goland/`；先从 S1 交接扣除已经完成项，不重复删除/重写。

| 入口 | S2 的清理 | 保留或退出判据 |
|---|---|---|
| `project/ReqwsProjectService.kt` | 清理只服务 Go 补偿的事件分类、后续刷新策略和残留订阅状态。 | 正常外部 model drift、信任/手动刷新、生命周期和有界防抖保持正确。 |
| `project/SyncReadRequestTracker.kt`、`sync/LatestWinsSyncCoordinator.kt` | 沿入队、意图合并、dequeue、读取失败、apply、提交闭环清理专属 follow-up policy。 | 不删除通用请求身份、latest-wins、dirty baseline、取消和防陈旧提交；相关签名/测试原子修改。 |
| adapter/applier 中 S1 留下的无效参数 | 删除无用途的签名与传递，不保留 always-true/false 兼容参数。 | 若改变 S1 被测入口、成功提交或失败路径，立即加入对应 S1 回归。 |
| `projectmodel/ReqwsProjectModelMutationGuard.kt` / roots notifier 残留 | 仅核对实际受影响内容；删除无通用用途的补偿残留。 | 正常模型 mutation guard 仍有用途则保留；保留额外 notifier 必须有普通文件场景和测试证明，不以 Go 滞后为理由。 |
| `diagnostics/ReqwsSyncTrace.kt` 与共享 trace helper | 删除无用途的 registry-only 事件、枚举和字段，更新引用及测试。 | 通用事件、日志脱敏、trace 不影响业务的规则不变；共享字段有消费者就保留，不随意重编号或改通用 schema。 |
| `integrations/goland/src/main/resources/META-INF/plugin.xml` | 无 Go API 引用后移除 `org.jetbrains.plugins.go` 依赖。 | 保留 GoLand 产品限制及平台/VCS 依赖、plugin ID/版本；不扩大 IDE 支持范围。 |
| `integrations/goland/build.gradle.kts` | 清理 `bundledPlugin("org.jetbrains.plugins.go")`，收紧已有 symbol gate 及其自检。 | 最终装配能编译/执行必要平台测试；若有构建/测试专属依赖确实必须保留，按第 5 节记清证据和范围，不恢复生产 Go API。 |
| 对应 service/tracker/coordinator/trace/UI 测试 | 删除已取消的 Go-only 期望，保留通用并发/恢复回归并使用通用失败注入。 | 未在本阶段运行的有效用例仍留在完整套件，不禁用或批量删除。 |
| 当前行为文档与最近索引 | 清理已过时的 Go gate、补偿、依赖与排障说明，写明新的成功语义及已知行为差异。 | 保留历史结论与截图；具体摘要台账已由 Git 历史承载，不恢复输入 digest 清单；没有最终证据不写 GO。 |

## 4. 实施顺序

**先界定状态用途。** 基于 S1 剩余项一次性核对每个 notification/follow-up 字段的触发源与消费者，区分 Go 补偿和真实通用 drift。只清理剩余死路径，不因为状态机所在文件大就扩大成全面重构。

**再原子收拢调用闭环。** 改事件来源时同步改 request tracker、candidate 合并、dequeue、失败恢复及测试。普通外部 roots event 不能一概忽略，自己的受管变更也不能不断触发重放。若保留一个通用通知/后续验证路径，说明它保护哪个目录范围契约及为什么正常提交不足，不再携带语言检测或旧 Go 专用计数要求。

**然后处理装配与观测。** 去掉无用途依赖、共享 trace 残留及直接 UI 说明，更新原有生产符号门禁，不新建第二套扫描框架。用户文案变化遵循现有 i18n；没有变化就不重新确认翻译基线。

**维护已落实的版本与证据规范。** Wrapper 已移除 `distributionSha256Sum`，仍固定 Gradle 9.3.0 和官方 HTTPS 地址；S2 不重新加入或迁移该值。源码输入哈希清单已删除，文档不再粘贴过程哈希；使用 Git commit/diff 和 CI/工件入口交接。Release 校验、现有 Wrapper JAR 验证、锁文件 integrity 和运行时 manifest digest 保留。该准备工作不新增 S2 功能回归矩阵。

**最后收尾文档与局部回归。** 清单完成后在同一 S2 结果上运行第 6 节实际适用项，修复直接失败，准备 V。现有源文件若被删除，当前说明中的引用要改为固定历史提交链接或移除，避免文档链接指向已不存在的生产文件。

## 5. 依赖与 symbol gate 的具体边界

`com.intellij.modules.goland` 是现有产品范围的一部分，不随语言 API 依赖一起删除；GoLand 作为构建/验证目标不变。删除 descriptor 的 `org.jetbrains.plugins.go` 及 Gradle 显式 Go 插件依赖后，在最终依赖配置下验证生产、全部测试源码和一个受影响真实平台主路径，不能只证明纯 Kotlin helper 测试通过。

若编译或测试发现依赖问题，查明是否有遗漏的生产 Go API、测试 fixture 的语言依赖，或真实的构建/测试装配约束。优先移除前两者；最后一种只有在无生产 Go API/运行时硬依赖且理由可复核时，才按总方案限定为构建/测试配置并记录。环境下载失败记为环境阻塞，不推导为必须恢复 Go 业务依赖，也不能在无法验证时宣称装配通过。

复用 `verifyForbiddenProductionSymbols` 拒绝 production source/bytecode 的 Go API 引用，例如 `com.goide` / `com/goide`、`VgoModulesRegistry`，保留现有 VCS writer、外部进程及私有 API 禁令。更新原 scanner 自检，确认新旧禁止项有效；文档、历史数据、对照 fixture 不是生产执行路径，不能用无差别全文 grep 误杀它们。此扫描按阶段集中执行，不是每个用例后重复的人工任务。

## 6. S2 最小回归

### 6.1 按实际改动选择

| 本阶段项 | 触发条件与必要断言 | 对应总用例 / 优先层 |
|---|---|---|
| S2-R1 内容与 intent | 修改 request/candidate 合并、follow-up 或触发类型时必做：最新内容获选；manual/trust intent 在后到 automatic/读取失败后保留；不错误使用旧 snapshot 或 same-digest NoOp。 | T-04；tracker/coordinator/service 对应方法。 |
| S2-R2 漂移与自事件 | 修改 roots 监听、guard 或调度时必做：真实同 digest 范围漂移可修复；插件自身事件不循环；正常后到事件得到有界处理。 | T-04；service/mutation guard。 |
| S2-R3 生命周期 | 修改 owner scope、监听注册/关闭、失败提交或异步任务时必做：取消按原实例传播；dispose 后不写入/提交；相关恢复不丢。 | T-04/T-05 受影响部分；独立取消/竞争分支分别覆盖。 |
| S2-R4 依赖与清理 | 本任务装配/门禁变化必做：生产/测试源码可编译；Go 专属路径/依赖/参数无无用残留；原 symbol gate 与配置/结构校验通过。 | T-06；Gradle 与原 scanner。 |
| S2-R5 共享消费者 | 仅在 trace/UI/resource 消费分支被改时：无遗留 Go 分支，真实 PFI/missing/VCS 语义、日志脱敏和通用状态正确。 | T-03/T-05/T-06 相关部分；直接消费者测试。 |
| S2-X 交叉主路径 | 改到 S1 被测入口、错误/成功发布、fixture 或运行依赖时必做：补受影响 S1 方法。依赖变更至少跑一个使用真实平台 adapter 的普通仓库主路径；若影响失败/恢复，再补对应负向用例。 | S1-R1～R5 的受影响子集，不是重跑整个 S1。 |

表中条件项没被改动时记录“本阶段不适用/延后 V”，不将未执行计为 PASS；不得因条件表而略去实际受到影响的风险。多个表项由同一已有用例覆盖时只选一次，不设测试数量上限，也不复制相同测试。

候选类为 `ReqwsProjectServiceTest`、`SyncReadRequestTrackerTest`、`LatestWinsSyncCoordinatorTest`、`ReqwsProjectModelMutationGuardTest`；trace/UI 被改时再从其对应类选方法。以实施时源码确认真实测试名称，不将这些类全部默认加入命令。

### 6.2 运行方式

优先以 Gradle `test --tests` 指定真实方法/类，使用已有可控时钟和 barrier，不通过长时间 sleep/idle 代替状态机断言。无法可靠隔离方法时退到直接受影响的整类，例如确实修改了请求跟踪器时可使用：

```bash
# 从仓库根目录开始；仅是跟踪器类级回归示例，不覆盖全部 S2 条件项。
cd integrations/goland
./gradlew test --tests 'com.reqws.goland.project.SyncReadRequestTrackerTest'

# S2 装配收尾：利用正常任务依赖，不跳过必要构建任务。
./gradlew verifyForbiddenProductionSymbols \
  verifyPluginProjectConfiguration verifyPluginStructure
```

把其他实际适用方法及 S2-X 平台主路径追加到选例集合；以上命令不意味着已经运行，也不保证当前 checkout 的任务配置未变化。正常 test 任务应覆盖生产与测试源码编译，若实际任务依赖未覆盖则补相应编译任务，不对同一结果重复 clean/build。

零用例命中、全部跳过不是通过；记录真实选择器、数量、结果位置与有效输入。S1 未受影响的通过记录只作为阶段输入；运行依赖变化后的平台主路径必须在 S2 配置下验证，不能用 S1 旧结果代替。详细过滤与证据规则见[总测试方案](../testing/test-plan.md#4-分阶段执行与最小回归)。

本任务不默认再运行 S1 全套、未改的 parser/VCS/持久化全矩阵、完整 Verifier、GUI 或 Desktop 全业务。需要的结构/符号校验可以正常生成工件，但不因此安装、发布或额外跑完整 GUI。修改文档后在完整 checkout 中执行 `npm run docs:check`、`git diff --check`；远端 CI 不因局部策略而跳过。

## 7. 完成条件与交给 V 的信息

剩余清理项必须闭合：没有无用 Go 生产依赖/检测/门禁/补偿等待；保留的 notifier/follow-up 有明确通用职责及对应测试；通用安全/恢复测试仍在套件；本阶段实际影响集合通过且没有已知直接缺陷；当前文档正确区分目标、实际行为和尚未完成的最终验收。

未修改的 Desktop/shared、未运行的 GUI/完整矩阵分别如实记录，不当作已通过。依赖装配、源码编译或必要回归受阻时，不宣称 S2 完成；独立文档工作仍可完成。

```text
阶段：S2
接收的 S1 基线 / 当前基线与实际差异：...
已清理项；保留的通用 notifier/follow-up 及理由（如有）：...
实际依赖结论 / symbol gate、结构检查结果：...
S2-Rx 与 S2-X → 真实选择器 → 执行/失败/跳过数 → 结果位置：...
未运行项与原因：...
V 需要的候选、工件和 GUI 条件：...
是否修改 roots 订阅或关闭恢复路径（决定 V 是否补一次重开）：...
结论：S2 必要回归通过 / 未完成；最终验收尚未执行
```

## 8. 本轮实施记录（2026-09-18）

本节保留 S2 实施与验证时的阶段事实。S2 随后已提交为 `70e566dfd69403ca57631af3025c68790ab15745`；下文“未提交工作树差异”和“V 未执行”描述当时输入与交接。组合候选的最终阶段证据另见[V 验收记录](../testing/acceptance-2026-09-19.md)，不把阶段结果当作完整验收。

- 接收基线：S1 提交 `8d2561a6b6492f437bad7709b591f72ee8ee3c2d`；本轮候选为该提交加 S2 未提交工作树差异。S1 的 108 项通过记录属于原阶段，不能替代当前依赖和调度配置下的验证。
- 已落实代码：清理 `PROJECT_MODEL_FOLLOW_UP`、origin digest / event epoch 和 verify-only 合并/传播，保留普通 `PROJECT_MODEL_CHANGE` 范围漂移修复；清理共享 registry trace，删除 descriptor/Gradle 显式 Go 依赖，并扩展既有生产符号门禁及自检。
- 通用保护：保留请求身份、generation、latest-wins、manual/trust intent、读取失败恢复、dirty baseline、PFI、trust/dispose 和 VCS 只读；继续用 mutation guard 抑制自身模型事件并防抖外部变更，不增加额外 notifier。
- 编译与验证状态：生产与全部测试源码编译通过；S2-R1～R5 实际适用集合及 S2-X 平台主路径共执行 85 项，失败 0、错误 0、跳过 0。34 个选择器全部命中，包含 3 个类级选择器与 31 个方法级选择器；symbol gate 自检/扫描及配置/结构检查通过。详情见下表与精确命令。
- V 交接：roots 事件分类与调度已纳入本轮修改范围，最终验收需按 V 条件增加一次同候选关闭重开；V 所需安装工件绑定和 GUI 验证环境待实际建立，不安装或重启真实 IDE。
- 未执行范围：V 的完整保留插件回归、双版本兼容矩阵及必要 GUI；Desktop/shared 未修改，不重复执行 Desktop 全业务。S2 必要回归已通过，不产生完整功能 GO。

### 验证环境、选择器与结果

macOS arm64；JDK 21.0.12.1（现有 Homebrew JDK），Gradle Wrapper 9.3.0，GoLand 2026.1.3 测试平台，与 S1 相同。以下命令在仓库根目录执行；所有 `--tests` 紧跟 `test` 并置于后续任务之前，含空格的方法名整体引用。

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./integrations/goland/gradlew -p integrations/goland test \
  --tests 'com.reqws.goland.project.SyncReadRequestTrackerTest' \
  --tests 'com.reqws.goland.sync.LatestWinsSyncCoordinatorTest' \
  --tests 'com.reqws.goland.diagnostics.ReqwsSyncTraceTest' \
  --tests 'com.reqws.goland.project.ReqwsProjectServiceTest.testSafeModeTrustTransitionForcesSameDigestProjectModelReplay' \
  --tests 'com.reqws.goland.project.ReqwsProjectServiceTest.testAutomaticTrustedRefreshBeforePollStillForcesSameDigestProjectModelReplay' \
  --tests 'com.reqws.goland.project.ReqwsProjectServiceTest.testLatestRefreshPropagatesProcessCancellationAndRestoresManualSync' \
  --tests 'com.reqws.goland.project.ReqwsProjectServiceTest.testLatestRefreshPropagatesCoroutineCancellationAndRestoresManualSync' \
  --tests 'com.reqws.goland.project.ReqwsProjectServiceTest.testTracePreservesProcessCancellationAndManualRecoveryAcrossReadAndVcsSpans' \
  --tests 'com.reqws.goland.project.ReqwsProjectServiceTest.testTracePreservesCoroutineCancellationAndManualRecoveryAcrossReadAndVcsSpans' \
  --tests 'com.reqws.goland.project.ReqwsProjectServiceTest.testExternalProjectModelChangeForcesSameDigestReplay' \
  --tests 'com.reqws.goland.project.ReqwsProjectServiceTest.testGuardedOwnedProjectModelChangeIsIgnored' \
  --tests 'com.reqws.goland.project.ReqwsProjectServiceTest.testTraceRecordsGuardedWorkspaceAndOrdinaryRootsWithoutSelfReplay' \
  --tests 'com.reqws.goland.project.ReqwsProjectServiceTest.testProjectModelChangeWithoutValidSnapshotIsIgnored' \
  --tests 'com.reqws.goland.project.ReqwsProjectServiceTest.testProjectModelChangeBurstDebouncesToOneForcedReplay' \
  --tests 'com.reqws.goland.project.ReqwsProjectServiceTest.testTraceSeparatesEightRootCallbacksFromOneForcedReplay' \
  --tests 'com.reqws.goland.project.ReqwsProjectServiceTest.testDisposeCancelsProjectModelDebounceAndClosesRegistration' \
  --tests 'com.reqws.goland.project.ReqwsProjectServiceTest.testLateOrdinaryProjectModelCallbackRacingDisposeIsDroppedWithoutFailure' \
  --tests 'com.reqws.goland.project.ReqwsProjectServiceTest.testThrowingProjectModelRegistrationCloseDoesNotSkipLaterCleanup' \
  --tests 'com.reqws.goland.project.ReqwsProjectServiceTest.testDisposeWinningBeforeCandidateCommitPreventsDurableDigestAdvance' \
  --tests 'com.reqws.goland.project.ReqwsProjectServiceTest.testProjectionFailureInvalidatesLiveProofAcrossALaterManifestReadError' \
  --tests 'com.reqws.goland.project.ReqwsProjectServiceTest.testQueuedReadFailureCannotResurrectProofInvalidatedByAnOlderApplyFailure' \
  --tests 'com.reqws.goland.project.ReqwsProjectServiceTest.testApplyCancellationInvalidatesLiveProofUntilARecoveryProjectionSucceeds' \
  --tests 'com.reqws.goland.projectmodel.ReqwsProjectModelAdapterTest.testManifestProjectionIsIndependentOfGoModContentsAndLocation' \
  --tests 'com.reqws.goland.projectmodel.ReqwsProjectModelAdapterTest.testAddsRemovesAndReaddsTargetAndPersistentMarkerTogether' \
  --tests 'com.reqws.goland.projectmodel.ReqwsProjectModelAdapterTest.testVerifiesLiveProjectionAfterEveryApplyIncludingModelNoOp' \
  --tests 'com.reqws.goland.projectmodel.ReqwsProjectModelAdapterTest.testPropagatesLiveFileIndexFailureAndRecoversWithTheSameSnapshot' \
  --tests 'com.reqws.goland.projectmodel.ReqwsProjectModelAdapterTest.testTraceSpansCoverRealModelAndPfiAndKeepTheApplyIdentity' \
  --tests 'com.reqws.goland.projectmodel.ReqwsProjectModelAdapterTest.testTracePreservesPfiPlatformCancellation' \
  --tests 'com.reqws.goland.projectmodel.ReqwsProjectModelAdapterTest.testTracePreservesPfiCoroutineCancellation' \
  --tests 'com.reqws.goland.project.ReqwsProjectionApplierTest.file index failure dirties the same digest until a successful retry commits it' \
  --tests 'com.reqws.goland.project.ReqwsProjectServiceTest.testLateOrdinaryEventForcesOneReplayWithoutLooping' \
  --tests 'com.reqws.goland.project.ReqwsProjectServiceTest.testOverlappingOrdinaryEventsEachForceReconciliation' \
  --tests 'com.reqws.goland.project.ReqwsProjectServiceTest.testFailedOrdinaryEventReadPreservesReconciliationForANewerManifestDigest' \
  --tests 'com.reqws.goland.project.ReqwsProjectServiceTest.testFailedOrdinaryEventReadPreservesReconciliationForTheSameManifestDigest' \
  verifyForbiddenProductionSymbols verifyPluginProjectConfiguration verifyPluginStructure
```

构建用时 28 秒，共 18 个任务，17 个实际执行、1 个 UP-TO-DATE（`generateManifest`）；`compileKotlin`、`compileTestKotlin`、`test` 与各验证任务均实际执行。本轮最新源码及测试修改均早于这些任务，验证后仅补充文档证据。首次尝试将 `--tests` 参数放在 `verifyPluginStructure` 后导致命令配置失败、测试未执行；纠正参数顺序后的上述运行成功，未把首次零执行计为通过。

| 测试类（完整包名前缀 `com.reqws.goland.`） | 选择方式 | 执行数 | 覆盖 |
|---|---|---:|---|
| `project.SyncReadRequestTrackerTest` | 完整类 | 19 | S2-R1/R3：请求/intent 合并、读取失败恢复与请求身份。 |
| `sync.LatestWinsSyncCoordinatorTest` | 完整类 | 30 | S2-R1/R3：latest-wins、manual/trust intent、同 digest 重验、dirty baseline 与取消/关闭。 |
| `diagnostics.ReqwsSyncTraceTest` | 完整类 | 5 | S2-R5：通用 trace、固定枚举/数字与脱敏、诊断不改变业务。 |
| `project.ReqwsProjectServiceTest` | 上述 23 个精确方法 | 23 | S2-R1/R2/R3/R5：外部漂移、自事件抑制、防抖、后到/重叠事件、失败后新旧 digest 恢复及生命周期。 |
| `projectmodel.ReqwsProjectModelAdapterTest` | 上述 7 个精确方法 | 7 | S2-X/R5：新依赖配置下真实平台语言无关主路径、增删重加、PFI/no-op、取消与 trace。 |
| `project.ReqwsProjectionApplierTest` | 上述 1 个精确方法 | 1 | S2-X：注入 PFI 失败，验证同 digest 保持 dirty，修复重试后才提交；真实平台 PFI 主路径由 adapter 用例覆盖。 |
| 合计 | 34 个选择器全部命中 | 85 | 失败 0、错误 0、跳过 0。 |

结果位置为 `integrations/goland/build/test-results/test/TEST-<完整类名>.xml` 与 `integrations/goland/build/reports/tests/test/index.html`（本地生成输出，不提交）。跟踪器、协调器的共享签名/合并分支及 trace 消费者变化较广，采用直接受影响整类；service、adapter 和 applier 使用上述方法集合。UI/resource 在 S2 未改，不重复其 S1 回归；通用 UI 测试仍保留到 V。

S2-R4：扩展后的 `verifyForbiddenProductionSymbols` 自检与扫描通过，覆盖 45 个 `src/main` 文件和装配 JAR 的 311 个 class；`verifyPluginProjectConfiguration`、`verifyPluginStructure` 通过。`build/libs/reqws-goland-0.1.0-base.jar`、`build/libs/reqws-goland-0.1.0-instrumented.jar` 和 `build/libs/reqws-goland-0.1.0.jar`（均相对 `integrations/goland/`）中的 descriptor 已核对：ID 为 `com.reqws.workspace`，版本为 `0.1.0`，只声明 platform/goland/vcs 依赖；没有恢复 `org.jetbrains.plugins.go` 或增加构建/测试替代依赖。该结果证明本轮局部装配与平台回归，双版本 Verifier 仍待 V。

文档检查：`npm run docs:check` 与 `git diff --check` 通过。本轮没有 Desktop/shared 或用户文案变化；未运行 Desktop 全业务、完整保留插件套件、双版本 Verifier、真实 GUI，也未安装或重启 IDE。S2 必要回归通过，下一步仍须对最终组合候选执行 V，不能把两个阶段测试数量相加当作完整验收。

下一步只执行[最终系统回归与验收](../testing/final-acceptance.md)，不要将 S1/S2 结果简单相加写成 GO；也不要为任务文档数量额外创建 PR、安装或重复完整验证。
