# GoLand 插件 Compose 重构

本需求包规划用宿主提供的 Compose Desktop 与 Jewel 替换 ReqWS Tool Window 的 Swing 内容层，并同步重构展示状态、生命周期管理和自动化测试。

## 状态与授权范围

- 方案状态：draft；S0/S1/S2 已 completed，G0/G1/G2 pass，S3 completed、G3 pass、S4 in-progress。实际范围分别见 [S0](s0-verification.md)、[S1](s1-verification.md) 和 [S2](s2-verification.md) 验证记录；性能指标不参与验收。
- 初始调研基线：`main@b50a6b15d50658e067d3dd9283ea062e06aff559`；调研日期：2026-09-26。S0 实际基线为 `384894dab0e8dcc7c0040b08802eb3bd167a3c85` 加本轮验证补丁。2026-09-27 rebase 后，重建脚本改用主线可达的 `e90cd310b2250594a22148b877b9a6882e752943`；已通过 Git diff 确认所归档的 `integrations/goland`、`scripts` 与 `package.json` 完全一致，历史验收记录保持原提交身份。
- 初始文档 PR 只包含方案和索引。2026-09-26 用户授权在 `feat/plugin_rebuild_compose` 执行 S0；S0 使用隔离候选与专用测试 IDE，当时保留正式 Swing UI，不安装日常 IDE、不发布。
- 2026-09-27 用户授权完成 S1/S2；正式 Factory 已切换生产 Compose 内容，完整界面和状态/宿主验证已通过独立审查，见 [S1 验证记录](s1-verification.md)与 [S2 验证记录](s2-verification.md)。这是此前 S1/S2 的阶段范围。用户随后授权 rebase 主线并在新 goal 会话完成 S3/S4、推送现有 Draft PR 且确保最终 CI 通过；当前进度见 [S3记录](s3-verification.md)。
- 项目处于早期阶段：目标实现不保留旧 Swing 内容层、旧 UI 开关、双实现或向后适配层。IntelliJ 宿主容器、数据安全与声明范围内的依赖/API 正确性仍然需要保留。

## 文档导航

| 文档 | 状态 | 说明 |
|---|---|---|
| [Product Design 原型与实施](product-design.md) | active | 记录原型、真实字号和全文交互修复、当前候选证据与原生复验缺口。 |
| [技术方案](technical-design.md) | draft | 定义范围、宿主依赖、展示状态、线程、生命周期和旧代码清理边界。 |
| [分阶段任务](tasks/README.md) | draft | 按 S0–S4 拆分可执行子任务、依赖、所有权和阶段验收条件。 |
| [Subagent 协作方案](subagent-plan.md) | draft | 定义按需委派、模型核验、并发文件隔离、任务提示词和集成职责。 |
| [测试与验收方案](test-plan.md) | draft | 定义分层验证、覆盖迁移、真实交互、生命周期及候选证据要求，性能指标不参与验收。 |
| [调研依据](research.md) | draft | 区分已核对源码、官方能力、工程决策和仍需 S0 验证的假设。 |
| [S4 清理记录](s4-verification.md) | active | 跟踪旧实现删除、legacy及Desktop完整通过、剩余Compose列表边界和重载复验。 |
| [S3 集成记录](s3-verification.md) | active | 记录24组件、独立CI、原生输入/主题/重载/20轮资源证据和独立审查，G3 pass。 |
| [S2 验证记录](s2-verification.md) | active | 记录完整生产屏幕、18 个组件用例、同候选检查和独立审查，G2 pass。 |
| [S1 验证记录](s1-verification.md) | active | 记录生产状态/宿主实现、95 个直接测试、最小宿主与独立审查闭环。 |
| [S0 验证记录](s0-verification.md) | active | 记录精确工具链、可复现探针、功能检查、真实输入与内容循环、独立审查和 G0 通过结论。 |

## 执行顺序

`S0 技术验证 → G0 → S1 状态与宿主 → G1 → S2 Compose 界面 → G2 → S3 自动化集成 → G3 → S4 清理与最终验收 → G4`

阶段内允许按明确文件所有权并行；阶段验收条件未满足，不进入下一阶段的生产实现。后续阶段可以提前只读分析，不提前删除旧覆盖。任务详情是执行入口，技术方案不重复充当逐步操作清单。

## 与现有规范的关系

[插件兼容性与自动化方案](../ide-plugin-compatibility-automation/README.md)、[语言解耦方案](../ide-plugin-language-decoupling/README.md)及 [AGENTS.md](../../../AGENTS.md) 仍描述当前实现与有效约束。本需求包尚未将它们标为失效，也不借文档 PR 关闭现有检查。

“无需考虑兼容性”用于取消本次重构的历史 UI/旧 IDE 适配负担，不用于绕过信任、路径、所有权、产物检查或真实 IDE 验证。若 S0 确定必须改变 SDK/最低版本，实施时同步消除政策、构建校验和 descriptor 的冲突；不因此授权升级用户日常 IDE。现有记录继续保留为历史证据，不修改其原始结论。
