---
title: Compose 重构测试与验收方案
type: test-plan
status: draft
updated: 2026-09-26
---

# Compose 重构测试与验收方案

本方案将 UI 状态覆盖下沉到纯 Kotlin/Compose 测试，同时保留真实 IDE 的接入、输入和 Project 树验证。

## 1. 测试分层

| 层级 | 测试对象 | 运行边界 |
|---|---|---|
| 纯 Kotlin | Mapper、稳定 ID、操作策略、订阅顺序/终态与取消。 | CI；不启动完整 IDE。 |
| Compose 组件 | 真实生产组件、语义、点击、禁用、滚动、文案和布局约束。 | 独立测试配置，使用匹配 JBR/渲染依赖；CI 先验证图形能力。 |
| 平台 Light/Heavy | Factory、service、信任、投影、文件边界和生命周期接入。 | 保留当前 CI；Heavy 测试不等同完整 IDE。 |
| 本机 Starter/Driver | 确切 ZIP、真实内容挂载/输入、项目树、同步和恢复。 | 固定代表 GoLand、隔离 profile、已获准的本机环境。 |
| 小范围系统验收 | 主题/缩放/焦点、VoiceOver 和原生渲染异常。 | 固定环境，优先自动化；无法替代的部分记录必要人工步骤。 |

Playwright 仍用于 Electron/browser，不把 Compose 当作 DOM。CI 不新增完整 GoLand 进程或授权凭据要求。Compose 测试的 JBR/显示服务可单独提供，但不得绕过现有 CI 环境限制。

## 2. 覆盖矩阵

所有条目初始状态均为 not-run；ID 是后续任务和报告的稳定引用，不是已经存在的测试方法名。

| ID | 必须覆盖的行为与反例 | 首次负责阶段 |
|---|---|---|
| CMP-V01 | 宿主 Compose/Jewel 模块、compiler 对齐、descriptor；缺模块/错运行时的受控 fixture 必须失败。 | S0 |
| CMP-V02 | ZIP 无重复宿主运行时、standalone 主题、测试 harness 或控制通道；依赖/产物检查负例有效。 | S0，S4 复核 |
| CMP-V03 | 在真实 IDE 中定位并实际点击 Compose 操作、验证副作用；直接调用 action/service 不计真实输入。 | S0，S3 扩充 |
| CMP-V04 | 全部生命周期与仓库状态组合、投影未确认、错误保留快照、用户 root 覆盖和按钮状态。 | S1 |
| CMP-V05 | 订阅时更新、快速连续通知、重复注册、乱序异步结果、关闭后事件、终态之后禁止复活。 | S1 |
| CMP-V06 | 隐藏/显示、内容删除/重建、项目关闭、插件禁用/启用或重载；资源释放且同步不依赖面板打开。 | S1，S3 |
| CMP-V07 | 同名仓库不同 ID、重排/删行/0行/大量行，滚动和选择身份；已加载数不由列表长度伪造。 | S2 |
| CMP-V08 | 窄宽度、长无空格文本、中英文、emoji/组合字符、字面 HTML、tooltip、错误与复制反馈共存。 | S2 |
| CMP-V09 | 明暗主题切换、字体与缩放、键盘焦点/Tab/激活、完整语义；VoiceOver 作为独立系统证据。 | S2，S3 |
| CMP-V10 | 原有信任/路径/所有权/VCS 不变；Project 树的 `2 → 1 → 0 → 2`、未打开面板刷新、冷启动恢复。 | S3 |
| CMP-V11 | 同环境首次打开、空闲 CPU、重组活动、20次内容开关后的 listener 数、关闭后任务和内存趋势。 | S0 基线，S4 对比 |
| CMP-V12 | 旧内容实现/无调用 dispatcher/旧专属断言已删；同一集成 head 的保留门禁和新覆盖均有效。 | S4 |

## 3. 旧用例迁移规则

S0 从实际测试文件提取“旧类/方法 → 保护意图 → 新类/方法 → 所属 V-ID → 状态”。只在新用例已经运行并保护同一意图后删除对应旧用例；不先降低覆盖阈值等迁移补齐。

