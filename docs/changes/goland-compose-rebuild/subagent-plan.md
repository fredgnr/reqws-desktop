---
title: Compose 重构 Subagent 协作方案
type: governance
status: draft
updated: 2026-09-26
---

# Compose 重构 Subagent 协作方案

本规范用于后续实施中的有界委派和独立审查；编写本需求包不代表已经启动 subagent 或验证了某个模型配置。本文 Main 始终指当前用户任务的顶层 Main/Integrator，不指实现 Worker 或嵌套委派者。

## 1. 角色和使用条件

| 角色 | 职责 | 权限 |
|---|---|---|
| Main / Integrator | 固定 head、分配文件、确认模型、维护共享接口/构建、集成和最终报告。 | 唯一远端写入者；本任务仅文档分支和 Draft PR。 |
| Explorer | 核对 SDK/API、现有状态语义、Driver 能力和测试覆盖。 | 仅读；返回路径、符号、证据与不确定项。 |
| Worker | 完成一个有独立所有权的实现切片，并编写/执行直接相关测试。 | 只写任务单列出的精确文件；不自行推送或扩权。 |
| Reviewer | 针对固定集成 commit，审查线程、生命周期、依赖、边界与覆盖缺口。 | 仅读；不直接修复，不使用作者自评代替审查。 |
| Translator | 仅在真实 i18n delta 时按现有 skill 产出候选。 | 仅读；不写 catalog、baseline 或 UI 文件。 |

小修复和单文件任务由 Main 完成，不为并行而拆分。Explorer 的发现可能改变方案时先读后写。需要独立验证的阶段不能由作者换一个角色名称后自称完成独立审查；没有实际 Reviewer 时如实记为未完成。

## 2. 模型和 reasoning 核验

编码与实质性代码审查任务按用户指定的两类逻辑 profile 选择；下表不是跨宿主通用 API ID 声明，也不是保证当前环境已提供这些模型。

| 逻辑 profile | 模型系列 | reasoning 要求 | 适用场景 |
|---|---|---|---|
| `sol-max` | GPT-6 Sol | `max` | 依赖/类加载、并发、生命周期和复杂集成问题。 |
| `astra` | GPT-6 Astra | 至少 `high`；复杂任务优先宿主支持的 `xhigh` 或 `max` | 常规 Kotlin/Compose/测试实现和有界 review。 |

“GPT-6 Sol Max”拆成模型系列与 reasoning，不能机械拼成一个模型 ID。`gpt-6-sol`、`gpt-6-astra` 只是可读的系列标记；真实 `model` 参数从宿主可用目录/可信运行配置取得，不按显示名猜 ID。

核验顺序：先读可信模型目录与有效配置，再解析继承/显式选择，然后记录最终 effective model 和 effective reasoning。大小写、空格、连字符及宿主明确登记的别名不应造成误判；不得把不同版本、后缀或套餐凭相似名字当成同一模型。`max` 与 `xhigh` 不自动互换。

任务单记录 `requested_profile`、`effective_model`、`effective_reasoning`、配置证据位置、是否继承和核验结果。核验结果区分 `verified`、`unverified`、`mismatch`：无法取得有效配置不等于已知不匹配，但两者都不能启动受约束的编码委派。禁止仅凭子代理自述认定通过，禁止静默降级。某一路线不可用时可选择另一条本来获准的 profile 并明确记录；不要把替换写成同一模型。

本规范不追加主代理模型限制。翻译优先遵守[翻译契约](../../../.agents/skills/reqws-i18n/references/translation-contract.md)：与主代理实际模型一致、reasoning 至少 high、只读 JSON 输出、Main 校验写回；不由编码 profile 覆盖翻译规则。

缺少符合要求的 subagent 能力时，停止受影响的委派并报告。Main 可完成不要求独立代理的安全工作，但不虚构并行执行、独立 review 或翻译结果；明确需要独立检查的阶段保持未完成。

## 3. 分阶段调度

