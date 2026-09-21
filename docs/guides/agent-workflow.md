---
title: ReqWS Agent 协作指南
type: guide
status: active
updated: 2026-09-21
---

# ReqWS Agent 协作指南

本指南说明如何向编码 Agent 提供清晰的任务、最小必要上下文和可核验的完成条件，同时保留 ReqWS 的安全与验收约束。

## 1. 指令放在哪里

| 位置 | 承载内容 | 不承载内容 |
|---|---|---|
| [AGENTS.md](../../AGENTS.md) | 常驻项目边界、任务路由、授权和交付原则。 | 完整仓库地图、所有专题流程、每次编辑都要执行的阅读清单。 |
| `.agents/skills/*/SKILL.md` | 简短适用条件、关键边界、按需参考入口。 | 多个工作流的全部细节与重复模型政策。 |
| Skill 的 `references/` | 该工作流真正需要的契约、安装确认和恢复细节。 | 无关任务也必须读取的背景材料。 |
| [开发指南](development-guide.md)与需求包 | 当前操作、实现契约、测试及按次证据。 | 为单次提示词临时增加的通用训导。 |
| 用户任务 | 目标、范围、完成条件、允许的外部动作和停止点。 | 重复粘贴所有仓库约定。 |

已知文件可以直接读取相关章节；只有定位或权威性不清楚时才从索引搜索。冻结历史材料不变成当前指令。文档创建、元数据和索引仍遵守[文档规范](../standards/documentation-standard.md)。

## 2. 按任务选择技能

文档契约、验收条件、开发流程或组织结构变化时使用 [reqws-documentation](../../.agents/skills/reqws-documentation/SKILL.md)；只读检索或拼写修正不需要完整生命周期评估。UI catalog、key、占位符、复数或本地化映射变化时使用 [reqws-i18n](../../.agents/skills/reqws-i18n/SKILL.md)。内部重构和 Markdown 文字不构成翻译 delta。

[GoLand 安装技能](../../.agents/skills/reqws-goland-plugin-install/SKILL.md)仍只允许用户显式选择或调用。普通编译、Kotlin 修复、文档中提到技能名称，均不授权安装或重启。有效确认只延续尚未结束的同一工作流；工件或目标变化时重新确认。

[ReqWS 签名凭据维护](../../.agents/skills/reqws-signing-maintenance/SKILL.md) 是项目级技能，支持签名身份审计、同身份的包装密码/Secrets 更新及明确请求的证书/私钥迁移，保持自动发现。普通 updater 代码或文档任务不自动触发凭据操作；技能会根据具体任务区分只读核对、准备候选与激活范围，既有明确授权无需反复确认。真实签名材料只在指定私有凭据仓库及受保护 Environment 保存，本地操作使用临时材料并清理。

翻译子代理使用与主 Agent 相同的模型，不再固定模型系列或版本。优先使用运行时支持的模型继承；需要显式指定时，使用主 Agent 当前配置中的模型标识，不从展示名称猜测 API ID。reasoning 仍至少为 `high`；只读权限、JSON 输出、主 Agent 校验与失败时禁止写回等要求见[翻译契约](../../.agents/skills/reqws-i18n/references/translation-contract.md)。只有真实翻译任务才需要该子代理，不能因为编辑这份说明而启动它。

## 3. 安全执行与完成边界

在已授权范围内，Agent 应继续完成实现、相关验证、修复本次引入的失败和最终 diff 复核，不必在首次补丁后额外等待批准。使用隔离临时 fixture 的本地测试可以连续执行；运行环境的权限限制仍然有效。

这不等于允许启动使用真实 userData 的开发实例、修改真实工作区、安装系统工具、接受新权限、重启 IDE 或发布。遇到缺失权限、模型或环境时，停止受影响的操作，保留现场并明确缺口；能独立完成的安全部分继续完成，不把部分验证写成全量通过。

用户明确要求“新建分支并发起 PR”时，可以完成对应的 scoped commit 和新分支 push；不包含 merge、force-push、tag、Release 或其他仓库写入。只读 review 不授权修复。插件安装仍要求在第一个可能提交安装的控件前确认 exact ZIP、SHA-256、版本与目标，不因追求连续执行而省略。

## 4. 按影响验证

