# GoLand 插件支持

本目录维护跨 IDE manifest 契约、Desktop GoLand 入口和本地 GoLand 插件的当前需求、实现与修复交付依据。

- 状态：active（实现与约定验收已完成，结论为 `GO`）
- 更新日期：2026-09-13
- 工作分支：`feat/goland-plugin-support`

## 文档

| 文档 | 类型 | 状态 | 说明 |
|---|---|---|---|
| [需求说明](requirements.md) | requirements | active | 定义用户行为、范围、安全边界与 GL-01～GL-16 验收条件。 |
| [技术方案](technical-design.md) | technical-design | active | 定义 manifest、项目模型所有权、同步与恢复、VCS 只读诊断和可选追踪。 |
| [界面设计与截图](ui/README.md) | technical-design | active | 保留布局契约和经过复核的交付截图，注明各图的验证范围。 |
| [测试与修复交付](testing/README.md) | testing | active | 提供测试方案、最终 GO 修复报告、源码校验清单及交付边界。 |

## 交付边界

Desktop 是 `.reqws/workspace.json` 与仓库生命周期的唯一 writer。插件只读 manifest，自动同步能证明归 ReqWS 所有的项目模型条目，并在 authoritative Workspace Model、ProjectFileIndex 和 Go Modules registry 收敛后更新状态。Git Root 由用户与 GoLand 维护；插件只读检查并提示 Directory Mappings 差异。

本地 ZIP 不签名、不发布 Marketplace、不自动更新；不提供从 GoLand 操作仓库、双向通信、`go.work` writer、远程开发或其他操作系统支持。

## 文档整理范围

本次按 squash 交付整理：需求和验收条件保持；技术方案保留最终决策并补齐已实现的提示与追踪规则；测试方案继续维护可执行矩阵；修复报告同时承载交付范围、验证证据和已知限制。指南保留安装、Safe Mode 重开、手动 VCS、诊断与开发步骤。

探索计划、逐日中间报告、重复待办和已被替代的截图已移除，其必要结论归并到修复报告；不另建重复的交付说明或迁移文档。没有修改冻结参考资料，也没有发生发布或数据迁移。功能完成状态以测试索引内的统一报告为准，提交整理本身不代表 GUI 验收通过。
