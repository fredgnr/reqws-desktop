---
title: GoLand 工作加载集合任务拆分与 Agent 协作
type: technical-design
status: active
updated: 2026-09-26
---

# GoLand 工作加载集合任务拆分与 Agent 协作

本计划把共享契约先串行冻结，再将独立实现并行推进，最后统一接线和验收，避免多个 agent 争写同一模型或 GUI。用户已决定直接替换历史实现；所有任务都不承担新旧共存、迁移、回退和多版本兼容。

本文保留工作加载改造的阶段背景；当前 IDE 兼容与回归范围以[兼容性与自动化计划](../ide-plugin-compatibility-automation/README.md)为准，下文历史单目标安排不覆盖后续政策。本次子代理职责澄清不改变产品、API 或验收范围。

## 1. 依赖与并行结论

```text
S0 契约/单目标冻结 ───────────→ S1 Desktop 控制面 ─────────┐
     ├────────────────→ S2 受管模型/所有权 ────────┤
     └─ API 门禁通过 ──→ S3 shell 两层适配 ─────────┤
                                                ▼
                                     S4 唯一链路接线/旧逻辑清理
                                                ▼
                                     V 最终自动化 + 本地 GUI
```

S1、S2、S3 可以在接口冻结后使用不同文件范围并行；S3 的生产 API 必须先通过 S0。S4 依赖三个真实实现完成，不靠空服务、永久 feature flag 或宽泛异常吞掉缺失部分。一个 feature 分支/PR 承载本需求，不为任务编号强拆多 PR。

建议最多两个并行写入 worker；读源码 Explorer 和独立 Reviewer 可按需要短时加入。同一时刻仅一个 GUI 操作者；不要同时运行多个争用内存、sandbox 或构建缓存的 Gradle 全量检查。子任务回归按影响执行，最终一次集成全量；失败后的必要重跑不受“一次”限制。

## 2. Agent 角色与权限

| 角色 | 权限与交付 |
|---|---|
| Main / Integrator | 当前用户任务的顶层主 Agent，对契约和状态最终负责；分配精确文件、整合补丁、运行最终检查；独占翻译委派/校验、英文写回与基线确认，以及远端提交/推送和候选状态发布。 |
| Explorer | 只读指定目录/SDK/source；返回路径、事实与风险；不改代码或规范。 |
| Worker | 仅写任务授权文件；可以在独立 checkout 中改实现/直接测试；不得自改 API 门禁、其他 worker 文件或远端状态。 |
| Reviewer | 与实现 worker 分离，按 diff 审阅契约、安全/所有权和测试有效性；只读报告，不以 worker 自评代替。 |
| Translation subagent | 仅真实 UI 翻译 delta 时由顶层 Main/Integrator 委派；模型、reasoning 和输出以[翻译契约](../../../.agents/skills/reqws-i18n/references/translation-contract.md)为准，始终只读，只返回结构化 JSON，不获得写入权。 |
| GUI operator | 单独获准的隔离环境内操作 Desktop/GoLand；按 Project 面板取证，明确人机协作和阻塞。 |

编程 subagent 同样优先继承主 agent 实际配置、reasoning 至少 high，不从显示名猜 API 模型 ID。运行时不能验证模型/权限时停止该受限代理操作，主 agent 可以继续已获授权的独立工作；不能谎报启动了不可用的 subagent。协作与翻译沿用[Agent 指南](../../guides/agent-workflow.md)及其链接的技能。

各 worker 使用独立临时 checkout，禁止共享工作目录并发覆盖；不能对用户工作目录 reset/stash/clean。Main 分配基线 commit 和准确写入清单，收到补丁后按依赖整合；更改共享签名前先收回相关文件写入权，再统一修改实现、调用点和测试。冲突由 Main 解决，不用覆盖式复制整个文件。

## S0 契约与目标 API 决策

**Owner：Main；辅助为只读 SDK Explorer。**

输入：本方案、当前 AGENTS/插件规范、用户最新无兼容要求、验证报告摘要与目标 GO-262.9437.286 SDK。输出不是另一轮通用机制 spike，而是单目标 API 判定、本版共享契约和旧代码删除清单。

工作：
1. 核对当前 HEAD 与设计基线的差异，保留已合入的自更新/活动门禁。
2. 冻结本版 schema、选择求交/失效 ID 规则、错误码、DTO 和 plugin 接口；可直接改变历史字段/签名，全部消费者同时更新，不写兼容 parser。建立 TS/Kotlin 共用普通 JSON fixtures。
3. 核对 Workspace Model update、标准 module/root/marker 序列化、DirectoryIndexExcludePolicy、TreeStructureProvider 与缓存失效所用的真实 SDK 签名/注解。
4. 给出冷启动无差异、空集合、trust 和绑定撤销均可工作的合规失效路径。只能使用 Experimental 时，列出精确例外申请并暂停该路径；不得直接修改禁令来伪装通过。
5. 按用户最新决定把编译 SDK、descriptor、Verifier 统一为 GO-262.9437.286，删除 261/旧矩阵和版本桥接；同步 AGENTS/标准中相冲突的旧基线/策略条款。此项无须重新申请旧版本退役许可；Internal/Experimental 使用仍单独按实际接口作决策。
6. 建立删除清单及当前安全回归的保留/改写映射；将本包从 draft 转 active 基于完成的接口/API 决策，不为历史行为延长过渡。

