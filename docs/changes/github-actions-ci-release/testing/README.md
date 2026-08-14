# GitHub Actions CI 与 Release 测试

本目录维护 CI、tag 校验、双架构 package 和 GitHub Release 发布事务的验证计划与按次证据。

| 文档 | 状态 | 说明 |
|---|---|---|
| [测试方案](test-plan.md) | active | 定义本地检查、GitHub 事件、失败分支和首次真实版本 tag 的验证要求。 |

真实 workflow 或 Release 验证完成后，使用日期命名的 `test-report` 记录运行链接、环境、资产摘要和遗留风险；在此之前不创建空验证报告。
