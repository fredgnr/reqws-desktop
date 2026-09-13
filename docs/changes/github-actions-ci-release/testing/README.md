# GitHub Actions CI 与 Release 测试

本目录维护完整 CI、tag 校验、arm64 app/GoLand 插件 ZIP、缓存和 GitHub Release 发布事务的验证计划与按次证据。

| 文档 | 状态 | 说明 |
|---|---|---|
| [测试方案](test-plan.md) | active | 定义完整检查、插件附件回归、冷/热缓存、并行发布门禁和真实 tag 的验证要求。 |
| [v0.1.1 发布验证](verification-2026-09-13.md) | active | 保留历史真实 tag、双架构构建和公开资产摘要复核结果，不作为新流程的验证证明。 |

真实 workflow 或 Release 验证完成后，使用日期命名的 `test-report` 记录运行链接、环境、资产摘要和遗留风险；在此之前不创建空验证报告。