ViewModel 的业务状态测试保留或移动；Swing Border 类型、组件个数等实现细节改成组件行为测试。旧 HTML 转义工具可以随旧 UI 删除，但“用户文本按字面显示、完整 Unicode/可访问文本”必须由 CMP-V08/V09 接续。领域 latest-wins、信任和项目文件边界测试不因 Compose 或 StateFlow 引入而删掉。

Factory/availability 测试继续覆盖内容懒创建。线程/disposal 在平台测试与真实宿主各有必要覆盖，不能用 mock 项目证明卸载没有类加载器或原生资源泄漏。

## 4. Compose 与真实输入策略

生产屏幕接受 UiState 和事件回调；测试使用相同组件，不复制一套只为测试存在的页面。选择器建议 `reqws.sync`、`reqws.copyDiagnostics`、`reqws.repository.<id>`；标签稳定，断言仍检查文本、角色、enabled 和真实事件。

Compose 的语义测试与 IntelliJ Driver 是两条路径。S0 优先验证现有 Driver/可访问性是否能稳定驱动 Compose；确有必要可增加仅测试使用的适配，但不得把可执行服务命令的端点打包进正式 ZIP。通过测试设施定位并发送实际输入和直接调用业务方法必须分开记录。无法形成可靠真实输入路径时 G0 blocked，不把坐标盲点或截图存在当作通过。

IDE 中的文件存在性使用 Project 面板目录/文件节点，不使用 Find in Files。原生 Git、Go SDK、语言注册表、代码引用或用户项目 go test 不纳入重复 UI 回归。

## 5. 命令与环境

当前已有命令为 `npm run docs:check`、`npm run check:goland`、`npm run package:goland`，以及兼容性方案中的[本机集成入口](../ide-plugin-compatibility-automation/local-integration.md)。本机入口必须按其实际 CLI 传入候选 ZIP，不在本文编造未验证参数。

`composeUiTest` 是计划新增的 Gradle 任务名，S0 验证后才能写入当前开发指南；尚不能把它当作已有命令。S3 接入 CI 后要求非零测试发现、失败正确传播、报告归档和负例自检。它使用单独 source set/runtime，不污染现有平台测试 classpath，也不进入生产 ZIP。

中间任务只跑直接影响的类/方法、编译和必要负例；最终集成候选运行保留的 `check:goland` 与新增测试。测试 harness 变化、依赖/SDK 改变、清理生产调用方之后必须重跑对应证据。只改本需求包时不跑 Gradle、不构建或启动 IDE。

## 6. 证据与通过条件

每条结果记录 V-ID、实现 commit、候选 ZIP（涉及宿主时）、测试代码 commit、IDE/JBR/OS/arch、命令/选择器、执行数/跳过数、结论、日志与剩余缺口。ZIP 摘要写入运行日志/CI 产物，不把逐文件源码摘要或临时校验台账提交到文档。

结果区分 pass、fail、not-run、blocked；零测试、全跳过、无法授权、只有编译或单张截图都不是 UI pass。签名改变候选后不得借用签名前的宿主报告。文档检查不是运行时证据。

G0 要求 V01–V03 的最小验证及 V11 基线；G1 要求 V04–V06 的本阶段覆盖；G2 要求 V07–V09 组件覆盖；G3 要求真实宿主 V03/V06/V09/V10 与独立 CI 测试入口；G4 要求全部必需项在最终候选成立及 V02/V11/V12 复核。尚未自动化的必要系统检查要真实执行或明确阻塞，不能从 G4 静默删掉。

V11 在 S0 记录同机 Swing 基线和可重复测量方法，冻结首开/资源数值预算；S4 使用相同 fixture 和轮次比较。明显泄漏、持续空闲负载、重复 listener 或关闭后任务为硬失败；噪声需复测并记录，不能以单次 RSS 涨幅直接证明泄漏，也不能掩盖持续增长。
