---
title: ReqWS Agent 协作指南
type: guide
status: active
updated: 2026-09-26
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

[ReqWS 签名凭据维护](../../.agents/skills/reqws-signing-maintenance/SKILL.md) 是项目级技能，支持签名身份审计、同一身份的包装密码/Secrets 更新及明确请求的证书/私钥迁移，保持自动发现。普通 updater 代码或文档任务不自动触发凭据操作；技能会根据具体任务区分只读核对、准备候选与激活范围，既有明确授权无需反复确认。真实签名材料只在指定私有凭据仓库及受保护 Environment 保存，本地操作使用临时材料并清理。

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

## 8. 开发子代理

本节按 2026-09-26 的用户要求约束后续开发中的编码子代理；它是模型选择与协作说明，不表示仓库已实现自动模型检查器或已运行多代理评测。适用范围包括代码探索、实现、重构、调试、测试编写、构建/CI 修改和代码审查，不能通过把编码任务命名为 Explorer、Reviewer 或文档任务来绕过。它不限制主 Agent 自身的模型，也不要求小任务必须拆分。第 2 节的纯翻译子代理继续遵守独立的同模型、只读及主 Agent 校验写回契约。

### 8.1 何时拆分与如何分工

当任务能按独立问题或文件集合拆开，且并行有助于缩短执行或增加独立验证时，主动使用 subagent。小范围文案修正、单点低风险补丁、尚未稳定的共享接口，由主 Agent 直接处理即可，不为了满足代理数量机械拆分。

| 角色 | 工作方式 | 边界 |
|---|---|---|
| 主 Agent | 分析依赖、选择模型、分配文件、整合结果与最终验证 | 持有共享接口和提交权；最终结果由主 Agent 负责 |
| Explorer | 按具体问题只读追踪调用链、测试和风险 | 先给出位置与证据，不抢先修改代码 |
| Worker | 在已明确契约和指定文件内实现，并运行受影响检查 | 越界修改先交回主 Agent 调整所有权；不自行改验收标准 |
| Reviewer | 对已稳定的候选独立只读检查正确性、安全与测试缺口 | 不边审边改；问题交回所有者修复后再复核 |

先读后写：存在未知跨层契约时先完成必要探索，主 Agent 冻结接口后再开放 Worker。每个可写文件同一时刻只有一个所有者；共享 schema、入口、package/lockfile、工作流、构建输出与测试 profile 也要分配唯一所有者。无法拆开时串行交接，不把冲突留到最后 squash。

每项委派至少给出目标、基线/当前差异、依赖、允许读取与写入范围、不得改变的契约、最小检查及输出要求。共享工作树中的子代理不得 reset、stash、clean、checkout，或擅自提交、推送、修改 PR、合并、发布；主 Agent 统一执行获准的远端操作。递归委派须在主 Agent 的预算和所有权分配内，不能自行扩张并发。

### 8.2 编码模型与 reasoning 预设

下面两个预设都在用户允许范围内。按任务选择，必要时可在二者之间切换并记录理由，无需仅为这次模型选择再请用户批准；不得静默改用其他系列。

| 项目预设 | 标准 model | reasoning effort | 建议使用场景 |
|---|---|---|---|
| `coding-sol-max` | `gpt-6-sol` | `max` | 契约明确的实现、测试、局部重构及有界探索/审查 |
| `coding-astra` | `gpt-6-astra` | 至少 `high`；常规用 `high`，复杂任务优先 `xhigh`，必要时使用受支持的 `max` | 跨进程架构、并发/路径/安全边界、难复现故障及关键独立审查 |

这些场景是项目分配建议，不是两种模型的能力保证或强制“一角色一模型”。例如 Astra 不可用但 Sol+max 可用时，可以在后者上完成相应任务；不能把所有小任务都交给最低配置，也不用为每次探索强制启动 Astra。