文件所有权：Main 独占 `AGENTS.md`、`docs/standards/`、本需求包、`src/shared/` 的跨层入口、`integrations/goland/build.gradle.kts`、descriptor 和共享 fixtures；本阶段只修改真实需要的契约，不能为拆任务创建空实现。

最小回归：schema all/selected/empty、重复/非法 ID、协议拒绝、本版成员/选择职责正确；API 编译/结构检查及最小失效行为测试。零测试/全跳过不通过。仅查到公开 IDEA 相邻 tag 不能声称目标 SDK 验证通过。

完成：发布接口/文件清单和 API 决策；合规状态明确。API 阻塞不阻止不依赖它的 S1/S2，但会阻止 S3 完成与产品 GO。

## S1 Desktop 唯一入口与 UI

**Owner：Desktop Worker；依赖 S0 契约。**

工作：完成新的 binding/selection 服务和原子读写；纳入当前共享 coordinator/activity gate；加入 Main IPC/preload 调用和工作区详情 UI；将所有 GoLand 打开动作直接替换为验证后的 shell；删除原目录分支和旧入口 UI。首次准备入口、不接管未知 shell、不写 `.idea`、保存冲突和失败反馈必须一起完成。

写入范围：`src/main/services/` 中本任务新增服务及必要的 editor-launcher/workspace 接线、`src/main/ipc/`、`src/preload/`、相关 renderer 页面/组件、Desktop tests；S0 中的 shared contract 只能提交变更建议，由 Main 处理。Main 在分派时明确上述目录内的具体文件，不授予整个仓库写权。

文案仅在本任务真正改变 catalog 时进入翻译流程。Worker 只修改明确分配的中文源文案及调用方，交回 key、当前 source 和必要上下文；`src/renderer/locales/en-US.json` 与 `scripts/i18n-baseline.json` 不在其写入范围。顶层 Main/Integrator 整合中文变更并收回相关源文案写权后，执行 scan、委派只读翻译子代理、校验结果、写回英文并执行 apply/check；后续源文案变化需要重新审查。其他 worker 不改同一 catalog。翻译门禁未满足时保持英文与基线不变，记录受影响的完成条件和测试缺口，不把它们标为通过；独立工作可继续。

最小回归：初次准备与同绑定复用、foreign/partial shell 不覆盖、expectedRevision 冲突、原子写失败、all/selected/empty、成员增删求交规则、UI 错误/取消、启动参数及活动门禁；VS Code/Cursor 与成员 manifest 不因选择变化而改写。新读写路径的安全回归当步执行。

完成：无需插件即可证明业务配置与启动准备正确；不把保存成功显示成 IDE 已同步，不安装日常插件。输出 S4 所需的真实文件协议和操作入口。

## S2 受管 module、root 所有权和恢复

**Owner：Model Worker；依赖 S0 契约。**

工作：实现 `loading/model/` 中 planner、标准模型 adapter、root claims/companion markers、独立 journal、PFI verifier；保护原生 shell module，空集合保留承载 module。以单 root 差量更新，不全量替换 module。用户额外 root 保留，受管 root 中用户子配置阻止破坏性删除。

写入范围：新增 `integrations/goland/src/main/kotlin/com/reqws/goland/loading/model/` 与对应 tests；需复用现有 verified persistence 原语时，只修改 Main 明确分配的 persistence 文件。不得同时改 project service、plugin.xml 或 VCS observer；旧 adapter 由 S4 统一删除，避免其他任务仍有引用时并发删除。共用文件修改提交给 Main。

最小回归：2→1→0→2、用户 root 同 module 保留、user-extra + 空集合 + 模型保存重载、borrowed root 不认领、marker 丢失/替换/重复、含用户子配置不级联删除、prepared intent/模型部分落盘恢复、目录身份/锁、取消/dispose；正向和关键安全负向当步执行。

完成：模型和存储是可调用真实实现；提供实际命中测试列表。module/marker 持久化没验证就不能输出删除授权。遗留 exclude ledger 不参与新删除权。

## S3 精确入口排除与普通 Project 树

**Owner：Presentation Worker；依赖 S0 API 门禁；可与 S1/S2 并行。**

工作：实现 `loading/shell/` 的 immutable binding cache、精确 exclusion、tree provider 和有界缓存失效；trust、绑定失效与 no-model-diff 冷启动均覆盖。树 provider 不做 IO，不隐藏包含仓库的 module/project 容器。

