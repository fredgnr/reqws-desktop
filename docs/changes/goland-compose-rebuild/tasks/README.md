# Compose 重构分阶段任务

本索引将实现拆成五个阶段和二十二个子任务；每个阶段文档给出输入、允许变更、验证、阻塞条件和交接。

| 任务文档 | 状态 | 说明 |
|---|---|---|
| [S0 技术验证](s0-feasibility.md) | active | S0 已完成、G0 已通过；宿主依赖、真实输入、内容生命周期和测试运行时验证满足后续迁移的前置条件。 |
| [S1 状态与宿主](s1-state-and-host.md) | active | 固定状态/操作契约，接入有序订阅与内容生命周期。 |
| [S2 Compose 界面](s2-compose-ui.md) | active | 替换完整内容界面并补齐语义、布局与可访问性测试。 |
| [S3 自动化集成](s3-test-automation.md) | active | 接入独立 UI 测试和本机 Driver，验证真实集成而非服务直调。 |
| [S4 清理与验收](s4-cleanup-and-acceptance.md) | active | 删除旧代码，复核候选并完成独立审查和运行证据。 |

## 依赖和并行

`S0.1 → S0.2 → S0.3 → S0.4/G0 → S1.1 → (S1.2 ∥ S1.3) → S1.4/G1 → (S2.1 ∥ S2.2) → S2.3 → S2.4/G2 → (S3.1 ∥ S3.2) → S3.3 → S3.4/G3 → S4.1 → S4.2 → S4.3 → S4.4 → S4.5 → S4.6/G4`

全套共 22 个子任务。并行符号表示可以分开文件与输出目录，不代表必须同时运行 GUI/Gradle。签名、构建和 shared catalog 由 Main 先处理，最多两个写入 Worker。具体规范见[协作方案](../subagent-plan.md)。

## 阶段状态记录

| 阶段 | 当前执行状态 | 退出条件 | 未完成验证 |
|---|---|---|---|
| S0 | completed | [本轮记录](../s0-verification-2026-09-26.md)：依赖、组件、真实输入与内容生命周期通过，`7ad3387` 独立审查完成；按用户要求取消性能门禁后 G0 pass。 | 无 S0 必需项遗留；后续阶段仍按各自范围验证。 |
| S1 | completed | [S1 实施记录](../s1-verification-2026-09-27.md)：G1 pass，`cccc3de` 独立审查闭环；共享契约、有序状态和真实 Content 生命周期已验证。 | 无本阶段必需项遗留；V06 的完整卸载/重载仍由 S3 扩充。 |
| S2 | completed | [S2 验证记录](../s2-verification-2026-09-27.md)：G2 pass，`34f1392` 独立审查与最终同候选验证闭环。 | 无本阶段必需项遗留；CI 图形、系统主题/输入和 VoiceOver 仍由 S3 扩充。 |
| S3 | completed | [S3记录](../s3-verification-2026-09-27.md)：24组件、CI、原生输入/主题/重载和20轮Content通过独立审查，G3 pass。 | VoiceOver按用户要求豁免，未运行。 |
| S4 | in-progress | [S4记录](../s4-verification-2026-09-27.md)：旧UI已清理，新UI修复候选自动门禁通过。 | legacy已通过；Compose滚动与Desktop独立状态观察待复验，随后审查与G4。 |

阶段记录使用 planned、in-progress、blocked、completed；文档 frontmatter 的 draft/active 是另一维度，不混为实现完成度。开始实施时在本表链接真实记录与 commit，不把本计划生成日期当作测试日期。

## 接任务的方法

读取[技术方案](../technical-design.md)、本轮阶段文档和关联 V-ID，再由 Main 下发精确 allowlist 与直接测试。阶段完成即交接，不自动升级为安装、发布或无边界全仓重构。一个实现 PR 可以包含多个阶段 commit；不为任务数量强制创建多个 PR。
