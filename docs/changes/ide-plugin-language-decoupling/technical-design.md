---
title: IDE 插件语言解耦技术方案
type: technical-design
status: active
updated: 2026-09-18
---

# IDE 插件语言解耦技术方案

本方案移除 ReqWS 同步对 Go 构建文件和 Go Modules registry 的依赖，保留语言无关的 Git 仓库视图、受管项目范围及安全恢复能力。

## 1. 状态、依据与范围

S1 核心实现已删除 Go 同步成功门禁、专属 notifier 与直接错误/UI 分支，并移除无效参数；本轮必要回归已通过，实际记录见 [S1 任务](tasks/s1-core-sync-decoupling.md#8-本轮实施记录2026-09-18)。S2 调度/依赖及共享追踪核心清理已落实，必要回归已通过，见 [S2 实施记录](tasks/s2-scheduling-dependency-cleanup.md#8-本轮实施记录2026-09-18)。V 整体验收尚未执行。Gradle Wrapper 仍为 9.3.0，官方 HTTPS URL 不变且不设置 `distributionSha256Sum`；本轮不升级依赖，不变更 CI/Release，也不安装、重启或发布插件。

源码核对基线是 [main@409b30e573d47348618620bbc0a52c0dd0710954](https://github.com/fredgnr/reqws-desktop/tree/409b30e573d47348618620bbc0a52c0dd0710954)。实施时按实际基线核对引用；以下路径和类名是清理入口，不是允许整体删除文件的名单。

产品边界以[IDE 插件开发与测试规范](../../standards/ide-plugin-development-testing.md)为准。旧 [GoLand 需求](../goland-plugin-support/requirements.md)与[设计](../goland-plugin-support/technical-design.md)中 Go registry 成功门禁、Go package 配置可用性及专属通知补偿被本方案替代；其他通用契约继续有效。

## 2. 目标与非目标

| 编号 | 目标契约 |
|---|---|
| LD-01 | manifest 决定活动 Git 仓库集合；不探测 `go.mod` 或其他语言构建文件来决定成员、同步或状态。 |
| LD-02 | 保留现有 Workspace Model/excludes 策略和公开 ProjectFileIndex 验证；不把目录归属正确扩大为语言分析已完成。 |
| LD-03 | 删除 Go registry 读取、补偿、等待、错误链；ReqWS 状态不因语言服务未就绪而失败。 |
| LD-04 | 不新增 Git/VCS 写权限，不损坏用户目录/配置；保留 trust、路径、ownership、原子状态、取消和 dispose 保护。 |
| LD-05 | 去掉不再需要的 Go API 依赖和专用观测状态；保持现有 GoLand 产品范围、插件身份和兼容矩阵。 |
| LD-06 | 测试按自有契约收敛，保留必要自动化与 GUI 集成，不执行用户项目的语言工具链验收。 |

非目标：重构 Desktop workspace 生命周期；自动写 Git mappings；改变独立 clone/gitfile/worktree 支持；改为全新 Content Root 策略；迁移 manifest 或 ownership schema；支持其他 IDE/系统；引入语言适配框架、可选 Go 模块或开关；升级工具链、版本或发布资产。

## 3. 历史耦合与当前边界

[历史基线的 ReqwsGoModulesSynchronizer](https://github.com/fredgnr/reqws-desktop/blob/409b30e573d47348618620bbc0a52c0dd0710954/integrations/goland/src/main/kotlin/com/reqws/goland/projectmodel/ReqwsGoModulesSynchronizer.kt)在活动路径中筛选顶层普通 `go.mod`，读取 `VgoModulesRegistry`，比较活动与 excluded module root；不一致时额外发布 ordinary roots event，再有界等待，最终可能抛出 `GO_MODULES_REGISTRY_NOT_CONVERGED`。筛选当时只用于附加 Go 检查，不是 manifest 成员选择器；该文件已在 S1 删除。

[ReqwsProjectModelAdapter](../../../integrations/goland/src/main/kotlin/com/reqws/goland/projectmodel/ReqwsProjectModelAdapter.kt)历史实现先提交受管 excludes、验证公开文件范围，再调用 `goModulesProjection.synchronize()`；[ReqwsProjectionApplier](../../../integrations/goland/src/main/kotlin/com/reqws/goland/project/ReqwsProjectionApplier.kt)历史实现把 Go 失败与 PFI 失败都映射到 `PROJECT_CONTENT_NOT_CONVERGED`，但区分诊断 field。这条历史链曾使 Go 状态阻塞 ReqWS clean digest。S1 已删除 Go 调用与映射，保留模型/PFI 检查及其真实失败。

[ReqwsProjectService](../../../integrations/goland/src/main/kotlin/com/reqws/goland/project/ReqwsProjectService.kt)、[协调器](../../../integrations/goland/src/main/kotlin/com/reqws/goland/sync/LatestWinsSyncCoordinator.kt)及[请求跟踪器](../../../integrations/goland/src/main/kotlin/com/reqws/goland/project/SyncReadRequestTracker.kt)已清理专属 follow-up、origin digest / event epoch 和 verify-only 传播；S1 已原子移除 `allowRootsChangeNotification`。当前保留普通 `PROJECT_MODEL_CHANGE`、manual/trust intent 和 latest-wins；registry 专属追踪及显式 Go 依赖同步清理，编译和必要回归已通过，实际证据见 S2 阶段记录。

## 4. 目标执行链与状态

```text
只读读取并验证 manifest
→ 取得当前仓库存在性/路径身份，按现有规则诊断
→ 计算受管目录范围与 ownership 变更
→ trust/dispose gate + 原子状态 + Workspace Model 正常提交
→ 验证 ProjectFileIndex 的活动/排除边界
→ 保留最终生命周期检查，由协调器接受结果并提交 digest
→ 发布仓库视图；VCS 继续按现有独立只读诊断流程工作
```

不得引入 `go.mod`、registry、SDK、DumbService 全量索引等待或语言进程。`Synced` 只描述 ReqWS 负责的同步；`Active` 不表达 Go module 已发现。保留现有缺失仓库、非法输入、VCS 诊断及 Safe Mode 的独立状态语义，不趁本次解耦重写它们。

PFI 仍是目录范围的有效结果检查；`LIVE_FILE_INDEX_NOT_CONVERGED` 仍映射为 `PROJECT_CONTENT_NOT_CONVERGED` 和 `PROJECT_FILE_INDEX`。只有这一检查与其他既有通用 gate 成功，才能提交 clean baseline；不得通过直接标记成功或吞异常完成“解耦”。

## 5. 清理归属与独立任务入口

逐文件修改、实施步骤和最小回归已拆入独立任务文件，本节只维护工作归属，避免总方案与任务正文各保存一份细节清单。

| 清理域 | 归属 | 边界 |
|---|---|---|
| Go synchronizer / `go.mod` / registry / 轮询与专用 notifier | [S1](tasks/s1-core-sync-decoupling.md) | 删除主成功门禁及直接引用，保留必要的正常平台通知。 |
| model adapter / applier / Go error field / 主链直接 trace 与 UI | [S1](tasks/s1-core-sync-decoupling.md) | 签名、调用者、实现与测试一起修改，保留 PFI 和真实失败。 |
| 剩余 service / tracker / coordinator follow-up 策略 | [S2](tasks/s2-scheduling-dependency-cleanup.md) | 只删 Go 补偿状态，保留通用 intent、latest-wins、drift、取消和防循环；原子耦合部分可提前在 S1 完成。 |
| 共享 trace 残留、descriptor / Gradle 依赖、原 symbol gate | [S2](tasks/s2-scheduling-dependency-cleanup.md) | 无 Go 生产依赖或恒真替身，不扩大产品/CI 范围。 |
| 当前说明与旧用例清理 | 各任务随实际改动维护，S2 收尾 | 通用回归保留；历史结论不伪造，过程台账可删除并链接 Git 历史，不再保留源码输入 digest 清单。 |
| 完整插件回归、兼容矩阵与必要 GUI | [V](testing/final-acceptance.md) | 在最终组合代码上统一验证，不作为第三个开发任务。 |

未列出的命中按职责判断，不能仅依据 `GoLand`、`module`、`root` 或 `go` 文本全局替换；类和目录改名不在范围内。平台 moduleName 不等于 Go module。所有任务仍须满足下文共同通知、依赖与安全决策。

## 6. roots event 与 follow-up 决策

默认方案是删除同文件中仅由 Go synchronizer 调用的 `ReqwsProjectRootsChangeNotifier` 和 `PlatformReqwsProjectRootsChangeNotifier`。随之删除只表达“允许 Go 补偿通知”的 Boolean 参数及专属 verify-only/follow-up 状态，不保留永远未触发的通道。

正常 `WorkspaceModel.update()` 和它产生的必要模型通知必须继续工作。保留用于外部目录范围漂移的监听、受管变更 mutation guard 与有界防抖；不因删除 Go 补偿而把所有 ordinary event 当作无关，也不因任何 IDE event 都强制反复 apply。

实施前核对 notifier 的全部生产调用点。只有存在独立的语言无关场景，且最小平台 fixture 证明正常模型提交不足以满足该场景，才将 notifier 移入独立平台 adapter 并保留；记录场景、调用条件和为何不能依赖正常事件。此保留项不允许读取 Go 状态或轮询语言结果。不能把旧 Go registry 滞后本身作为保留依据，也不能用 catch-and-ignore、无条件额外通知或私有 API 替代。

S2 删除 `PROJECT_MODEL_FOLLOW_UP` 及其 lineage；未被 mutation guard 拦截且存在有效 snapshot 的项目范围事件，经同一有界防抖后提交普通 `PROJECT_MODEL_CHANGE`。后到事件重新读取最新 manifest，不消费旧 Go 补偿计数或 verify-only policy。保留的通用事件处理必须证明 latest 内容生效、手动/信任 intent 不丢、同 digest 的真实范围漂移可恢复、ReqWS 自己的正常事件不会形成循环；不要求维持旧 Go 专用事件次数和延迟常量。

## 7. 依赖与构建门禁

S2 已从 `plugin.xml` 删除 `org.jetbrains.plugins.go` 依赖；保留 `com.intellij.modules.goland`、platform/VCS 以及现有 GoLand 构建 target。产品限制与语言 API 依赖是不同问题，本变更不扩大安装范围。

Gradle 的 `bundledPlugin("org.jetbrains.plugins.go")` 已同步删除，未增加构建/测试专属替代依赖；当前配置已通过 S2 编译、真实平台主路径与结构验证；261/262 Verifier 仍须在 V 分别确认。若测试/产品装配确有依赖，先查明原因并将必要部分限定在构建/测试配置，记录证据，不通过恢复生产 Go API 依赖掩盖问题，也不把工具链假设写成已经验证的结论。GoLand 产品自带 Go 能力不等于 ReqWS 可以继续调用它。

现有 `verifyForbiddenProductionSymbols` 任务已扩展，在 production source/bytecode 上拒绝 `com.goide` / `com/goide`、`VgoModulesRegistry` 等 Go API 引用；继续保留 VCS writer、外部进程及既有私有 API 禁令。调整 scanner 的自检哨兵，使新增禁止项和原安全项均有覆盖，不新增另一套重复扫描框架。对构建文件探测的语义检查只针对可执行生产路径；文档、历史证据及语言无关对照 fixture 不应被简单文本匹配误杀。

依据：[JetBrains Project Model](https://plugins.jetbrains.com/docs/intellij/project-model.html)定义通用目录模型和 PFI；[GoLand Plugin Development](https://plugins.jetbrains.com/docs/intellij/goland.html)区分 Go API 依赖与 GoLand-only 产品依赖。以上依据核对于 2026-09-13，具体构建可用性仍需候选实测。

### 7.1 已落实的版本与证据简化

Wrapper 仅以明确的 Gradle 版本和官方 HTTPS 地址选择 distribution，不设置 `distributionSha256Sum`，不以其他配置或清单补回预期摘要。这是版本管理选择，不等于下载字节已通过 checksum 校验。原 Release 校验、Wrapper JAR 验证和运行时 manifest digest 不受影响。

源码一致性使用 Git commit/tree/diff；报告保留范围、命令、结果、未运行理由及 CI/产物入口。逐文件源码清单和具体测试摘要不再入库，历史明细由 Git 查询。S2 只维护该规范、不重复执行这次清理；V 不增加生成输入指纹的条件。见[证据与版本规范](../../standards/ide-plugin-development-testing.md#7-git测试证据与-gradle-版本管理)。

## 8. 子任务评估、实施顺序与阶段回归

### 8.1 拆分结果与文档分工

保持两个串行开发子任务加一个最终验收阶段。取消 Go 成功条件与清理补偿调度的风险不同，适合分别完成和回归；不按类、错误码、依赖或文档再细分。

```text
S1 核心同步语义解耦（实现 + 自己的最小回归）
→ S2 补偿调度与依赖清理（实现 + 自己的最小回归）
→ V 最终系统回归与验收（完整插件 + 兼容 + 必要 GUI）
```

| 执行入口 | 本文件中的任务定位 |
|---|---|
| [任务索引](tasks/README.md) | 顺序、交接、合并条件及文档维护分工。 |
| [S1 独立任务文档](tasks/s1-core-sync-decoupling.md) | 前置输入、逐文件清理、实施步骤、S1-R1～R5 最小回归与退出条件。 |
| [S2 独立任务文档](tasks/s2-scheduling-dependency-cleanup.md) | 接收 S1 结果、调度/依赖收尾、S2-R1～R5 和 S2-X 交叉回归与退出条件。 |
| [V 独立验收文档](testing/final-acceptance.md) | 最终候选命令、完整保留用例、兼容矩阵、唯一必要 GUI 链及结果判定。 |

每个任务将实施和最小回归放在同一文件，直接阅读当前任务即可组织工作，不要求开发者再从总测试方案拼接步骤。本总方案保留共同技术契约；[总测试方案](testing/test-plan.md)保留 fixture、T-01～T-07 和过滤/证据规则；不要在多个文件复制完整任务清单或最终 GUI 步骤。

### 8.2 原子修改、交叉回归与合并

S1/S2 默认同一分支串行，不并发修改共享 adapter/service/coordinator；一个最终 PR 评审整体实现，不因任务文档数量增加 commit/PR、安装或远端授权。

删除类型、签名、枚举必须连同调用/测试原子完成。S1 因此提前修改 S2 的调度项时，当步做对应回归并在交接标明；S2 先扣除已完成项，不重复实现。S2 修改 S1 入口、状态提交、fixture 或运行依赖时只补对应交叉测试，不默认为整个 S1 重跑，也不能忽略已知影响。

若没有可编译且可验证的中间边界，或 S1 已基本消除 S2 只剩零星收尾，可以合并实施；仍核对两份任务清单并执行 V，不增加临时 adapter、可选开关或恒真 stub 来维持拆分。任务文档是执行入口，不是强制独立交付单元。

### 8.3 最终验收与证据边界

阶段只运行生产/测试编译和必要方法/类；零用例命中、全跳过或不明缓存状态不能算通过。未在阶段执行的有效通用用例保留到 V；已知直接安全或并发影响不能拖到 V 才验证。具体选择器和交接字段由对应任务维护。

全部开发完成后在最终组合代码上统一执行 V，不用两阶段结果相加代替整体验收。最终代码/依赖/构建/工件改变后重建受影响证据；仅文档变化在核对功能输入未变后可复用相关功能结果并说明绑定。

现有 CI/Release 不变，中间 push 触发完整检查仍照常执行，不使用 skip 或修改 workflow 规避成本。测试范围仍限 ReqWS 自有契约，不恢复 Go/其他语言工具链或原生 Git 全功能验收。

## 9. 风险、兼容与回退

删除旧补偿后，曾被它缓解的 Go registry/运行配置滞后可能再次出现。新版本明确不保证语言模型在 ReqWS `Synced` 时同步就绪，也不要求逻辑移除使已有运行配置不可运行。应保留这个已知行为差异，不能承诺 Go 体验完全不变。

反之，活动仓库仍被错误排除、retained 仓库范围错误、用户条目破坏、同步循环或误报通用成功仍是阻塞缺陷，不能归为“不关心语言”。必要通知被误删时修复通用平台接入，不恢复 Go 硬门禁。

不改变 manifest schema、ownership 文件格式、插件 ID 或受管策略，因而本方案不设计数据迁移和自动清理用户 `.idea`。升级后沿既有启动重验逻辑建立当前会话的 live proof，不能把旧持久 digest 直接当作新投影已验证。需要回退时回退本次实现代码/插件工件，不删除 workspace 或用户配置；旧版本可能重新表现出旧 Go gate，这不改变新规范。

## 10. 完成标准

LD-01～LD-06 的相关用例有结果；生产路径无 Go API、构建文件探测、Go gate 或专属轮询；剩余 notifier/follow-up 有明确通用职责或已删除；现有安全和恢复回归不被削弱；无无用 Go 依赖/空实现；代码、规范、当前指南与实际检查一致。

当前 S1 已提交；S2 核心清理已落实、必要回归已通过，V 尚未执行。阶段结果不能代替上述整体完成标准，也不产生新的完整功能 GO。
