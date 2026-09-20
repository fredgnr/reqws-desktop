---
title: GoLand 工作加载集合验证依据与源码索引
type: test-report
status: archived
updated: 2026-09-19
---

# GoLand 工作加载集合验证依据与源码索引

本文整理用户提供的两轮本机验证结论和已阅读的源码依据，不是重新运行测试的报告，也不是新产品候选验收。

## 1. 用户提供的报告

输入为《GoLand 原生多 Content Roots 验证》和《ReqWS GoLand 本机增量验证报告》，由用户在当前设计对话提供。这里保存必要结论而不复制报告内尚未一同上传的本机相对截图、诊断 JSON、插件源码和工件链接，也不复制工件/逐文件摘要。原始报告及附件仍由用户的本地 evidence 保存；本次只审阅报告正文，未逐项独立复核原工件。

| 报告 | 原结论与证据范围 |
|---|---|
| 原生 GUI 报告 | PARTIAL。独立入口、外部 roots、repo3 移除/恢复及项目重开成立，但入口仍有可展开 shell 文件；Show Excluded Files 开启态未完成。不能据此宣称完整隔离。 |
| 增量测试插件报告 | GO FOR IMPLEMENTATION。macOS 15.7.3 / 24G419、GoLand 2026.2.1.1 / GO-262.9437.286；独立 sandbox，无 ReqWS 产品插件干扰；模型、精确排除与树过滤候选成立。不是产品 GO。 |

增量报告保留的核心事实：
- 四格对照区分范围排除与呈现：仅树过滤不会消除 shell 的默认项目搜索结果；两层开启后入口被排除且普通树不显示。
- Excluded Files 开、关及恢复原值均完成；运行后新增 shell 文件仍保持排除，正常保存/项目重开保持。
- 公开模型操作完成 `2 → 1 → 0 → 项目重开仍为 0 → 2`，空集合重开先只读 Dump，没有重新 apply 修补；仓库和文件保留。
- P3 原生 shell Content Root 保留，另有测试插件创建的承载 module；总 content 项数不等于受管集合大小。
- P4 在受管 module 中手动加入 user-extra，reconcile 后条目保留。其可见/可搜索内容说明不能承诺整个项目严格只含受管集合。
- 无绑定项目同名 `.reqws/ide/goland` 正常显示；VCS 只读记录。repo3 从未加入本轮 P3，不能把它未出现在检测结果当作 VCS 卸载验证。

报告中的 GUI 为人机协作，不是全自动。它未覆盖 Desktop 真实自动同步、完整 IDE 进程冷启动后的产品绑定/ownership 恢复、旧 `.idea` 迁移（现已明确不在产品范围）、所有用户配置类型、跨版本、性能或语言运行能力。项目重开不能替代进程重启。

**测试方法变更：** 历史报告里的搜索截图是当时证据，不改写其结论；用户最新要求已经将后续文件可见性验收改为普通 Project 面板直接展开目录。新的[测试计划](test-plan.md)不再要求搜索截图、默认 scope 切换或命中数量。

## 2. 源码与接口依据

源码参考为公开 IDEA `idea/262.9437.185`，commit `b75ab523e6adbe1d26112219729eacbcfd24daa0`；与报告 GoLand build 相邻但不是其精确源码。报告称本机 jar/字节码已对齐关键行为，本次没有取得这些完整附件。S0 必须按实际产品 SDK 核对 API，不能拿相邻 tag 充当目标二进制的实际行为证明。

