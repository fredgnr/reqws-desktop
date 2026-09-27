---
title: ReqWS 文档组织现状审计
type: technical-design
status: draft
updated: 2026-09-27
---

# ReqWS 文档组织现状审计

本审计回答三个问题：当前文档如何被发现，哪些页面拥有当前事实，以及完成一个开发任务是否需要扫描过多无关材料。结论基于文档、文档规范和检查源码，不是新的产品运行验收。

## 概述

ReqWS 已经具有 DeepSeek Harness 所强调的若干基础：直属子项索引、按需阅读、冻结历史输入、常青指南和生命周期材料分开、文档检查进入开发流程。主要问题不是缺少目录，而是当前事实仍散落在较大的需求方案中，常青入口包含按次进度，`status` 同时被用于文档有效性和实施状态，读取工具又不能利用页面结构限制返回内容。

因此建议渐进调整，不进行全库重命名或机械套模板。先确定事实归属，补当前契约入口；再收缩索引和指南；最后增加章节读取、锚点与元数据检查。逐文件建议见[处置清单](inventory.md)。

## 目录

- [审计基线与覆盖](#审计基线与覆盖)
- [主干增量复核](#主干增量复核)
- [现有组织方式](#现有组织方式)
- [应保留的设计](#应保留的设计)
- [主要问题与直接证据](#主要问题与直接证据)
- [领域评估](#领域评估)
- [检查能力与缺口](#检查能力与缺口)
- [DeepSeek 方案的适用边界](#deepseek-方案的适用边界)

## 审计基线与覆盖

ReqWS 基线为 [main 的固定提交](https://github.com/fredgnr/reqws-desktop/commit/99d9c1bb174c1c9c8e8fc95026d02805fb87f8d9)，提交时间为 2026-09-27 10:48:13 UTC，已包含 PR #29 的 Compose 插件改造。所有内容读取均固定在该提交，不将审计期间更新的默认分支混入样本。

| 范围 | Markdown | 其他文件 | 说明 |
|---|---:|---:|---|
| `docs/` 根 | 1 | 0 | 总索引。 |
| `docs/guides/` | 9 | 8 | 用户、安装、插件、开发、Agent、缓存及图像说明。 |
| `docs/standards/` | 8 | 0 | 文档和 IDE 规范、四种正文模板及索引。 |
| `docs/changes/` | 87 | 10 | 13 个需求包及总索引。 |
| `docs/reference/` | 2 | 3 | 冻结 MVP 设计及原型、交接资源。 |
| 合计 | 107 | 21 | 128 个文件、28 个含 README 的目录。 |

107 份 Markdown 均逐份阅读全文；过长返回分段补读，不把搜索摘要或目录枚举当成全文。另读取根 `AGENTS.md`、文档 Skill、`package.json` 和 `scripts/check-docs.mjs`，核对文档的执行约束。21 项资源只清点类型、归属和引用；未逐图视觉验收，未解压原始交接 ZIP 或重新执行其中内容。资源清单不能证明截图与当前产品一致。

本环境通过 GitHub 连接读取固定提交，没有完整 checkout；容器直连 GitHub 的解析失败。Node 为 22，项目要求 24。没有运行仓库 `npm run docs:check`、应用测试、Gradle、IDE、安装或发布。下文引用旧报告中的结果只用于分析文档组织，不代表本次重跑、远端当前 CI 结果或重新确认市场状态。

## 主干增量复核

收尾时 `main` 已前进到 [d8ea93076c3ccf4d69ea34a1ef7b9d5ef0f748c3](https://github.com/fredgnr/reqws-desktop/commit/d8ea93076c3ccf4d69ea34a1ef7b9d5ef0f748c3)，时间为 2026-09-27 14:12:17 UTC。已核对 [两提交比较](https://github.com/fredgnr/reqws-desktop/compare/99d9c1bb174c1c9c8e8fc95026d02805fb87f8d9...d8ea93076c3ccf4d69ea34a1ef7b9d5ef0f748c3)：新增一个提交、17 个变更文件，均为 workflows、scripts 和 workflow tests；`docs/`、根文档规则、文档 Skill、package 与文档 checker 均未改变。

因此 107 份 Markdown 的全文审计仍覆盖该次收尾 main 的文档内容，补丁应用基线采用 d8ea9307。没有把新增 Linux API 验证和遥测实现重新审计为本次文档结论；后续迁移 CI/缓存操作说明须以实施当时的最新源码复核，不能照抄旧平台安排。本补丁不覆盖新增流水线或测试代码。

## 现有组织方式

[文档规范](../../standards/documentation-standard.md)定义“总索引 → 分类 README → 需求包/局部 README → 正文和资产”。每个目录必须有 README；上层只收录直属文件和直属目录入口。该路径是导航工具，不是已知目标时必须重复走完的阅读顺序。

`guides` 面向当前操作，`standards` 维护规则，`changes/<topic>` 聚合需求、方案、任务和证据，`reference` 保留冻结输入。这与 DeepSeek 的按职责分层方向一致；但 ReqWS 的 `reference` 明确不是当前 API 参考目录，不能照搬 DeepSeek 的同名含义。

正文已有 `title / type / status / updated`。`type` 为七种：governance、guide、requirements、technical-design、test-plan、test-report、delivery。`status` 为 draft、active、superseded、archived。现行规范要求同范围同类型通常只保留一个 active；冲突先看状态、更新时间，再以代码/测试复核。这不足以处理不同时间候选的 active 报告，也无法表达部分章节被替代。

需求包并未统一套同一骨架：Cursor 保持三份小文档；语言解耦按设计、任务、测试分层；Compose 分阶段任务、UI 设计和 S0–S4 证据；发布与自更新则更多依赖长篇技术方案。差异有部分合理的规模与维护节奏原因，不应一律消除。

## 应保留的设计

[用户指南](../../guides/user-guide.md)、[安装指南](../../guides/installation.md)和[插件指南](../../guides/goland-plugin-guide.md)已有任务导向、图文映射、成功条件及错误恢复。需要针对过长章节做入口优化，而不是重写全部教程。

[语言解耦入口](../ide-plugin-language-decoupling/README.md)已区分总方案、阶段任务、验收要求和实际结果；[Playwright 子代理计划](../playwright-regression-automation/subagent-plan.md)把通用模型政策链接到 Agent 指南，不复制第二份模型白名单。这些做法应推广。

[Playwright 替代登记](../playwright-regression-automation/manual-inventory.md)按原断言保留自动、部分自动及原生缺口；[V 报告](../playwright-regression-automation/verification-v-2026-09-26.md)明确失败记录、候选关系和有限成本样本。它们不是可以随意删去的“冗长日志”。同样，Compose 验收保留同一 ZIP、真实输入、独立进程和未执行项目，应保留证据完整性。

[旧插件测试计划](../goland-plugin-support/testing/test-plan.md)已经用重定向说明指向后续契约，而非重新维护过时测试矩阵；可以作为部分历史收缩的样例。冻结 `reference` 也应保持原样。

## 主要问题与直接证据

以下为文档组织问题，不把未重新执行的历史产品问题判成当前缺陷。

| ID | 观察与证据 | 影响 | 建议 |
|---|---|---|---|
| A01 | [开发指南](../../guides/development-guide.md)约 35.1 KiB，混合日常命令、进程边界、模型契约、发布方式与阶段验收；[Agent 指南](../../guides/agent-workflow.md)约 19.9 KiB，兼有导航和通用协作政策。 | 为一个命令或一个限制，读者需要扫描大量其他领域说明。 | 命令/操作留指南，事实迁当前契约，通用协作要求设唯一规范归属，证据留原报告。 |
| A02 | [变更总索引](../README.md)包含 Compose S0–S4/G0–G4、Playwright S0–S4/V、自更新签名阶段等细节；局部 README 和报告再次维护这些结果。 | 一个阶段变化牵动不相关总索引；日期和结果易漂移。 | 保留稳定主题与路由说明；细粒度结果只由对应记录负责。 |
| A03 | [旧插件技术设计](../goland-plugin-support/technical-design.md)元数据为 superseded，正文前部仍自称 active，并保留后续方案仍依赖的通用约束。 | 只按状态过滤会漏规则；只读正文又会误选旧设计。 | 先完成逐章节有效性矩阵和事实迁移，再修正当前提示及替代关系。 |
| A04 | [Compose 入口](../goland-compose-rebuild/README.md)状态为 draft，同时记录 S0–S4 完成；多个 test-report 为 active，但只针对自己的候选。 | 文档状态、方案采用、实现完成、验收和发布被混读。 | 保留原状态，不因 merge 批量升为 active；另标明内容权威范围和证据适用候选。 |
| A05 | [工作区加载技术方案](../goland-workspace-loading/technical-design.md)、[兼容性方案](../ide-plugin-compatibility-automation/technical-design.md)、[本机入口](../ide-plugin-compatibility-automation/local-integration.md)跨越多次演进。旧精确 SDK/禁用 API 表述与当前 262 系列及受控 JPS 例外需要按章节解释。 | 读取较早规则可能恢复已经退出的旧 build 上限或错误禁止已获准的唯一例外。 | 当前兼容与例外由标准负责；历史候选/旧过程保留时间限定，不跨候选迁移结论。 |
| A06 | [Marketplace 运维文档](../goland-plugin-marketplace/bootstrap-and-operations.md)把长期配置与 v0.1.7 事故、恢复 Token、一次性删除重建同名 tag 的授权放在一起。 | Agent 可能把事件授权当成日常操作许可，或依据过去的远端状态执行当前操作。 | 日常流程与按次事故记录分开；显式标注历史授权不适用于新会话。此迁移不读取私库或 Secrets。 |
| A07 | [Compose S3 报告](../goland-compose-rebuild/s3-verification.md)指向 `s2-verification.md#4-旧断言迁移`，但目标 H2 是“4. 旧 Panel 意图接续”；S4 已使用对应的新标题链接。 | 文件存在但章节跳转失效，阻断渐进阅读。 | 后续实施修正为真实目标锚点，并用这个实际错误作为负例。 |
| A08 | 文档规范已有简短首段要求，但缺少统一可检索 description、按章节返回协议；现有工具按文件读取仍可能返回全文。 | 有目录不等于节省模型输入，折叠块仍在原始 Markdown 中。 | 在已厘清归属的领域试点 metadata/目录/章节三种读取视图。 |
| A09 | 多份当前有效信息仍只在 `changes` 的大型技术方案或实施记录中；没有独立常青架构契约层。 | 新功能需要先理解历史顺序才能找到当前行为；后来修改容易留下一份旧副本。 | 新增小规模 `architecture`，仅迁移已用当前代码/测试复核的事实。 |
| A10 | [文档检查源码](../../../scripts/check-docs.mjs)不验证锚点，扫描入链也未覆盖全部 `.agents`/集成入口。 | 目录变动通过检查后，Skill 或包入口仍可能断链。 | 扩大明确的维护范围并增加锚点/结构负例，不扫描整个依赖或产物树。 |

A01 的 KiB 为文件字节数除以 1024，只用于说明读取规模；不是模型 token 数，不据此承诺某个节省百分比。旧插件技术方案约 84.7 KiB、自更新方案约 47.4 KiB，拆分依据应是事实归属和检索任务，而不是统一文件长度上限。

## 领域评估

| 领域 | 当前资料组合 | 处置方向 |
|---|---|---|
| Desktop 基础 | MVP 冻结设计、全局设置方案、Code Humanizer 整改、开发指南 | 整理当前 workspace/事务/Git/IPC 约束；冻结原设计和历史缺陷报告不改为当前说明。 |
| IDE 加载 | 插件支持 → 语言解耦 → 独立入口 → 兼容性 → Playwright JPS 例外 | 当前数据所有权和投影契约设单一入口；变化原因、阶段任务和测试候选留原包。 |
| Compose | 技术与产品设计、阶段任务、五份验证报告、旧 Swing 视觉材料 | 当前状态/host 生命周期/UI 边界迁稳定页；原型和旧渲染材料保留来源说明，不冒充现行截图。 |
| 测试 | IDE 标准、开发指南、本机集成指南、Playwright 方案和替代登记 | 标准定义必须验证什么；指南给当前怎么运行；报告记录运行过什么。替代登记继续独占原 ID 裁决。 |
| 发布和签名 | CI/Release、自更新、Marketplace 三个需求包 | 运行时更新信任、操作流程与事件记录分别归属；不更改权限和现有签名检查。 |
| 文档与 Agent | 根指令、文档 Skill、文档规范、Agent 指南、各任务分工 | 根只保留常驻规则；Skill 负责写作流程；规范拥有通用要求；任务只写当次分工差异。 |

## 检查能力与缺口

基线 `scripts/check-docs.mjs` 已检查目录 README、命名、基础元数据、直属索引、部分状态/替代关系及本地路径。它拒绝文档树中的 symlink；这些能力应保留。

其实现使用简化 frontmatter 与 Markdown 链接解析，去掉 fragment 后检查目标路径；代码块之外的匹配也不是完整 Markdown 语义解析。它不能证明标题锚点存在、唯一事实归属、正文与状态语义一致、命令正确、截图符合当前 UI，或翻译准确。因此不能把当前 `docs:check` 绿色等同于已经具备 DeepSeek 的全套检查。

新增检查应包含确切反例和正例，并由顶层命令实际调用。章节大小和入口长度只是可观测量；语义完整性、安全限制和证据是否属于当前候选仍需人工/Agent 评审。

## DeepSeek 方案的适用边界

参考固定在 DeepSeek Harness `477b4f420553e8a52c2fbccc464d7561b239c443`，不跟随其后续默认分支变化：

| 参考 | 对 ReqWS 的适配 |
|---|---|
| [文档分层与 one home per fact](https://github.com/deepseek-ai/deepseek-harness/blob/477b4f420553e8a52c2fbccc464d7561b239c443/docs/AGENTS.md) | 保留现有四类目录；增加当前架构归属，不照搬其 package/subsystem 全套布局。 |
| [dsh-doc 工作流](https://github.com/deepseek-ai/deepseek-harness/blob/477b4f420553e8a52c2fbccc464d7561b239c443/.agents/skills/dsh-doc/SKILL.md) | 借鉴先归属、再结构、最后验证；不让普通文档改动重新全文读库。 |
| [结构与渐进披露](https://github.com/deepseek-ai/deepseek-harness/blob/477b4f420553e8a52c2fbccc464d7561b239c443/.agents/skills/dsh-doc/references/structure-hierarchy.md) | 描述、摘要、目录、正文分工；紧密耦合限制不为字数拆散。 |
| [元数据](https://github.com/deepseek-ai/deepseek-harness/blob/477b4f420553e8a52c2fbccc464d7561b239c443/.agents/skills/dsh-doc/references/metadata-links-i18n.md) | 复用 ReqWS `type`，不再新增同义 `kind`；只增加有实际读取消费者的字段。 |
| [不维护中央 Agent Note 索引](https://github.com/deepseek-ai/deepseek-harness/blob/477b4f420553e8a52c2fbccc464d7561b239c443/.agents/notes/implemented/process/2026-07-19-remove-generated-agent-note-index.md) | 13 个需求包的主题路由仍有价值；不增加全库逐文件常驻索引。本次 inventory 是一次性迁移审计附件。 |
| [Skill 的实际加载边界](https://github.com/deepseek-ai/deepseek-harness/blob/477b4f420553e8a52c2fbccc464d7561b239c443/packages/skill/tool-skill/README.md) | 借鉴摘要先行、资源按需加载；不声称 ReqWS 或使用它的 Codex 已自动具备 DSH runtime。 |

不引入英语 100-word 规则作为中文硬指标，不要求全库双语逐行配对，不新增每页 Dev Note 或强制全部章节折叠。现有 i18n 与模型规则不属于本次重设范围。
