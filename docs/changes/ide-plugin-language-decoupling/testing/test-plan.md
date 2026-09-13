---
title: IDE 插件语言解耦测试方案
type: test-plan
status: active
updated: 2026-09-13
---

# IDE 插件语言解耦测试方案

本方案验证删除 Go 耦合后 ReqWS 自有仓库同步与 IDE 适配仍正确，不替 GoLand 或任何语言工具链做全面回归。

## 1. 状态与验收对象

规范、文档和 Wrapper 版本管理调整已落实；以下 Go 解耦功能测试尚未执行。目标契约见[技术方案](../technical-design.md)，边界见[开发与测试规范](../../../standards/ide-plugin-development-testing.md)。本文件维护共用 fixture、覆盖目标与选例规则；阶段执行分别在 S1/S2 和 V 独立文档维护。新计划替代旧 GoLand 常规测试矩阵中的语言门禁和重复人工排查；其通用安全/并发自动化回归仍需保留，不以新表行数替代旧测试覆盖。

验收对象是后续实际清理代码的候选。文档一致性通过、旧版本 GO、单独 ZIP 构建成功或测试总数均不能证明该候选功能通过。

## 2. 最小 fixture

```text
workspace/
├── .reqws/workspace.json
├── repo-a/     active，本地 Git，README.md / marker-a.txt
├── repo-b/     active，本地 Git，README.md / marker-b.txt
├── repo-c/     retained，本地 Git，README.md / marker-c.txt
└── notes/      普通目录，note.txt
```

单元/平台 fixture 使用临时目录和本地 `git init`；Desktop 增删端到端需要 clone 时使用本地 bare remote，不依赖外部服务、账号或真实用户仓库。保持 Desktop 生产 URL 安全策略不变：本地 remote 仅由已有测试层注入，不为方便 GUI 扩大产品接受范围。已创建的 fixture 可通过测试辅助层生成有效 manifest。

基础 fixture 不含 `go.mod`、`go.work` 或任何可编译程序，也不要求 Go SDK。文件名标记仅用于验证目录归属及必要的普通文本搜索，不测试符号引用。

一个自动化对照组即可覆盖顶层 `go.mod` 不存在、存在但内容无效、移到子目录三种情况；它只是证明文件对 ReqWS 判定无影响，不读取 Go registry、不调用 Go 命令。沿用既有路径/安全 fixtures，不额外复制语言矩阵。

## 3. 自有逻辑与针对性自动化

| 用例 | 操作与断言 | 层级 / 对应目标 |
|---|---|---|
| T-01 仓库契约 | 普通文本 Git 仓库按 manifest 全部呈现；额外磁盘仓库不自动成为 active；切换 `go.mod` 对照只改变无关文件，ReqWS 成员、范围与状态判定保持一致。 | manifest/service/platform；LD-01、LD-03。 |
| T-02 目录范围 | 添加、逻辑移除、重加；同时断言受管 Workspace Model 与 PFI 的活动/排除范围，保留 `notes` 和用户条目，retained 数据不删除。 | model adapter/planner；LD-02、LD-04。 |
| T-03 成功与错误 | 通用模型及 PFI 成功才建立 clean baseline；注入 PFI 不收敛仍报 `PROJECT_CONTENT_NOT_CONVERGED / PROJECT_FILE_INDEX`、保持 dirty，修复后同 digest 可恢复；不存在 Go registry 错误/等待分支。 | applier/coordinator/view model；LD-02、LD-03。 |
| T-04 事件与恢复 | 原子替换 burst、latest-wins、手动同 digest、trust 转换及外部范围 drift；自身模型事件不形成循环，后到有效内容和 intent 不丢；关闭/取消沿既有契约收敛。 | service/coordinator/tracker；LD-02、LD-04。 |
| T-05 只读与保护 | 验证插件自己的 VCS 配置分类与重读；缺失/冲突诊断不造成写入；保留非 owned 条目、rootSettings、manifest、仓库目录与无关用户设置。复用既有路径/ownership/原子状态/恢复回归。 | classifier/adapter/security；LD-04。 |
| T-06 清理与构建 | production source/bytecode 不含 Go API；Go gate、专用 trace/参数无悬空引用；无恒真替身。descriptor、实际依赖、插件自身测试、结构验证及原 Verifier 矩阵通过。 | 原 symbol gate + Gradle；LD-03、LD-05。 |
| T-07 必要 GUI | 真实 GoLand 中执行 V 文档的最小操作链，核对插件状态、目录范围与自动刷新。 | 真实 IDE；LD-01～LD-04、LD-06。 |