| 源码/文档 | 支持的结论，不扩大为未测行为 |
|---|---|
| [ProjectViewProjectNode](https://github.com/JetBrains/intellij-community/blob/b75ab523e6adbe1d26112219729eacbcfd24daa0/platform/lang-impl/src/com/intellij/ide/projectView/impl/nodes/ProjectViewProjectNode.java) | 普通树组合 Project Roots 与 module roots；多 root 可以出现 module 容器。 |
| [ProjectRootUtils](https://github.com/JetBrains/intellij-community/blob/b75ab523e6adbe1d26112219729eacbcfd24daa0/platform/lang-impl/src/com/intellij/ide/projectView/impl/nodes/ProjectRootUtils.kt) | Project roots 来自 ProjectRootEntity，而不只是模块 content 项。 |
| [ProjectRootEntity](https://github.com/JetBrains/intellij-community/blob/b75ab523e6adbe1d26112219729eacbcfd24daa0/platform/projectModel-api/src/com/intellij/workspaceModel/ide/ProjectRootEntity.kt) | 实体和 register/unregister 方法为 Internal，不作为默认生产删除方案。 |
| [ProjectRootEntity file-index contributor](https://github.com/JetBrains/intellij-community/blob/b75ab523e6adbe1d26112219729eacbcfd24daa0/platform/projectModel-impl/src/com/intellij/workspaceModel/ide/ProjectRootEntityWorkspaceFileIndexContributor.kt) | ProjectRootEntity 注册 CONTENT_NON_INDEXABLE；不是“完全项目外”的同义词。 |
| [DirectoryIndexExcludePolicy](https://github.com/JetBrains/intellij-community/blob/b75ab523e6adbe1d26112219729eacbcfd24daa0/platform/projectModel-impl/src/com/intellij/openapi/roots/impl/DirectoryIndexExcludePolicy.java) | legacy/OverrideOnly 扩展允许提供排除 URL；部分辅助入口 Internal，不能一并视作稳定公开。 |
| [NonIncrementalContributors](https://github.com/JetBrains/intellij-community/blob/b75ab523e6adbe1d26112219729eacbcfd24daa0/platform/projectModel-impl/src/com/intellij/workspaceModel/core/fileIndex/impl/NonIncrementalContributors.kt) | 策略参与文件集合排除且有缓存，getter 返回值改变不意味着缓存已正确失效。 |
| [AdditionalLibraryRootsListener](https://github.com/JetBrains/intellij-community/blob/b75ab523e6adbe1d26112219729eacbcfd24daa0/platform/projectModel-api/src/com/intellij/openapi/roots/AdditionalLibraryRootsListener.java) | 源码描述策略重读用途，但接口 Experimental 且有写锁要求，构成 S0 合规门禁。 |
| [FindInProjectTask](https://github.com/JetBrains/intellij-community/blob/b75ab523e6adbe1d26112219729eacbcfd24daa0/platform/lang-impl/src/com/intellij/find/impl/FindInProjectTask.java) | 解释历史报告为何非索引 shell 仍可能进入默认搜索；不作为新增 GUI 操作要求。 |
| [Workspace Model 官方说明](https://plugins.jetbrains.com/docs/intellij/workspace-model.html) | 第三方可使用标准实体进行模型操作；不能据此放行所有内部实体/方法。 |
| [Project View 结构扩展](https://plugins.jetbrains.com/docs/intellij/tree-structure-view.html) | TreeStructureProvider 可过滤/组织树节点，不负责文件范围排除。 |

## 3. 当时基线与后续范围变更

读取基线为 `cb25cbba2b58fc767d48cbbc001004082e7de390`，包括[AGENTS](../../../AGENTS.md)、[插件规范](../../standards/ide-plugin-development-testing.md)、[Agent 指南](../../guides/agent-workflow.md)和现行 GoLand build 配置。

读取时的生产基线是公开 261 API，禁止 Internal/Experimental；当时 Verifier 覆盖 GoLand 2026.1.3 与 2026.2。报告使用了 Experimental 的排除通知接口和 Obsolete 模型更新入口，这是原始证据的限定，不改写。

用户随后明确撤销本需求的所有兼容要求，并授权清除历史代码/逻辑。新方案只针对 GO-262.9437.286，不保留原目录适配、旧状态迁移、回退或旧版本矩阵；[S0](implementation-plan.md#s0-契约与目标-api-决策)只确定本版契约、目标 API 和删除清单。API 使用政策另行处理，不把无兼容要求解释为已验证或已批准全部 Experimental 方法。无需重做已完成的通用四格探索。

当前 Agent 指南要求翻译子代理继承主 agent 实际模型而非固定旧模型名。当前证据政策不向文档复制逐文件/工件哈希。本需求遵循这些当前规则，不沿用历史对话中已经被仓库更新的配置。

## 4. 设计增量与未执行项

独立业务选择文件、expectedRevision、root companion marker 冷恢复、完整 Desktop 控制链、用户子配置删除冲突和新 API 合规路径是本次设计/实现工作，并非原测试插件已证明的全部能力。历史代码清理是用户新增的范围决定，不是原报告的测试结论。S1–S4 的实现与 V 尚未执行；本文件没有产品测试通过数字，也不提供虚构截图或 CI 结果。
