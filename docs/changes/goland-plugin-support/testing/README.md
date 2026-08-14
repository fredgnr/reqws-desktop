# GoLand 插件支持测试与验证

本目录保存 GoLand 插件支持的测试范围和后续按次验证证据。

- 状态：draft
- 更新日期：2026-08-14

## 文档

| 文档 | 类型 | 状态 | 说明 |
|---|---|---|---|
| [测试方案](test-plan.md) | test-plan | draft | 定义 Desktop、插件、JetBrains Platform、真实 macOS GUI、规模和安全测试。 |

实现期间每次形成可复现的真实 GoLand 验证后，再新增 `verification-YYYY-MM-DD.md` 并更新本索引。验证报告必须记录 exact commit、GoLand build、macOS、JDK/JBR、插件 ZIP SHA-256、fixture 和完整结果；不提前创建空报告。
