# IDE 插件语言解耦

本变更定义语言无关的插件职责，并用独立任务文档分别交接 S1、S2 的实现与最小回归，最后统一验收。

- 状态：active（S1 核心实现与必要回归已完成；S2 调度/依赖收尾与 V 验收尚未执行）。
- 更新日期：2026-09-18。
- 历史源码核对基线：`main@409b30e573d47348618620bbc0a52c0dd0710954`。后续实现使用 `refactor/ide-plugin-language-decoupling`，进度见 PR #8。
- 本次范围：按 [S1](tasks/s1-core-sync-decoupling.md) 删除 Go 主成功门禁、专属通知、直接错误/UI 分支及无效参数，同步迁移必要回归和当前说明；保留通用 follow-up 调度、共享追踪及 Go 构建依赖供 S2 收尾。不升级依赖或工具链，不改变 CI/Release、manifest/ownership schema 或用户配置。
- 实施组织：两个串行开发任务 S1/S2，各做必要回归，之后执行 V；不强制拆成多个 PR，强耦合时允许合并实施。

## 文档

| 文档 | 类型 | 状态 | 说明 |
|---|---|---|---|
| [总技术方案](technical-design.md) | technical-design | active | 维护 LD-01～LD-06、架构、安全边界、通知/依赖决策和任务归属。 |
| [实施任务](tasks/README.md) | tasks | active | 直接进入 S1/S2 独立文档，每份包含实现、最小回归、完成与交接条件。 |
| [测试](testing/README.md) | testing | active | 维护共享 fixture/覆盖规则与独立 V 最终系统回归，不预填结果。 |

## 权威性与交接

开发和测试遵循[IDE 插件开发与测试规范](../../standards/ide-plugin-development-testing.md)。总方案维护共同契约，任务正文维护具体执行步骤，最终命令/GUI 链仅在 V 文档维护；不要多处复制完整清单。开始开发时先读仓库 `AGENTS.md`，直接打开当前任务，边界不明确时再查关联章节。

本包替代旧 [GoLand 需求包](../goland-plugin-support/README.md)中的 Go registry/语言工具链成功条件及旧常规测试矩阵，不废除通用安全、ownership、manifest、同步恢复和 VCS 只读契约。

旧实现与[2026-09-13 验证报告](../goland-plugin-support/testing/verification-2026-09-13.md)保留原结论并提供固定 Git 历史入口；历史 GO 不适用于解耦后代码。后续按实际候选执行并记录检查，不创建空验收报告或宣称文档拆分等于功能已完成。
