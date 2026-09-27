---
title: 文档归属与渐进式披露技术方案
type: technical-design
status: draft
updated: 2026-09-27
---

# 文档归属、多级索引与渐进式披露技术方案

本方案在现有导航上增加明确的事实归属和按需读取协议，让用户、开发者与 Agent 不必先加载完整需求历史再做一个局部任务。以下路径、字段和命令均为拟议设计，尚未实施。

## 概述

调整分三层：文档只在适当位置维护事实；索引负责选择而非复述；读取工具分别返回候选、目录和正文。规则和必要安全限制必须完整加载，不能为减少返回字节而静默漏掉。历史方案先完成有效内容迁移，再退出当前默认检索。

## 目录

- [目标与非目标](#目标与非目标)
- [事实归属与目标目录](#事实归属与目标目录)
- [高影响文件的章节迁移](#高影响文件的章节迁移)
- [多级索引约定](#多级索引约定)
- [元数据与状态语义](#元数据与状态语义)
- [页面结构与引用](#页面结构与引用)
- [渐进读取协议](#渐进读取协议)
- [校验、预算与迁移保护](#校验预算与迁移保护)
- [决策取舍](#决策取舍)

## 目标与非目标

目标是让一次典型任务明确找到适用规则、当前契约、执行步骤、实现位置及必要验证；一个事实的更新不再需要修改几份近似说明。不得把方案、历史实跑和当前产品状态混为一谈。

本次不改产品行为、IDE 最低 262 系列/无上限政策、唯一受控 JPS API 例外、原生验收豁免、模型/翻译白名单和发布权限。不重构 CI，不运行或配置秘密，不触碰用户数据，不创建实际发布。也不引入文档网站、向量数据库、MCP 服务、全量翻译或另一套任务管理系统。

## 事实归属与目标目录

保留现有目录，逐个领域迁入当前事实；下列是首版上限，不要求一次创建全部页面或空占位。

```text
docs/
  README.md                          # 面向任务的总路由
  guides/
    README.md
    installation.md                  # 保留已有用户路径
    user-guide.md
    goland-plugin-guide.md
    development-guide.md             # 开发起步、常用命令及路由
    agent-workflow.md                # Agent 如何选范围、执行、交接
    actions-cache-policy.md
    actions-cache-cleanup.md
    testing/
      README.md
      electron.md                    # 当前自动化入口、隔离、失败排查
      local-ide.md                   # 当前专用 profile / exact ZIP 流程
    release/
      README.md
      macos-signing.md               # 当前构建、签名、维护操作入口
      marketplace.md                 # 当前上架/重试流程，不夹事件授权
    images/                          # 保留资源归属
  architecture/
    README.md                        # 系统组成、职责、当前契约路由
    workspace.md                     # Git/workspace/manifest/事务与数据保护
    ide-integration.md               # binding/selection/roots/PFI/JPS 的当前契约
    compose-ui.md                    # 只读 UI 状态、宿主和资源生命周期
    settings-and-i18n.md              # 设置持久化、错误及语言行为
    self-update.md                   # 运行时更新状态、互斥与信任
  standards/
    README.md
    documentation-standard.md        # 元数据、归属、状态及检查规则
    agent-collaboration.md            # 通用委派、模型判定和文件所有权
    regression-testing.md            # 跨产品证据与手工替代原则
    ide-plugin-development-testing.md # IDE 专有兼容、API 与平台要求
    templates/                       # 沿用按需模板
  changes/                           # 原需求包、决策理由、任务、候选证据
  reference/                         # 完全冻结，不改用途、不移动
```

根架构 README 介绍 Electron Main/preload/renderer/shared 与 IDE 插件的职责关系，不列出全部函数。包级 README 和源码注释继续拥有局部 API；新架构页链接代码，不复制完整类型或测试清单。若 `ide-integration.md` 后续确实需要独立子领域归属，再评估拆分，不预建深目录。

| 事实类别 | 唯一维护位置 | 其他页面的处理 |
|---|---|---|
| 全局授权、产品边界、每次都适用的短规则 | 根 `AGENTS.md`，细则链接规范 | Skill/指南不重复完整政策。 |
| 文档结构、metadata、状态与写作检查 | `standards/documentation-standard.md` | 文档 Skill 只解释执行流程和模板选择。 |
| 通用子代理模型与有效配置判断、所有权、交接 | 拟议 `standards/agent-collaboration.md` | Agent 指南与需求分工链接，不生成第二份模型列表。迁移前仍以现有 Agent 指南为准。 |
| 当前数据、跨层行为和安全条件 | `architecture` 对应主题 | 技术方案保留决策与变化范围，指南只保留操作所需摘要。 |
| 当前怎样执行 | `guides` 对应教程/运维页 | 设计只链接操作页，不再复制整组命令。 |
| 测试必须证明什么 | 通用/IDE 标准及需求专项 test-plan | 操作指南负责命令；报告负责已执行结果。 |
| 旧手工断言的替代裁决 | 保留 Playwright `manual-inventory.md` | 当前标准引用它；不在根和指南复制 49 项裁决。 |
| 为什么作决定、替代了哪一部分 | 原需求包的设计/研究页 | 当前契约提供直接理由链接。无需新建平行 ADR 库。 |
| 候选、命令结果、失败、例外授权和事故时间线 | 原需求包按次报告或明确事件记录 | 不传播为常青状态；旧授权不授予新动作。 |

“唯一维护”不是禁止摘要。父级可以用一句话说明职责，但不能重新维护下层的字段默认值、完整条件、版本结论或测试计数。

## 高影响文件的章节迁移

每次迁移先在当前源码和测试中核对行为；本计划并未替代这一步。对目标归属尚不确定的内容保留原入口并标记待核，不以概括摘要删除唯一规则。

| 来源 | 保留/迁出内容 | 目标与收尾 |
|---|---|---|
| `guides/development-guide.md` | 保留搭建、最常用命令和失败入口；进程/数据职责、IDE 投影、测试细节、发布说明、阶段计数分别处理。 | 架构 README/workspace/IDE 页、testing/release 指南及原报告。旧标题若仍有入链，先保留短路由并原子修链。 |
| `guides/agent-workflow.md` | 保留任务启动、如何定位文档和证据交接；第 8 节通用模型判定与委派约束一次性迁移。 | `agent-collaboration.md`；同步所有引用第 8 节的 Skill/任务链接，不改变允许的模型或回退语义。 |
| `standards/ide-plugin-development-testing.md` | 保留最低版本评估、平台/API、CI 不启动完整 IDE、唯一 JPS 例外等 IDE 专项。 | 通用证据分层移 `regression-testing.md`，命令移 local-ide 指南；不能恢复 blanket Internal 禁令覆盖已获准例外。 |
| `changes/goland-plugin-support/{requirements,technical-design}.md` | 逐章节区分仍有效的读取/语言无关/数据保护要求与已替代的入口、Go 依赖和 UI。 | 先迁入 workspace/IDE 当前契约，再修正 superseded 提示；完整原候选证据保留，不只改 header。 |
| `changes/goland-workspace-loading/technical-design.md` | binding、独立入口、selection、用户 roots/claim、错误保留和 PFI 是当前契约候选；初始 SDK、步骤和曾采用方案是历史/决策。 | 当前部分与已实现 JPS/Compose 变化一起核对后进入 `ide-integration.md`；原包保留差异与取舍。 |
| `changes/ide-plugin-compatibility-automation/local-integration.md` | 授权/profile、精确 ZIP、suite、失败报告是当前操作；旧 0.1.5/v7 示例与计数可能只属当时宿主。 | 当前命令经脚本核对迁 `guides/testing/local-ide.md`；证据版本由实现及按次报告负责，不将 v7 写成永久版本。 |
| `changes/goland-compose-rebuild/{technical-design,product-design}.md` | 当前 UI 状态/intent、host、线程与资源生命周期迁出；阶段设计变更、失败和候选仍是证据。 | `architecture/compose-ui.md`；保留明确 VoiceOver 豁免及性能非门禁的适用范围，不新增产品验收门槛。 |
| `changes/global-settings/technical-design.md` | 当前设置字段、持久化、错误与 i18n 行为与原实现代码片段/阶段指令分开。 | `settings-and-i18n.md`；用户操作仍在现有指南；翻译实际工作流仍由现有 Skill 负责。 |
| `changes/playwright-regression-automation/technical-design.md` | 当前真实链路、隔离、错误证据进入标准/指南；初始缺口、S0–V 计划和参考基线留原包。 | `regression-testing.md` 与 `guides/testing/electron.md`；不复制 report 数字，不改变逐项替代裁决。 |
| `changes/macos-self-update/technical-design.md` | 信任与运行时、证书/Environment/发布/维护操作、最小验收和阶段计划分别有主职责。 | runtime→self-update；操作→release/macOS 与既有 signing Skill；验收留需求专项并由标准引用；计划留原包。关键限制不能在迁移中丢失。 |
| `changes/goland-plugin-marketplace/bootstrap-and-operations.md` | 日常权限、签名、上传/重试，与 §6 v0.1.7 事故和授权记录分开。 | 日常→release/marketplace；事故→本包按次记录。移动保留完整上下文、日期和原链接，历史授权明确不可复用。 |
| `changes/github-actions-ci-release/{requirements,technical-design,delivery}.md` | 旧三资产/unsigned 与后续四资产/签名/市场链不同；“常青交付”与旧候选不能同时承担当前说明。 | 当前发布契约用脚本/工作流复核后迁指南；v0.1.1 报告和初始范围保留历史身份。 |

文件级去向见[清单](inventory.md)。当前契约更新时直接改 owner；只有持久决策理由确实变化才更新/新增需求设计，不要求小改动每次新增需求包。

## 多级索引约定

总索引用任务分路：使用产品、开发实现、执行检查、发布维护、查决策或历史。分类 README 简短说明本类负责什么，再列直接子项；不递归列出所有后代。需求总索引保留主题导航价值，不模仿自动生成所有文件和报告的中央 INDEX。

导航项包含链接和“何时阅读”的一句话。保留现行索引要求的状态列，但不把阶段完成/候选通过计数写到父级。例：

```markdown
| 主题 | 状态 | 何时阅读 |
|---|---|---|
| [IDE 集成](architecture/ide-integration.md) | active | 修改加载选择、独立入口、roots 或错误恢复时。 |
| [本机 IDE 检查](guides/testing/local-ide.md) | active | 已确定需真实 IDE 证据，准备精确候选和隔离环境时。 |
```

上例为未来总索引中的链接，目标存在后才入库。禁止先提交死链接占位。已知 owner 时直接读目标与适用指令，不强制每次从根走三层。正常任务默认不读本次 `inventory.md`。

对于根规则、目录规则和 Skill，沿用宿主当前能力和仓库路由。**不假定写一个子目录 AGENTS 就会自动按 touch 注入。** 本方案不为模仿 DeepSeek 额外创建重复的 `docs/AGENTS.md`；根指令继续指向文档 Skill 和规范。若未来新增目录指令，必须明确宿主读取方式并只写该子树独有规则。

## 元数据与状态语义

沿用现有 `type`，不新增同义 `kind`、无消费者的 tags、重复 name/audience 或“最近检查时间”装饰字段。拟议新增两个字段，由后述读取命令消费：

```yaml
---
title: IDE 集成契约
type: technical-design
status: active
updated: 2026-09-27
description: 修改 GoLand 的独立入口、加载选择、受管 roots 或失败恢复时阅读；不覆盖签名与市场发布操作。
authority: current
---
```

| 字段/取值 | 语义 |
|---|---|
| `type` | 文档主要形式：规范、指南、需求、设计、计划、报告或交付；保留七种既有值。 |
| `status` | 文档维护/采纳状态，不直接证明实现、验收或发布。修订现行“active 可作依据”的表述，明确还必须看 authority 和范围。 |
| `description` | 打开前的路由信息：涵盖什么、何时读、与相邻 owner 的差异。不是执行指令或完整摘要。 |
| `authority: current` | 已复核的当前契约、执行要求或当前裁决；不能仅因 PR 合并赋值。 |
| `authority: proposal` | 尚待采用/实施的方案，不压过 current。 |
| `authority: evidence` | 某候选、环境、时间内的观察；报告中的结果不能自动迁移到当前候选。 |
| `authority: historical` | 已被替代的设计/旧输入，只解释历史。`reference` 通过目录约定获得这一分类，不改 frozen 文件。 |

报告即使 `status: active`，也只能是 evidence。一个仍在维护的需求包可包含 current 的裁决、proposal 的下一阶段和 evidence 的旧报告，README 必须说清楚各文件的作用。混合正文优先拆分；暂未拆分的页面在开头明确分区和唯一 current owner，不能把整页标为 current 来掩盖旧步骤。

迁移期间缺字段的既有页面标作 `unclassified` 返回，不悄悄归成 current，也不直接过滤掉。默认检索优先 current，同时保留相关 unclassified 提示；指定 current-only 时也报告有未分类候选及潜在规则缺口。只有该领域完成有效内容迁移并验收，才可默认只读 current。`updated` 仅表示编辑日期，不是事实冲突的自动裁判。

已知替代继续使用现有 `superseded-by` 及正文链接（已核对基线 checker，不另造同义字段）。部分替代用章节表说明“哪些事实迁走、哪些仍有效”，不把整份报告误写为新候选已完成。原规范禁止的无意义 latest/final 文件名不批量追改旧记录，只约束新增材料。

## 页面结构与引用

常青页采用“概述 → 目录（较长时）→ 最短安全使用/职责 → 限制与错误 → 按需细节 → 源码/进一步阅读”。短索引、模板、机器拥有的格式和冻结资料不强制增加空章节。概述说明能力、使用时机和关键边界；目录只导航，不逐节复制摘要。

每个实质章节第一段帮助读者判断是否需要继续；稳定锚点使用可核验的标题或显式 ID。源码映射只列职责与实现入口，不复制完整 API/字段清单。重要先决条件、禁止事项和失败语义不能藏在可选阅读中。

HTML `<details>` 可用于人类阅读较深实现说明，但完整 Markdown 返回仍包含其正文。真正减少模型输入依赖下述分段读取，不以折叠数量验收。Skill 主体链接少量直接参考；进入一个具体工作流后不再多跳五层。

移动文件时原子修复 `README.md`、`AGENTS.md`、`docs/`、受影响 `.agents/skills/` 与 `integrations/goland/README.md` 等维护入口的入链。带旧 Git commit 的历史链接不自动重写；冻结来源也不改原文。确有外部书签需要兼容时保留可解释的短路由页，避免永久复制两份正文。

## 渐进读取协议

先用普通 Markdown 与现有文件工具形成可用路径，再实现薄的本地 CLI。以下是拟议接口，不是当前可执行命令：

```text
node scripts/docs.mjs find --query "加载选择" --limit 8 --max-bytes 4096
node scripts/docs.mjs outline docs/architecture/ide-integration.md
node scripts/docs.mjs read docs/architecture/ide-integration.md --section <anchor> --max-bytes 8192
```

`find` 返回路径、标题、description、type、status、authority 和匹配原因，不返回全文。优先按路径/标题/description/精确符号匹配；必要时显式全文检索，但只返回短片段，不将搜索结果当成足够的契约。全库目录从当前文件在内存生成，不提交一份每次新增报告都会改动的全量索引，不需要持久缓存或 embedding。

`outline` 返回概述、H2/H3 的锚点、范围和大小；不悄悄展开全部正文。`read` 返回指定章节与必要上下文，带实际路径、Git commit、工作树是否有未提交变更、章节、源行范围、返回字节和完整性标记。允许精确 `--from-line/--to-line` 继续读取；分页必须在段落/代码块边界处理，不能把未闭合代码块伪装成完整命令。超大单块明确报出需要更大预算或单独读取，不静默切断条件。

读取流程为：

```text
适用的根/目录/任务指令
  → 任务匹配一个或少量领域入口
  → 看候选描述、authority 与概述/目录
  → 读相关契约及其必要先决条件、限制
  → 定位源码、测试和操作步骤
  → 信息足以执行当前任务时停止扩展
```

关键词结果只用于发现。读者必须完整掌握适用的规范段落及其明确的必读依赖；禁止只看 description 执行。可选相关链接不自动递归全部加载，必要契约链接也不能被“最多读三页”截断。工具不自动裁定所有安全前置条件，章节入口和 Reviewer 共同检验覆盖。

命令只读仓库允许的 Markdown；拒绝绝对/越界路径、symlink 逃逸和不存在锚点。来源无法确定或文件读取失败时显式失败，不返回空数组假装没有规则。文档中的 shell、历史授权或提案不会被工具执行。代码和图片仍用对应现有工具读取，不把文档 CLI 扩展为任意文件执行器。

接口的默认字节值是初始读取预算，不是删减事实目标。预算不足必须给继续读取位置和未完整提示；只有已完整读完的规范才能计入“约束已读取”。不承诺固定 token 节省，验证按实际返回给 Agent 的字节/字符和任务正确性计算。

## 校验、预算与迁移保护

复用现有 `docs:check` 顶层入口，不另设一个永远不会运行的文档检查孤岛。工具实现与标准同时更新，并补正向/负向 fixture。保留目录索引、命名、现有状态与冻结边界检查。

首批新增：本地 fragment/显式 ID/重复标题解析；新 metadata 类型与值；description 缺失及重复占位；已迁移 current 页必备入口结构；未收录新 owner 与 Skill 入链；输出范围/字节及截断标记。解析要与实际 Markdown 渲染语义匹配，不仅用新的正则替换旧正则；具体 parser 选择在实现阶段核对锁定依赖与许可证后确定。

先对迁移领域启用硬检查，未迁移文件显式列在范围配置并给出退出阶段，不把“豁免全部 docs”当渐进策略。冻结历史只检查外部维护入口能否到达，不要求补 metadata 或 Summary；已存在的历史坏链单独登记，不为了绿灯篡改历史证据。

预算衡量入口返回大小、一次查找扫描的无关内容、最大章节、导航跳数和关键规则覆盖。中文用 Unicode 字符与 UTF-8 字节，不能直接照用英语 wc 单词预算。首轮先测量，再对根指令/索引设留有余量的上限；超限按“移到正确 owner → 精简重复 → 有理由调整预算”处理。精确参考和候选证据不套统一短文限制。

## 决策取舍

选择增加一个常青架构层，而不是把 `changes` 全部改成 ADR。原因是 ReqWS 的需求包已经承担任务、验证和历史来源；强迁移会破坏大量入链且不解决事实重复。

选择复用 `type` 并增加有消费者的 authority，而不是重命名成 DeepSeek kind。前者保留现有规范与检查，新增信息恰好解决“active 报告是否当前”的歧义；代价是需要明确迁移及组合验证。

选择保留主题索引、不提交全量自动清单。当前 13 个需求包的语义路由有价值；一次性处置清单仅在本需求包中作为审计快照，不成为每次开发的维护负担。

选择薄的本地读取命令，而不是先建 RAG/MCP。当前规模首先需要正确归属和可控返回；是否需要语义检索应以后按实际漏检样本决定。正文拆分带来的额外跳转通过直接必要链接和任务样例验收约束。
