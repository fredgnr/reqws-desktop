# Playwright 回归自动化

本需求包维护 Electron 真实链路与本机 GoLand 联动自动化，按已验证的断言范围替代重复 Computer Use。

| 文档 | 状态 | 说明 |
|---|---|---|
| [自动化方案与改造流程](technical-design.md) | active | 定义真实链路、测试隔离、D01–D12 用例、CI/本机边界、S0–S4/V 实施阶段与手工替代门槛。 |
| [子代理实施分工](subagent-plan.md) | draft | 定义各阶段的委派、文件/资源所有权、交接和集成约束，引用统一编码模型政策。 |
| [旧验收步骤与替代登记](manual-inventory.md) | active | 对 49 个原 ID 分别记录自动替代、部分替代或保留，并关联实际候选与证据。 |
| [S0–S4 实施与验证记录](implementation-2026-09-26.md) | active | 按阶段记录 Desktop/CI 与本机 GoLand 联动、初始 JPS 修复、受控 API 例外及各自验证边界。 |
| [V 替代验收记录](verification-v-2026-09-26.md) | active | 记录 v7 两轮完整联动、legacy、故意失败、有限成本样本及按范围完成的规范切换。 |

## 当前状态

S0–S3 的共用启动、隔离 Electron/HTTPS Git fixture、D01–D12、严格门禁及 CI 同包 smoke 已完成；当时 `56657ea` 的 18 项 Electron、两项故障探针和核心 smoke 20 轮共 80 项通过，见[实施记录](implementation-2026-09-26.md)。S4 同一 CI ZIP 的真实 Desktop→Driver 联动、初始 JPS 修复与原生落盘结果见 [S4 记录](implementation-2026-09-26.md#s4-本机联动)。

2026-09-27 完成 [V 的按范围替代验收](verification-v-2026-09-26.md)：普通 Desktop 30 项重复两次，后续 D02/D01 补充各重复 20 次；`e7a25f2` 宿主在原始 CI ZIP 上两轮各四项/九进程、32 条投影及五份落盘证明通过，legacy 三项/九进程通过，当前宿主准确拒绝 watcher 负向 ZIP。所有首次失败、修复和私有证据保留。按[逐项裁决](manual-inventory.md)撤销等价范围的重复手工操作，原生 picker、焦点/外部应用、签名安装/真实升级及明确缺口继续保留。有限成本样本不证明全套减少 80% 或长期可靠性；没有新推送或发布。

方案入库基于 main `fc7c31a69128039a31c0f0bc9cc0eb368feaca1c`（0.1.6），已对齐 PR #21 合入后的插件规则，不沿用早期调研中的旧 build 上下限或 CI 完整 IDE 建议。

开发子代理的模型和 reasoning 规则统一见 [Agent 协作指南第 8 节](../../guides/agent-workflow.md#8-开发子代理)；本次有分域实现与只读审查，不宣称进行了模型适配器或子代理评测。

## 与已有工作的关系

沿用[插件兼容性与自动化回归](../ide-plugin-compatibility-automation/README.md)及[本机完整 IDE 入口](../ide-plugin-compatibility-automation/local-integration.md)：最低 262、无上限；CI 保留编译、平台与 API 检查；完整 GoLand 和新的 Desktop→IDE 联动仅在本机获准的专用环境运行。

已有 Starter/Driver 场景、候选 ZIP 校验、授权/profile 隔离和报告入口继续复用。既有正向记录不等于新联动或完整 V 验收通过，CI 绿色也不代表本机 UI 通过。

## 实施入口

按技术方案第 9 节依次推进 `S0 → S1 → S2/S3`，先建立 Electron 主线；S4 扩展本机跨进程联动，V 验证替代关系后才撤销对应的重复手工步骤。不因任务编号机械拆成多个 PR，不先删旧门禁再补自动化。

实际开发仍按[开发指南](../../guides/development-guide.md)和[Agent 协作指南](../../guides/agent-workflow.md)执行。纯文档检查使用 `npm run docs:check`；发布、真实用户数据、IDE 安装或授权操作不包含在本次实施中。
