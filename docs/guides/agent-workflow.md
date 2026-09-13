---
title: Agent 协作指南
type: guide
status: active
updated: 2026-09-13
---

# Agent 协作指南

本指南说明如何给 ReqWS 的编码 Agent 提供足够的任务上下文、选择相关 skill，并以可核对的结果结束工作。

## 指令放在哪一层

[AGENTS.md](../../AGENTS.md)只保留仓库边界、任务导航、授权与验证入口；已知目标可直接阅读，不要求预读全库。skill 的短 description 说明何时适用，入口说明结果和硬约束，复杂操作放在按需读取的 references。需求与技术方案记录产品决策；本轮目标、非目标、允许操作和完成条件放在任务 prompt 中。

这一整理参考 OpenAI 于 2026-09-11 发布的 [Rethinking skills and prompts for GPT-6 Astra](https://developers.openai.com/blog/rethinking-skills-and-prompts-for-gpt-6-astra)。这里采用短触发描述、按需读取和明确完成边界；不是要求所有参与者更换模型，也不是删除安全控制。

## 按实际任务路由

| 场景 | 入口与边界 |
|---|---|
| 新需求、设计/验收变化、文档创建或重组 | [reqws-documentation](../../.agents/skills/reqws-documentation/SKILL.md)；按影响更新材料与导航，不自动生成空文档集。 |
| 已知文档的小修、只读代码解释 | 直接看相关章节或代码；不因提到文档就强制加载文档 skill。 |
| UI 文案、locale key/placeholder/plural、UI-facing mapping 或翻译漂移 | [reqws-i18n](../../.agents/skills/reqws-i18n/SKILL.md)；有翻译增量才进入翻译契约，单纯布局改动不触发。 |
| GoLand 代码、Gradle 构建或只读排障 | 使用相关开发/插件指南，不隐含 UI 安装。 |
| 用户显式选择 `$reqws-goland-plugin-install` | [安装 skill](../../.agents/skills/reqws-goland-plugin-install/SKILL.md)；按阶段读取 build/install references，安装前仍确认 exact ZIP 与目标 IDE。 |

文档导航及元数据规则以[文档规范](../standards/documentation-standard.md)为准；检查命令及产品验收以[开发指南](development-guide.md)和对应需求包为准。

## 保留的门禁

翻译仍要求显式指定 GPT-5.6 Sol/Pro subagent、reasoning 至少 `high`、结构化 JSON、主 Agent 校验及 scan/review/apply/check 顺序。此次重构未授权换成 Astra 或其他翻译模型；若要迁移，应另行明确模型与验收要求。不可用时只阻断依赖该门禁的工作，不伪造通过，也不妨碍独立安全的文档或代码工作。

纯文档更新不隐含安装软件、修改真实 userData、重启 IDE、推送、合并或发布。GoLand 安装 skill 保持 `allow_implicit_invocation: false` 和 action-time 确认；Electron 信任边界、路径/凭据保护、Desktop 唯一 manifest writer、插件只读 VCS 与 exact-head GUI 验收均不变。

## 任务 prompt 示例

根据任务补充范围和可观测完成条件，无需复制整份 AGENTS 或所有 skill 内容。以下示例不授予本轮之外的持久权限。

```text
修复 <现象>，预期行为为 <验收条件>。
相关入口：<已知文件/需求链接>。不修改 <非目标>。
完成实现、相关回归与受影响文档；修复本次引入的问题后再交付。
允许在临时 fixture 中执行相关检查。不要安装、发布或合并。
交付改动摘要、执行的检查及未验证项，不停在第一版实现。
```

需要 PR 时明确基分支与允许新建分支/推送/开 PR；需要安装时显式选择安装 skill，并保留安装提交前的 exact-artifact 确认。只读评审写明不修改文件，输出可复现问题和证据。模型与 reasoning 要求只在确有约束时写入，并用工具配置核实，不以自述冒充运行证据。

## 维护与回归评估

更新指令时先找具体误触发、重复读取或过早停工的例子，再改对应入口，不把一次失败变成全局仪式。静态检查包括：skill frontmatter 可解析且 name 唯一、description 对应狭窄任务、references 路径有效、eval JSON 可解析、文档索引/状态一致，以及硬约束未被遗漏。`npm run docs:check` 不覆盖 skill 文件，不能替代这些检查。

本仓库保留的人工/Agent 回归提示集：

| 提示集 | 覆盖范围 |
|---|---|
| [文档评估](../../.agents/skills/reqws-documentation/evals/evals.json) | 跨层需求、导航迁移、已知路径小修、只读代码说明与不创建空文档。 |
| [i18n 评估](../../.agents/skills/reqws-i18n/evals/evals.json) | 文案变化、旧 key 新源文案、复数、占位符、布局负例、门禁与 baseline 保护。 |
| [安装评估](../../.agents/skills/reqws-goland-plugin-install/evals/evals.json) | 显式调用、未调用/引用文本负例、exact-artifact 确认、失败保护与有限授权。 |

比较前后版本时，在相同代码基线、模型/reasoning、工具及权限配置的新会话中运行同一提示。隐式路由测试仅暴露 skill 元数据，不预先把所有 SKILL.md 塞入上下文；显式调用测试按提示选择 skill。记录实际选择的 skill、读取路径、产物与验证结果、无关工作、额外确认及越界行为。涉及安装的案例保持 planning-only；真实 UI 验收另走授权流程。

这些 JSON 是评估输入，不是已接入 CI 的自动评测。JSON 校验通过、入口字节数下降或产品 CI 通过都不能证明模型行为改善。PR 应区分静态检查、实际运行的 Agent 评估与平台/GUI 证据；未运行就明确写未运行。保留原有安全负例，新增误触发与未触发回归，再判断精简是否有效。