T-04 使用可控事件、时钟或已有 barrier 回归，不用长时间 sleep 证明并发正确，也不维持只属于旧 Go 补偿的通知次数。若保留额外 notifier，必须补一个能独立证明其通用用途的用例；没有这种用途就删除 notifier 和其专属测试。

### 旧自动化测试的处理

删除 `ReqwsGoModulesSynchronizerTest` 中只验证 Go module 筛选、registry 轮询/补偿/超时的用例。混合在 model/service/coordinator/UI 测试中的 Go 断言按职责移除；原本用 Go failure 触发 dirty baseline、取消、dispose 或恢复的测试，改注入 PFI/通用模型失败，保留相同安全断言。纯平台 module、roots、ownership 或 VCS 测试不能仅凭名称被删除。

当前有效的通用缺陷回归继续随 `npm run check:goland` 执行。测试数量允许因职责清理减少，但必须能说明被删用例只覆盖被取消的 Go 功能；不以保持旧数量为目标，也不借减少数量绕过安全失败。

## 4. 分阶段执行与最小回归

### 4.1 直接打开当前阶段文档

| 阶段 | 执行入口 | 该文档维护的内容 |
|---|---|---|
| 规范与 Wrapper 配置整理 | 本节 | 完整 checkout 执行 `npm run docs:check`、`git diff --check`；覆盖包只能报告实际完成的包内静态检查。 |
| S1 | [核心同步语义解耦](../tasks/s1-core-sync-decoupling.md) | 实施步骤与 S1-R1～R5：主路径、目录范围、PFI 失败/恢复和直接受影响保护。 |
| S2 | [补偿调度与依赖清理](../tasks/s2-scheduling-dependency-cleanup.md) | 实施步骤与 S2-R1～R5 / S2-X：实际事件/意图分支、装配、共享消费者及必要 S1 交叉回归。 |
| V | [最终系统回归与验收](final-acceptance.md) | 最终候选完整插件用例、原兼容矩阵、文档与必要真实 IDE 操作及判定。 |

顺序固定为 S1 → S2 → V；任务边界与合并条件见[任务索引](../tasks/README.md)。S1/S2 不是完整交付候选，不各跑一遍完整 Verifier 或 GUI；V 也不是把两个阶段结果加总。具体阶段测试清单只在对应任务正文维护，本文件不再复制。

### 4.2 最小选例的通用规则

以直接变更、调用/状态消费者、fixture 和运行依赖的实际影响为依据，先从已存在的方法中选择，缺少覆盖时再新增测试。一个用例覆盖多个目标只选一次；不按固定用例数限制，也不将几种独立的取消或竞争风险合并为一个正常路径。

先核对执行时真实测试名称，使用方法/类级过滤；不编造选择器。方法隔离不可靠时退到直接受影响的完整测试类，不通过禁用合法测试缩小范围。选择器零匹配或全部跳过均不能算通过；记录实际执行、失败、跳过数和结果位置。

生产与全部测试源码须可编译，利用正常 Gradle 任务依赖完成，不把编译修复延后。修复时只跑失败及直接关联用例；阶段完成前覆盖该步最终改动的影响集合，不拿修改前结果作证据。未改动的有效测试保留到 V；原 Go 失败注入所保护的通用契约需当步改为 PFI/普通模型注入。

S2 接手本身不触发 S1 全量重跑；改到 S1 被测入口、错误/成功发布、共享 fixture 或运行依赖时必须补对应项。依赖调整后至少验证一个使用真实平台组件的普通仓库主路径，不能仅凭纯 helper 测试证明装配。

### 4.3 执行与证据复用

阶段/最终结果分别注明 S1、S2、V，记录实际代码基线、必要工作树差异、命令/选择器、测试范围、数量、结果位置和未运行原因。局部测试 XML、历史 GO 或单独 ZIP 构建不能证明最终完整覆盖。

原有任务缓存可用，但必须能够核对相同有效输入和同一执行集合；纯粹显示 UP-TO-DATE 不足以作为证据。不能确认覆盖时仅重执行所需测试/检查任务，不清空依赖或重复下载 IDE。V 不传局部选择器，并确认覆盖完整保留集合。

