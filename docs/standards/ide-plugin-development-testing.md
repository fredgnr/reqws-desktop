---
title: IDE 插件开发与测试规范
type: governance
status: active
updated: 2026-09-18
---

# IDE 插件开发与测试规范

本规范把 ReqWS IDE 插件限定为语言无关的 Git 多仓库视图与必要的平台适配，明确开发职责、成功语义和按影响选择的测试边界。

## 1. 生效范围与实现状态

适用于 `integrations/goland/` 及后续获准开发的 IDE 适配，不代表当前已支持其他 IDE 或操作系统。本规范与[语言解耦技术方案](../changes/ide-plugin-language-decoupling/technical-design.md)定义新的开发和验收方向；规范、文档清理和 Gradle Wrapper 的版本管理调整已落实；S1 已删除 Go 主成功门禁及直接错误链，必要回归状态见 [S1 实施记录](../changes/ide-plugin-language-decoupling/tasks/s1-core-sync-decoupling.md#8-本轮实施记录2026-09-18)。S2 调度/依赖收尾和 V 最终验收尚未执行。

旧 GoLand 需求、设计、指南和测试材料中，把 `go.mod`、Go Modules registry、Go package 配置或 Go 工具链可用性作为 ReqWS 成功条件的条款被本规范替代。其余 manifest、安全、项目模型所有权、生命周期和 VCS 只读约束继续有效。旧版本源码与按次验证报告仍用于说明当时行为，不能据此要求新候选重新执行已移出范围的验收。

`active` 表示本规范可作为后续实现依据，不表示解耦实现、构建或 GUI 验收已经完成。实际交付状态必须另据候选代码及验证结果判断。

## 2. 产品与写入职责

| 对象 | 负责内容 | 不负责内容 |
|---|---|---|
| ReqWS Desktop | 仓库录入、独立 clone、分支与 workspace 生命周期、成员增删、物理隔离、manifest 写入、IDE 启动。 | 为每种语言配置 SDK、依赖、构建、测试或调试环境。 |
| IDE 插件 | 只读消费 manifest、展示仓库集合、自动/手动刷新、必要的受管项目范围适配、只读 Git 配置诊断。 | 重做 Desktop 业务、写 manifest、执行 Git 生命周期命令、写 VCS mappings、访问仓库 URL 或删除仓库。 |
| IDE / 语言插件 / 用户 | 语言模型、SDK、依赖解析、索引与代码分析、运行配置、编译、测试、调试以及用户维护的 Git 配置。 | 向 ReqWS 提供所有语言服务已完成的同步承诺。 |

manifest 是活动仓库成员关系的唯一业务来源；文件系统/Git 检查只能验证存在性、安全性和现有仓库契约，不能依据语言文件改变成员集合。保留仓库发现只服务于现有排除策略，不自动把磁盘上的额外仓库加入 manifest。

仓库里有无 `go.mod`、该文件是否有效、位于顶层还是子目录，均不得影响 ReqWS 的业务判定。也不得换成识别 `package.json`、`pom.xml` 等文件的通用语言检测框架。语言解耦不顺带改变独立 `.git`、gitfile/worktree、symlink 或 URL 的既有支持范围。

## 3. 插件实现约束

插件对业务数据只读，不等于对 IDE 模型完全零写入。保留 workspace-root Content Root 与现有受管 excludes 策略：仅在受信任且 project/service 存活时修改能证明归 ReqWS 所有的条目；用户条目、普通目录和不归插件所有的配置保持不变。继续维护 ownership、稳定目录句柄、原子持久化、恢复、路径 containment、取消传播及 dispose 保护。

不得在生产同步路径探测语言构建文件、查询 Go registry、等待语言服务、执行语言命令，或因语言状态失败而让 ReqWS 报同步失败。不要用 warning、开关、反射、可选依赖、空实现或永远成功的 stub 保留原 Go 检查链。

通过公开受支持的平台 API 正确提交目录模型变更，并保留必要的正常事件。删除“Go registry 不一致 → 额外 roots event → 轮询 → 报错”专属反馈链；不得为补偿它而改成每次刷新无条件额外通知。任何保留的额外通知都必须有独立、可复现的语言无关需求、调用点和最小测试，不能仅凭旧类名中有 `ProjectRoots` 就保留。

只使用项目声明的公开平台 API 基线；禁止 JetBrains `@Internal`、`@Experimental`、反射或私有 API。插件身份、GoLand 产品限制、现有兼容矩阵和发布流程不因这次解耦自动扩大。

## 4. 成功与失败语义

`Synced` 只表示：有效 manifest 已被消费，插件负责的仓库视图和受管项目范围已同步，且没有该范围内未处理的失败。`Active` 表示对应的存在仓库在活动集合及应有的项目内容范围内，不代表语言模块可用。

Workspace Model 与公开 `ProjectFileIndex` 的目录归属/排除结果仍需一致。检查 `isInContent`、`isExcluded` 是验证插件自己的适配结果，不要求等待 IDE 全部索引结束或代码语义分析完成。真实项目范围失败不得被当作“语言无关”而吞掉；缺失仓库、非法 manifest、ownership 冲突、Safe Mode 和取消仍按各自现有契约处理。

Git mapping 的缺失、冲突或 retained 提示仍走独立的只读诊断语义，不在本变更中重新定义。语言服务尚未完成，或用户项目编译/测试失败，不应改变 ReqWS 同步结果。

逻辑移除不是磁盘访问控制或代码执行禁令：不得要求磁盘上保留仓库的运行配置必然失效、无法执行；重新加入也不承诺运行配置立即恢复。

## 5. 按职责与影响测试

| 层级 | 必须验证 | 不扩大到 |
|---|---|---|
| Desktop | 本次确实涉及的 Git 封装、workspace 事务、启动和共享契约。 | 重新运行与插件文档或内部解耦无关的全部业务场景。 |
| 插件单元/平台 | manifest、同步协调、受管范围、错误和恢复、只读 VCS 分类、状态展示、用户配置保护。 | 证明 GoLand 原生 Git、Go Modules 或语言工具链整体正确。 |
| 必要 GUI 集成 | 真实 IDE 中插件加载、仓库增删重加、自动刷新和状态/范围一致。 | 每个动作之后重复引用查找、补全、go test、运行和调试。 |
| 构建与兼容 | 插件自身 Kotlin/Gradle 测试、结构/配置验证及已有 Plugin Verifier 矩阵。 | 把 IDE 兼容性等同于用户 Go 项目测试成功。 |

基础 fixture 使用本地 Git 仓库与普通文本文件，不需要 Go SDK、Go Modules、外部依赖下载或账号。可增加一个小型对照回归，证明改变 `go.mod` 不改变插件判定；不得因此建立多语言编译矩阵。

每个新增用例应能回答：覆盖哪个 ReqWS 自有契约、什么变化会让它失败、应在哪一层测试。同一风险优先由最低有效层的自动化回归覆盖，GUI 只补平台测试不能证明的集成环节。已有安全/并发缺陷回归不能因为旧 fixture 使用 Go 文件而整批删除；改成普通文件和语言无关失败注入。

源码/bytecode 的禁用 API 检查按阶段或最终候选集中在构建门禁执行，不要求每个 GUI 动作都人工重扫；生产代码变化后仍须覆盖新的有效输入。性能、详细 tracing、长时间 idle、sleep/wake 和多轮恢复只在改动影响或具体故障需要时扩展，不作为每次轻量变更的固定清单。

## 6. 命令、证据与停止条件

纯文档执行 `npm run docs:check`。需要分阶段开发时，子任务内只做生产/测试编译与直接受影响方法/类的必要回归；已知的取消、配置保护或并发影响必须当步验证，不能一概拖到最后。全需求完成后的最终候选统一执行 `npm run check:goland`、完整保留的插件用例和必要集成；中间子任务不自动等同于一个完整交付候选。共享 manifest 变化才增加两端契约检查。

按风险而非文件数量拆分，不为维持任务编号引入临时开关、空实现或破坏可编译性；缺少独立边界时合并实施。测试过滤必须命中实际用例，零执行/全跳过不能算通过。筛选不可靠时扩大到直接受影响的完整测试类，不通过禁用有效 case 缩小范围；未跑的通用用例保留到最终完整回归。

阶段与最终结果分别记录。最终检查后修改运行代码、依赖、构建或工件，要重新建立受影响证据；“最终统一一轮”不是禁止失败后的必要重跑。本次两步通过[独立任务文档](../changes/ide-plugin-language-decoupling/tasks/README.md)执行，每份就地维护实现与最小回归、完成及交接要求；[最终 V 文档](../changes/ide-plugin-language-decoupling/testing/final-acceptance.md)单独维护完整检查与 GUI，不多处复制清单。既有 CI/Release 工作流不在本规范提交中修改，中间 push 若触发完整 CI 仍正常执行，不用“局部回归”绕过必需构建或安全门禁。

“未运行”“环境阻塞”“非本次范围”“失败”“通过”必须分开记录。文档通过不等于实现通过；ZIP 构建、历史 GO、普通单测也不能替代新候选需要的 GUI 证据。真实 GUI 只记录源码 commit、插件版本、构建/安装工件入口、IDE build、必要环境和场景结果，不再默认要求用户项目 Go 版本或 SDK 矩阵。

出现 ReqWS 范围错误、越权写入、配置破坏或循环时阻塞候选；出现语言服务问题时先记录归因，只有能证明由插件违反通用平台契约导致时才纳入相关缺陷，不升级为 IDE/语言全套验收。保留正常平台事件不意味着整个 IDE 无进程或无网络，禁止通过关闭 IDE 原生机制制造零副作用证明。

安装/重启真实 IDE、操作真实 workspace、发布或合并仍需对应授权。按[语言解耦测试方案](../changes/ide-plugin-language-decoupling/testing/test-plan.md)执行本次清理的针对性验证。

## 7. Git、测试证据与 Gradle 版本管理

源码版本以 Git commit、tree 和 diff 为准，不为已跟踪源码维护第二份逐文件 SHA-256 清单。正式候选使用明确的 checkout；用 `git status --short`、`git diff` 核对未提交和未跟踪输入，不能在实际输入有变化时仅记录 HEAD 并冒充已提交候选。日常局部迭代可记录实际差异，不因此创建输入哈希台账。

项目文档保留被测提交、测试范围/命令、结果、未执行理由和 CI run/工件入口，不复制源码、目录、截图、JAR/ZIP 或过程 JSON 的具体摘要值。需要比较安装工件或配置前后是否一致时，可临时计算摘要，结果留在本地测试输出或 CI artifact，不回写源码、方案或报告；正式发布的校验文件随 Release 保存。不要为验证文档身份产生“清单的清单”或自引用哈希。

`plugin-inputs.sha256` 已从当前分支删除，不再生成或作为交付/验收条件。历史结果不得伪造，但可以精简当前报告、删除重复台账并链接固定 Git 历史；不要求当前树永久保留过程文件，也不清除 Git 历史。临时 artifact 有保留期限，引用失效时明确缺口，不能仅凭摘要推断原件仍可取得。

Gradle 通过 Wrapper 的固定版本和官方 HTTPS `distributionUrl` 管理，当前为 9.3.0；不设置 `distributionSha256Sum`，也不在构建脚本、文档或自建清单里改放同一预期值。保留 `validateDistributionUrl=true`、现有超时、Wrapper 文件及缓存设置；升级时维护明确版本，不使用 latest、动态范围、个人镜像或本地 ZIP。这是用户选择的版本管理策略：不执行 Wrapper distribution 的预期 SHA-256 比对，版本号/HTTPS 不被描述为等价的字节完整性证明。[Gradle Wrapper API](https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/wrapper/Wrapper.html)说明了该字段未设置时的行为。

边界不得扩大：Release 自动生成/验证的 `SHA256SUMS`、依赖锁文件及其工具原生 integrity、现有 Wrapper JAR 验证、Action 提交固定和运行时 manifest 摘要均不属于本次删除对象。保留哈希算法和真正的测试向量，不做全仓 `sha256` 字样删除。Go API 依赖清理仍按 S1/S2，不能与这次 Wrapper 配置简化混称为已实现。

本次配置与文档调整只需确认 Wrapper 属性差异、版本/URL未漂移、删除文件无失效引用、文档与索引一致；在可用的完整 checkout 运行 `npm run docs:check` 和 `git diff --check`。不新增逐文件指纹门禁、不为了这一行配置重复 Go/IDE 功能回归。需要验证首次下载时使用获准的隔离 Gradle 缓存，不清空个人缓存；未实测就记录未运行。既有 CI 照常运行。