| 阶段 | 建议调度 | 串行条件 |
|---|---|---|
| S0 | Explorer 核对宿主依赖；Main 实现最小验证；Reviewer 查证据。 | Gradle、descriptor、SDK 策略与最小宿主入口由 Main 独占。 |
| S1 | Main 先落共享签名；Worker A 做 Presenter，Worker B 做平台操作/内容生命周期。 | 文件清单必须互斥；签名变更先合入并通知双方。 |
| S2 | Worker A 做摘要/列表，Worker B 做诊断/操作区，分别带组件测试。 | Screen 装配、共享 token、UiState/Action 和 Bundle 由 Main 集成。 |
| S3 | Worker A 做独立 UI 测试覆盖；Worker B 做本机 Driver 场景。 | 构建/CI 接线与正式 ZIP 检查由 Main；真实 IDE 进程串行。 |
| S4 | Main 清理旧实现并生成候选；Reviewer 对同一 head 仅读审查。 | 清理期间不安排其他 Worker 修改调用方；最后统一验收。 |

默认最多两个并行写入 Worker；额外只读分析仅在不会拖累环境时使用。阶段门槛与依赖以[任务索引](tasks/README.md)为准，不能靠增加代理绕过 G0–G4。

## 4. 文件、运行环境和产物所有权

委派前由 Main 将目录范围展开成精确 allowlist，列出禁止写入文件和本轮新增文件名。同一文件包括生产/测试/文档在内，只能有一个写入者。`build.gradle.kts`、`settings.gradle.kts`、`compatibility.properties`、`plugin.xml`、共享 UiState/Action、CI/脚本、资源 catalog 和任务索引默认由 Main 独占。

Worker 使用独立 checkout/worktree；不得在同一 working tree 并发写入，也不得共享 Gradle 项目缓存、`build/`、IDE sandbox、日志或测试报告目录。可使用受控只读依赖缓存，不得在一方运行时由另一方执行 clean。GUI 测试对显示会话和 sandbox 实行单一租约；没有隔离条件就串行。

需要修改边界外文件时，先向 Main 报告原因与精确文件，由 Main 重新分配；禁止顺手修复、整库格式化、共享分支 reset/stash/clean、覆盖他人改动。Worker 输出补丁/commit 及直接测试结果，Main 检查越界后集成；集成冲突不能通过强推或删除他人工作解决。

凭据、用户 workspace/userData、IDE 安装/重启、发布和远端操作不随任务继承。隔离测试的真实执行仍遵守宿主权限；本地 Starter 不等于可以操作用户日常 IDE。

## 5. 派工与交接模板

实际派工必须填完以下字段，不能只转发整个设计文档。

```text
任务 ID / 阶段 / base commit：
目标及本轮验收条件：
已满足的依赖与共享接口 commit：
requested_profile / effective_model / effective_reasoning：
可信配置证据 / verified 状态：
只读输入文件：
允许写入的精确文件（含测试）：
禁止写入 / 不做事项：
独立工作目录 / build / sandbox / reports 所有权：
本轮直接测试命令与必须命中的用例：
需要 Main 决策的条件及停止点：
交付：补丁或 commit、变更文件、测试选择器/数量/结果、未执行项与风险。
```

Reviewer 的任务单另外固定“审查 commit、允许读取范围、问题严重性/证据/复现、禁止修复和远端写入”。主代理逐项核对返回文件和实际命令；“建议执行”“零用例”“全跳过”不算通过。

## 6. 翻译和最终集成

默认复用既有文案，不启动翻译。出现 delta 时，Main 先合入源文案并收回对应文件写入权，再提取 key/原文/占位符与上下文；Translator 返回候选 JSON，Main 验证完整性、占位符、旧 key 文案变化与语气后写入，并运行现有 i18n 检查。源文案再次变化时重新进入翻译审查，不能复用 Worker 旧 checkout 的结果。UI Worker 的代码写权限不包含 catalog 或翻译基线；Main 串行编码回退也不授权其自行翻译。

Main 最终核对同一 head 的检查、阶段状态、剩余风险及远端授权。PR/commit 由 Main 统一提交；本计划允许后续在一个实现 PR 中保留阶段性 commit，不要求每个任务单独开 PR。没有用户额外授权，不 merge、tag、Release 或发布 Marketplace。
