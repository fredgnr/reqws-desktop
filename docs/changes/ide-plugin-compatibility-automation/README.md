# IDE 插件兼容性与自动化回归

本改造将最低支持范围扩展到 262 系列，取消预设兼容上限，并用自动化测试替代常规 computer use 回归。

| 文档 | 状态 | 说明 |
|---|---|---|
| [改造方案](technical-design.md) | active | 定义兼容政策、API 矩阵调度与 CI/本机完整 IDE 集成的边界。 |
| [实施与验收](implementation-plan.md) | active | 拆分实施任务，列出迁移门槛、负向测试和最终交接要求。 |
| [开发记录](implementation-2026-09-21.md) | active | 记录 S1–S3 开发、用例迁移映射、运行入口及当时未执行的验证。 |
| [验证记录](verification-2026-09-21.md) | active | 记录平台/API 回归、早期授权阻塞及 CI 分片日志验证；不代替完整 V 验收。 |
| [本机回归记录](local-verification-2026-09-22.md) | active | 记录获准启动 IDE 后的自动场景、宿主隔离修复和剩余验收边界。 |
| [本机集成入口](local-integration.md) | active | 说明专用环境账号授权准备、显式 ZIP 自动集成、状态隔离和独立报告。 |

## 当前状态

2026-09-22 已移除 API 矩阵的 `max-parallel` 限制，工作流测试要求不再设置该字段；并发由 GitHub 的可用 runner 和账户配额决定。每周两组候选仍按阶段验证，全部必需目标和同一 ZIP 的汇总校验继续保留。此前开发记录中的双并发描述仅代表当时实现。

2026-09-21 已加入统一策略、产物检查、API 目标解析/汇总、固定环境 Starter/Driver 场景及 CI/Release/每周工作流。前两轮按用户要求仅开发；用户随后授权测试、回归和推送，实际结果见[验证记录](verification-2026-09-21.md)。随后用户授权继续 IDE 回归，固定代表环境正向集成及宿主修复见[本机回归记录](local-verification-2026-09-22.md)。完整 V 验收仍未完成，不能宣称自动化替代已全部生效。

当前执行策略已调整：CI 保留编译、单元、Light/Heavy 平台、禁用 API、结构/产物策略及跨版本 Verifier；完整 GoLand 的 Starter/Driver 三组自动场景仅在本机固定 2026.2.1.1 运行。所有工作流不要求 License Server 或 IDE 授权，CI 结果不代表本机 UI 通过。专用测试环境允许 JetBrains Account 交互登录，License Server 可选；本机回归使用 IDE 实际显示的有效试用，未验证 Account 登录和长期授权复用。

目标描述为 `<idea-version since-build="262"/>`。用户说的“最低 262.*”表示从整个 262 系列开始，不是把通配符写进 `since-build`。`until-build` 和 `strict-until-build` 都不设置。

本方案取代此前“最低固定 2026.2.1.1 的精确 build”和“按已验证主版本设置严格上限”的目标建议。当前 GoLand 产品限制保持不变；新增 IDEA 产品支持不包含在本次改造内。

现有自动检查继续运行。旧规范中与目标冲突的单一 build、常规手工 GUI 要求，按实施与验收中的迁移条件逐项替换，不先删除门禁再等待自动化补齐。
