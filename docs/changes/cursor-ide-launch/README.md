# Cursor IDE 工作区启动

本目录记录 Cursor 已处于 Agents Window 时，ReqWS 仍显式打开 IDE 工作区窗口的当前需求、实现决策和验证证据。

- 状态：active
- 更新日期：2026-08-14

## 文档

| 文档 | 类型 | 状态 | 说明 |
|---|---|---|---|
| [需求与技术方案](technical-design.md) | technical-design | active | 定义 IDE 窗口语义、内置 CLI 启动与兼容回退、错误处理和验收范围。 |
| [2026-08-14 验证记录](verification-2026-08-14.md) | test-report | active | 记录 Cursor Agents Window 到多根 IDE workspace 的真实 GUI smoke 与自动检查结果。 |

本次局部修复由一份合并方案承载需求、设计和测试计划，避免重复的需求文档；按次自动检查和真实 macOS GUI smoke 证据单独记录。没有发布、数据迁移或独立回滚流程，因此不创建交付文档。