| 改动 | 本地验证范围 |
|---|---|
| 仅文档/指令 | `npm run docs:check`、diff 检查；变更 skill 时另查 metadata、参考链接和相关 eval 场景。 |
| Desktop 代码 | 迭代时运行受影响测试；交付前在环境支持时运行一次 `npm run check`。 |
| GoLand 代码、descriptor 或构建 | `npm run check:goland`；需要交付 ZIP 时再打包。 |
| TS/Kotlin 共享 manifest 契约 | Desktop 与 GoLand 两侧检查。 |
| 安装、GUI 或发布行为 | 对应环境和 exact-head 证据，遵守既有验收计划及确认边界。 |

插件每次迭代记录最低系列 262 的影响；CI 保留编译、Light/Heavy 平台与 API 等全部自动门禁，只有完整 Starter/Driver 进程/UI 移到[本机入口](../changes/ide-plugin-compatibility-automation/local-integration.md)。`HeavyPlatformTestCase` 不是完整 IDE 场景。专用 profile 可交互登录 JetBrains Account、可选 License Server，不复制个人配置或假定继承登录。CI 与本机报告分开，签名产物不能借用签名前 UI 结果；更高版本无需 GUI/Computer Use 矩阵。用户要求只开发时记录未运行项，不执行授权准备。隔离 Starter 不激活日常用户 IDE 安装技能。

文档 checker 不覆盖全部 skill 参考链接，也不执行模型行为评测。静态检查、模型 eval、完整应用测试和真实 GUI 证据是不同结论。不得通过更改 CI、跳过失败用例或清除翻译基线来减少工作；没有相关改动或新证据需求时不重复执行无关的大型检查。

## 5. 可直接使用的任务提示词

### 文档修正

> 修正 docs/guides/development-guide.md 的指定说明。只改相关文字和必要索引，不改变应用行为。完成文档与 diff 检查，报告改动和无法验证的部分；不要安装应用或创建发布。

### 实现并提交 PR

> 修复指定的 workspace 行为，保持现有数据与安全契约。完成实现、相应回归测试、必要文档和最终 diff 复核，修复本次引入的失败后新建分支并发起 PR。不要停在第一版补丁；不要合并、打 tag、操作真实用户工作区或安装 IDE。无法执行的检查明确列出。

### 有界只读审查

> 审查指定提交的 manifest 兼容性与路径安全。只读代码、共享 fixture 和相关有效文档，不修改文件或远端状态。给出可复现问题、证据和剩余不确定性；覆盖这两个主题后结束，不扩展为全仓治理审计。

目标和约束足够清楚时无需再规定文件读取顺序、固定工具调用次数或无条件拉起多个代理。并行写入确有价值时才拆分，并明确文件所有权；翻译子代理始终只读。

## 6. 修改指令后的回归评估

以下项目级 skill 的 `evals/evals.json` 保存场景，不是已执行的测试报告：

- [文档场景](../../.agents/skills/reqws-documentation/evals/evals.json)：跨层契约、轻量修正、移动索引、已知文件直读、只读审查和按需验证。
- [翻译场景](../../.agents/skills/reqws-i18n/evals/evals.json)：与主 Agent 同模型的继承/显式选择、模型不匹配保护、旧 key 新文案、复数、重复占位符和无翻译 delta 的负例。
- [安装场景](../../.agents/skills/reqws-goland-plugin-install/evals/evals.json)：显式触发、工件确认、未保存编辑、故障恢复、普通构建和错误触发负例。
- [签名维护场景](../../.agents/skills/reqws-signing-maintenance/evals/evals.json)：只读有效期审计、同一候选的密码上传中断恢复、已有分发版本的证书/私钥迁移；演练不使用真实秘密或变更远端状态。

评估前后版本时，使用相同任务、宿主工具/权限和可确认的模型/reasoning 配置，在独立的新会话中运行；触发负例必须允许正常技能发现，不能预先强制加载被测技能。记录是否选对技能、读取了哪些文件、是否越权、完成条件是否达到和验证缺口。只读场景不得产生写入，安装场景可用非执行计划或隔离环境，不为评测触碰日常 IDE。

可比较根指令和 description 的 UTF-8 字节数，但它不是 token、延迟或质量测量。只有实际运行了模型场景才报告通过数量和观察结果；只检查 JSON 能解析不能声称 eval 通过。没有可用执行环境时如实记录未运行，不编造工具、子代理或 GUI 证据。

## 7. 调整依据

组织方式参考 OpenAI 于 2026-09-11 发布的 [Rethinking skills and prompts for GPT-6 Astra](https://developers.openai.com/blog/rethinking-skills-and-prompts-for-gpt-6-astra)：缩小技能适用范围、按需展开细节、减少常驻上下文，并明确完成与授权边界。本文将这些建议落实到 ReqWS，而不是取消模型门禁、安全检查、手动安装确认或 exact-head 验收。