**“GPT-6 Sol Max”表示 `gpt-6-sol` 配合 `max` effort，不是要求 model 字段字面等于 `gpt-6-sol-max`。** 官方 [Sol 模型页](https://developers.openai.com/api/docs/models/gpt-6-sol)和 [Astra 模型页](https://developers.openai.com/api/docs/models/gpt-6-astra)分别给出模型 ID 与支持档位；配置时仍须确认当前宿主/所选模型实际支持。

Codex 的 model 与 effort 分别配置。以下仅为可按当前宿主 schema 使用的字段片段，不是本次已安装的自定义代理文件：

```toml
# coding-sol-max
model = "gpt-6-sol"
model_reasoning_effort = "max"
```

```toml
# coding-astra；复杂任务可将 high 改为宿主支持的 xhigh 或 max
model = "gpt-6-astra"
model_reasoning_effort = "high"
```

Responses API 的对应字段是 `model` 与 `reasoning.effort`，例如 `{"model":"gpt-6-sol","reasoning":{"effort":"max"}}`。`reasoning.mode`（如 `standard`/`pro`）是另一维度，不能拿 Pro 展示后缀代替 effort，也不因宿主展示为 Astra Pro 就认定属于不允许的模型；先核对其基础模型与实际 effort。[官方 reasoning 说明](https://developers.openai.com/api/docs/guides/reasoning)

显式选择模型时同时指定 effort；只在两项都能确认正确继承时才省略。官方 [subagent 配置说明](https://developers.openai.com/codex/multi-agent/)指出，显式换模型但未配 effort 时可能使用新模型默认值，自定义代理文件也可能覆盖选择。启动前应核对最终生效配置，不把主会话的 Max、代理角色名或某份未生效 TOML 当作子代理实际档位。

### 8.3 判定语义，保留实际 ID

模型身份、effort 和 mode 分开判断，不使用整串展示名称完全相等或含有 `gpt-6` 子串作为门禁。

1. 从宿主当前有效配置、模型目录、明确的别名映射或启动结果取得 model/effort。宿主保证执行的显式参数，或明确保证的当前父级配置继承，可以作为依据；不要求子代理暴露不可访问的内部路由或隐藏信息。配置互相冲突时先解决冲突。
2. 仅在比较展示名称时统一大小写、空格、连字符/下划线等排版差异；`GPT-6 Sol`、`gpt-6-sol` 和界面的 Sol + Max 选择可表达同一预设。传给工具的 ID 必须保留宿主真正接受的值，不把清洗后的展示名自行拼成 API ID。
3. 日期快照、提供方前缀以及 `-wm`、`-pro` 等额外后缀，须由宿主目录/明确配置映射到允许的基础模型；不无条件删后缀放行，也不因名称不同立即报错。`GPT-6`、`default`、`auto` 或“与主代理相同”本身不足以确认身份，需解析其当前有效值。
4. `High`/`Extra High`/`Max` 等展示档位按宿主映射核对为实际 effort；不要比较字符串大小。Sol 必须满足 max，不能因为某客户端最高只显示 xhigh 就默认二者等价。Astra 接受 high、xhigh、max；Ultra 或其他档位只有在宿主明确说明并确认实际 effort 满足所选预设时才接受，不能把某产品的自动委派模式当作通用 API 参数。
5. 支持的模型 ID、effort 及配置字段以当次宿主 schema 为准。[Codex 配置参考](https://developers.openai.com/codex/config-reference/)明确档位依赖模型与客户端。宿主已提供可信映射时不必反复联网验证；出现未知别名/不支持档位才做针对性核对，不把网页暂未收录某别名当作错误证据。

权限模式、订阅等级、service tier、输出长度和 reasoning summary 都不能证明模型或 effort；子代理在正文里自称“我是指定模型”同样不是运行配置证据。不要为得到身份依据读取凭据或私人会话。

### 8.4 不匹配与信息不足的处理

将结果区分为 `confirmed`、`mismatch`、`unresolved`：前者表示宿主可确认符合预设；中者有明确证据不符合；后者只是信息不足，不能冒充通过，也不能直接宣称模型错误。

遇到不符合或信息不足，先读取可用的有效配置/目录，修正参数，或改用另一个已确认的允许预设。当前宿主没有 `max` 且无等价映射时，改用可确认的 Astra+high/xhigh/max，不静默把 Sol 降到 xhigh。两条路线均无法确认时，停止的是该次编码委派，而不是无关文档、检查或全部主任务。允许由主 Agent 完成可独立开展且不要求独立子代理的部分；不得把主 Agent 自审冒充已经完成的独立复核或翻译。

发现运行时发生模型/effort 回退时停止继续写入，保留该子代理差异并交由符合配置的代理或主 Agent 复核；不要为了重跑丢弃其他人的编辑。交接中注明实际配置、依据、回退/未运行项。不要把模型档位问题转化为 Computer Use 全量回归。

### 8.5 输出、集成与验证

在任务交接或执行日志中记录一条紧凑配置摘要即可：`task / role / preset / effective model / effort / mode（如可见）/ 配置依据 / 文件范围`。mode 未暴露而本任务未约束它时可记未提供，不因此阻断；身份、effort 已由宿主确认后，无需每次消息重复校验或提交模型证据台账。

子代理返回完成内容、变更文件、实际检查命令与结果、未完成项和风险。主 Agent 必须读 diff、核对文件归属及相互影响，再对集成候选执行相应检查；子代理的“通过”不能替代最终集成验证。独立 Reviewer 尽量不由同一 Worker 的延续会话担任；修复后按影响复核，不扩大成重复全量测试。

Playwright 改造各阶段的建议分工与独占资源见[子代理实施分工](../changes/playwright-regression-automation/subagent-plan.md)。此处是编码模型政策的唯一详细来源，需求包和提示词引用本节，不复制另一套型号/档位白名单；翻译专门契约除外。

### 8.6 指令检查场景

下面是后续配置适配/模型行为评测应覆盖的场景，不是已执行结果；文档检查通过不等于启动过这些模型。

| 输入或情况 | 应有结果 |
|---|---|
| 界面 GPT-6 Sol Max；宿主解析为 gpt-6-sol + max | confirmed，不要求 model 包含 max |
| 受信目录将某带前缀/快照的 ID 映射为 Astra，effort=high | confirmed，保留真实可调用 ID |
| Astra Pro 映射为基础 Astra + pro mode，effort=high | confirmed，不能因 Pro 后缀误拒 |
| Sol + xhigh，即使是该客户端最高可选档位 | 不满足 Sol+max；尝试允许的 Astra 配置 |
| Astra + low，或只有 Pro mode 而没有可确认的 effort | 分别为 mismatch / unresolved，不能靠展示名称放行 |
| 主 Agent 满足预设，宿主确认 model 与 effort 均继承 | confirmed，无需硬填猜测 ID |
| 只指定新 model，effort 回到默认 medium | mismatch，显式补充符合策略的 effort |
| Luna/5.x、未知的 auto，或只有子代理自报身份 | 已知非允许型号为 mismatch；未知身份为 unresolved |
| 两个 Worker 要写同一 IPC/schema 文件或使用同一 IDE profile | 先分配唯一所有者或串行，不能靠 squash 解决竞争 |
| 无可用子代理工具的纯文档任务；真实翻译任务 | 前者主 Agent 继续并如实说明；后者仍执行翻译专门门禁 |
