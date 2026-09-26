# Compose 重构分阶段任务

本索引将实现拆成五个阶段和二十二个子任务；每个阶段文档给出输入、允许变更、验证、阻塞条件和交接。

| 任务文档 | 状态 | 说明 |
|---|---|---|
| [S0 技术验证](s0-feasibility.md) | draft | 验证宿主依赖、真实输入和测试运行时，建立基线后决定是否继续。 |
| [S1 状态与宿主](s1-state-and-host.md) | draft | 固定状态/操作契约，接入有序订阅与内容生命周期。 |
| [S2 Compose 界面](s2-compose-ui.md) | draft | 替换完整内容界面并补齐语义、布局与可访问性测试。 |
| [S3 自动化集成](s3-test-automation.md) | draft | 接入独立 UI 测试和本机 Driver，验证真实集成而非服务直调。 |
| [S4 清理与验收](s4-cleanup-and-acceptance.md) | draft | 删除旧代码，复核候选并完成独立审查和运行证据。 |

## 依赖和并行

`S0.1 → S0.2 → S0.3 → S0.4/G0 → S1.1 → (S1.2 ∥ S1.3) → S1.4/G1 → (S2.1 ∥ S2.2) → S2.3 → S2.4/G2 → (S3.1 ∥ S3.2) → S3.3 → S3.4/G3 → S4.1 → S4.2 → S4.3 → S4.4 → S4.5 → S4.6/G4`

全套共 22 个子任务。并行符号表示可以分开文件与输出目录，不代表必须同时运行 GUI/Gradle。签名、构建和 shared catalog 由 Main 先处理，最多两个写入 Worker。具体规范见[协作方案](../subagent-plan.md)。

## 阶段状态记录

| 阶段 | 当前执行状态 | 退出条件 | 未执行验证 |
|---|---|---|---|
| S0 | planned | G0：技术路径与真实输入可用，基线可重复。 | CMP-V01/V02/V03/V11 |
| S1 | planned | G1：共享契约、有序状态与 disposal 有测试。 | CMP-V04/V05/V06 |
| S2 | planned | G2：完整组件功能和必要语义覆盖。 | CMP-V07/V08/V09 |
| S3 | planned | G3：CI 组件测试、本机真实输入与 Project 树集成。 | CMP-V03/V06/V09/V10 |
| S4 | planned | G4：旧 UI 清理、同候选检查与独立审查闭环。 | 全部必需项，重点 V02/V11/V12 |

阶段记录使用 planned、in-progress、blocked、completed；文档 frontmatter 的 draft/active 是另一维度，不混为实现完成度。开始实施时在本表链接真实记录与 commit，不把本计划生成日期当作测试日期。

## 接任务的方法

读取[技术方案](../technical-design.md)、本轮阶段文档和关联 V-ID，再由 Main 下发精确 allowlist 与直接测试。阶段完成即交接，不自动升级为安装、发布或无边界全仓重构。一个实现 PR 可以包含多个阶段 commit；不为任务数量强制创建多个 PR。
