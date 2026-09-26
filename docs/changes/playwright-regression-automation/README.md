# Playwright 回归自动化

本需求包维护 Electron 真实链路自动化，并规划衔接现有本机 GoLand 回归，减少日常开发中重复的 Computer Use 操作。

| 文档 | 状态 | 说明 |
|---|---|---|
| [自动化方案与改造流程](technical-design.md) | active | 定义真实链路、测试隔离、D01–D12 用例、CI/本机边界、S0–S4/V 实施阶段与手工替代门槛。 |
| [子代理实施分工](subagent-plan.md) | draft | 定义各阶段的委派、文件/资源所有权、交接和集成约束，引用统一编码模型政策。 |
| [旧验收步骤与替代登记](manual-inventory.md) | draft | 追溯旧断言、已实现 D01–D12 选择器及 S4/V 前仍保留的范围。 |
| [S0–S3 实施与验证记录](implementation-2026-09-26.md) | active | 记录 S0–S3 的最终代码候选、真实 CI、稳定性和保留的手工边界。 |

## 当前状态

2026-09-26 在 `feat/playwright-automation` 完成 S0–S3：共用启动、隔离 Electron/HTTPS Git fixture、D01–D12、严格报告/负向门禁、CI job 和同包 smoke 已实现并验证。最终代码候选 `56657ea` 的 18 项 Electron、两项故障探针、同包 smoke 和 Desktop 聚合门禁通过；核心 smoke 连续 20 轮共 80 项通过。候选身份、CI 修复过程与实际证据见[实施记录](implementation-2026-09-26.md)。S4 联动和 V 手工替代仍未实施，不撤销现有验收门槛。

方案入库基于 main `fc7c31a69128039a31c0f0bc9cc0eb368feaca1c`（0.1.6），已对齐 PR #21 合入后的插件规则，不沿用早期调研中的旧 build 上下限或 CI 完整 IDE 建议。

开发子代理的模型和 reasoning 规则统一见 [Agent 协作指南第 8 节](../../guides/agent-workflow.md#8-开发子代理)；本次有分域实现与只读审查，不宣称进行了模型适配器或子代理评测。

## 与已有工作的关系

沿用[插件兼容性与自动化回归](../ide-plugin-compatibility-automation/README.md)及[本机完整 IDE 入口](../ide-plugin-compatibility-automation/local-integration.md)：最低 262、无上限；CI 保留编译、平台与 API 检查；完整 GoLand 和新的 Desktop→IDE 联动仅在本机获准的专用环境运行。

已有 Starter/Driver 场景、候选 ZIP 校验、授权/profile 隔离和报告入口继续复用。既有正向记录不等于新联动或完整 V 验收通过，CI 绿色也不代表本机 UI 通过。

## 实施入口

按技术方案第 9 节依次推进 `S0 → S1 → S2/S3`，先建立 Electron 主线；S4 扩展本机跨进程联动，V 验证替代关系后才撤销对应的重复手工步骤。不因任务编号机械拆成多个 PR，不先删旧门禁再补自动化。

实际开发仍按[开发指南](../../guides/development-guide.md)和[Agent 协作指南](../../guides/agent-workflow.md)执行。纯文档检查使用 `npm run docs:check`；发布、真实用户数据、IDE 安装或授权操作不包含在本次实施中。