写入范围：仅新 `loading/shell/`、对应 unit/platform tests；向 Main 提交 descriptor 注册片段，不自行改 plugin.xml。不能改全局 Registry/ignored files、项目根实体或其他 worker 模型。

最小回归：仅 exact shell 命中、同名非绑定目录不命中、Excluded Files 两态逻辑、缓存撤销、新增文件的 PFI 边界、空集合与无模型变化重开、真实用户节点保留。方法级 API 审核集中做一次，不能靠 suppress 或扫描器漏检放行。

完成：两个层次独立生效，返回真实 adapter 与注册需求；不再做搜索截图四格，不以仅树隐藏替代范围层。API 门禁未通过时如实阻塞。

## S4 唯一链路接线、旧逻辑清理与整合

**Owner：Main/Integrator；依赖 S1、S2、S3 完成。**

工作：接入 verified binding/双文件 reader、两个精确 watcher、single-flight generation、唯一 shell 入口、trust/readiness/dispose、cold recovery；完成 PFI 与 UI 状态、VCS loaded/member/retained 分类；注册扩展，更新当前指南和单目标说明。插件不写选择文件。删除旧 workspace-root/excludes 运行链、retained 扫描、旧 ledger reader/迁移分支、旧模式 UI/IPC/状态文案、专用 fixtures/tests 和失效文档；没有 rollback adapter 或双策略开关。

写入范围：Main 独占既有 `project/`、`sync/`、`ui/`、`vcs/` 接线及 plugin.xml/build 配置；S1/S2/S3 已交回的文件才允许集成修正或删除。可让只读 cleanup Explorer 核对“删除/改写/通用复用”清单，删除仍由 Main 统一执行；不另设并发 cleanup Worker 争写旧文件。Kotlin messages 有翻译 delta 时执行同一只读翻译流程。

最小回归：Desktop 保存后自动生效、同 manifest 不同选择触发、并发原子替换 latest-wins、坏文件保留有效模型且不报成功、信任转换、同摘要强制重核、cancel/dispose 晚到结果不发布、不存在原目录接管/旧模式 fallback，旧状态文件没有 reader。用户手动根覆盖取消加载仓库时保留并告警，不把 VCS 额外 mapping 当作必须删除项。

完成：独立 Reviewer 根据整合 diff 检查全链、文件所有权和旧逻辑清理；核对 imports、descriptor、构建任务、状态文案与测试注册，无未使用旧实现；阻塞项修复后冻结候选再交 V。不要每个 worker 都各跑完整 GUI/全量 Gradle，也不要跳过已触发的现有 CI。

## V 最终验证与交接

**Owner：Main + 独立 Reviewer；GUI 由唯一操作者执行。**

以[测试计划](test-plan.md)执行一次整合后的完整 Desktop/插件检查和必要 GUI。只验证单一目标 SDK，不运行旧版/迁移/降级场景。真实安装与 IDE 进程重启先取得对应 exact artifact/隔离目标授权；本设计/推送请求不是安装授权。所有文件存在/可见性检查在普通 Project 面板展开目录完成，禁止搜索替代。

交接记录：实际源码 commit/diff、完成任务、文件列表、命令/真实测试选择器/结果、未执行理由、构建工件入口、IDE build、GUI 观察及用户协助。不得生成逐文件源码/工件摘要台账，也不得把设计 PR 的 CI 结果写成实现验收。

## 3. 通用 subagent 任务模板

```text
任务：<S1/S2/S3/只读审查>；基线：<commit>
必读：本任务段、需求、技术方案相关章节、当前 AGENTS。
模型：继承主 agent 实际模型配置；reasoning 至少 high；不可确认就停止代理操作。
允许写：<精确路径列表>；禁止写：<共享/其他 owner 文件>。
文案：只交回获分配的中文源文案及上下文；不自行翻译、不写 en-US.json 或 i18n-baseline.json、不运行 i18n:apply，由顶层 Main/Integrator 按翻译契约处理。
共享接口：<S0 冻结的本版接口>；允许破坏历史接口；跨当前子任务改签名仍先报告 Main，不能并发覆盖。
历史清理：不保留旧模式/迁移/回退/旧版本支持；旧文件删除按 Main 的依赖清单执行，不能删除用户数据。
实现真实功能及直接回归；不做 Git 生命周期/语言功能，不改 VCS mappings。
不安装、不操作日常 workspace、不发布远端；GUI 仅交给指定操作者。
返回：变更文件/实现摘要、实际检查及命中用例、风险与阻塞、给 Main 的接线需求。
```

任务模板是本需求的分工工具，不构成后台执行承诺，也不要求所有仓库任务都启动多个代理。没有编程 subagent 能力时，主 agent 可按相同依赖串行完成已授权的独立开发工作，并如实保留独立审查缺口；缺少满足门禁的翻译子代理时，仍须停止英文与基线写回，不能借串行执行自行翻译。
