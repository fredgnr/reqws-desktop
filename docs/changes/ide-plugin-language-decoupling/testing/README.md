# IDE 插件语言解耦测试

本目录维护共同测试规则及最终验收；每个开发子任务的最小回归直接放在对应任务文档中。

| 文档 | 类型 | 状态 | 说明 |
|---|---|---|---|
| [总测试方案](test-plan.md) | test-plan | active | 维护普通 Git fixture、T-01～T-07、选例与证据规则，导航到各阶段。 |
| [最终系统回归与验收](final-acceptance.md) | test-plan | active | S1/S2 完成后统一执行完整插件、兼容、文档和最小真实 GUI 链；尚未执行。 |

开发过程中直接使用[S1 的实施与最小回归](../tasks/s1-core-sync-decoupling.md)或[S2 的实施与最小回归](../tasks/s2-scheduling-dependency-cleanup.md)，不要把 V 的全量要求逐项复制到两个子任务。

S1 已提交，阶段证据见 [S1 实施记录](../tasks/s1-core-sync-decoupling.md#8-本轮实施记录2026-09-18)；S2 核心代码已落实，当前输入与必要回归证据见 [S2 实施记录](../tasks/s2-scheduling-dependency-cleanup.md#8-本轮实施记录2026-09-18)。V 尚未执行。旧 verification 属于旧工件，不是本次候选证据；零匹配、全跳过、不明缓存任务状态和阶段通过均不能单独充当完整验收。完成实际验证后再新增真实按次报告并更新本索引。