最终运行逻辑、依赖、构建或工件变化后重建受影响证据；纯文档变化核对功能输入/工件未变后可复用相关结果，说明绑定而不冒充另一个工件已验证。详细最终命令和缺陷后重跑规则由 V 文档维护。

权限或环境不可用时记录 BLOCKED/NOT RUN，不能自动安装工具、重启真实 IDE 或操作用户工作区绕过。现有 CI/Release 仍照常执行，中间 push 触发完整 CI 不等于应该跳过；局部测试策略不修改远端门禁。

### 4.4 版本与证据规范的轻量检查

Wrapper 的 Gradle 9.3.0 与官方 HTTPS URL 保持不变，`distributionSha256Sum` 未设置、无替代预期值；保留 URL 校验及其他现有配置。确认源码输入清单已移除、没有本地失效入链，文档不再要求抄写 ZIP/JAR/截图/源码摘要。临时比对可保存在本地测试输出或 CI artifact，正式发布校验随 Release 保存；不是删除发布或运行时校验。

该准备变更执行配置差异、文档检查与空白检查即可，不另建 Go 功能测试、不要求 S1/S2 重建源码指纹。首次下载未实测时记录 NOT RUN；完整仓库不可用时说明 `npm run docs:check` 缺口，不用覆盖包检查冒充全仓检查。

## 5. 唯一必要的真实 IDE 操作链

操作步骤已迁入[V：最终系统回归与验收](final-acceptance.md#4-最小真实-ide-操作链t-07)，只在最终阶段维护和执行。本节保留原导航锚点，避免历史入链失效；S1/S2 不为完成任务各安装并运行一次。

V 区分真实 Desktop→插件 E2E 与 manifest 边界集成，不把测试辅助层替换 manifest 写成 Desktop 已验证。正常 GUI 链只覆盖打开、自动添加/移除/重加、一次手动刷新及按实际改动补充的重开/版本/交互项，不重新引入语言运行配置或 IDE 全功能检查。

## 6. 明确不作为验收门禁的内容

GoLand 原生 Git 配置解析的全面正确性、原生 diff/log/commit 全流程、Go 补全/跳转/引用、Go Modules registry、SDK/GOROOT/GOPATH、依赖下载、`go test`、Go run/debug，以及其他语言的对应工具链，均不作为本次常规通过条件。

不要求逻辑移除让原 Go run/test 配置不可运行，不要求重加后配置立即可运行。对用户配置的“不越权改写”仍应通过 ReqWS 自有逻辑或必要的配置差异检查验证，不需要执行那些配置。

遇到语言问题可记录限制或开展具体归因；只有能证明违反插件通用契约时才阻塞相关用例。不得以此恢复 Go gate，也不得把真实目录范围错误推给 IDE。

## 7. 判定与结果记录

以下情况阻塞实现候选：遗留生产 Go 依赖/探测/轮询；无语言文件的合法 fixture 失败；真实范围/PFI 不一致却显示同步成功；自动刷新、intent、恢复、取消/dispose 回归；越权写入或用户数据/配置破坏；事件循环；必需构建/兼容检查失败。

Go 模型滞后或用户程序不可运行本身不阻塞 ReqWS 仓库同步验收，但必须诚实说明已移除旧补偿、不保证语言配置立即恢复。缺少必要的 T-07 GUI 证据不能报告完整功能通过，可单独报告自动化已通过。

执行后按需新增真实按次报告，最少记录候选身份、实际环境、T-01～T-07 的结果与证据位置、失败/未执行理由、剩余限制。T-07 区分 Desktop E2E 或 manifest 边界集成；结果用 `PASS`、`FAIL`、`BLOCKED`、`NOT RUN`、`OUT OF SCOPE`，不能将后面三者统计为通过。历史结论不伪造；旧详细记录通过 Git 历史查询，不再提交输入 digest 清单或具体过程哈希。

阶段结果须标明属于 S1、S2 还是 V，并记录实际方法/类选择器与执行数量。S1/S2 可以报告“阶段必要回归通过”，但不得据此报告最终功能 GO。V 覆盖最终组合代码且 T-07 有证据后，才能给出整体验收结论。

本次准备变更包含规范、过程记录和 Wrapper 配置调整；尚未执行 S1/S2 或 V 的功能测试，提交与 CI 状态以 PR #8 为准。
