# GoLand 插件支持

本目录维护跨 IDE manifest、Desktop GoLand 入口和插件的通用契约，并保留原版本交付证据；语言相关条款已按 S1/S2 实现同步更新。

- 状态：active（通用契约继续有效；语言解耦已实施，V 已通过，原版本 GO 仍只对应原候选）。
- 更新日期：2026-09-19。
- 原工作分支：`feat/goland-plugin-support`。

## 当前规范与替代范围

GoLand 入口、受管模型、状态与目标版本现由[工作加载集合需求包](../goland-workspace-loading/README.md)替代。本包保留通用契约背景和原候选证据，不要求继续保留 workspace-root/excludes 运行链。

当前开发遵循[IDE 插件开发与测试规范](../../standards/ide-plugin-development-testing.md)和[语言解耦方案](../ide-plugin-language-decoupling/technical-design.md)。原需求/设计中的 `go.mod`、Go Modules registry、Go package 运行配置及语言工具链可用性成功条件已被替代，正文已同步为当前契约；manifest、路径、ownership、trust、PFI、同步恢复和 VCS 只读约束仍有效。

S1/S2 已删除 Go 成功门禁、专属通知/调度、错误链和显式 Go API 依赖；实现与 V 结果见 [2026-09-19 验收记录](../ide-plugin-language-decoupling/testing/acceptance-2026-09-19.md)。历史报告保留结论摘要与固定 Git 历史入口，不作为当前候选的验证证据。

## 文档

| 文档 | 类型 | 状态 | 说明 |
|---|---|---|---|
| [需求说明](requirements.md) | requirements | superseded | 维护通用支持契约，并在完成定义、场景与矩阵中落实语言无关成功条件。 |
| [技术方案](technical-design.md) | technical-design | superseded | 维护模型/PFI、安全、恢复和 VCS 只读设计；标明已删除的 Go 门禁与补偿的历史入口。 |
| [界面设计与截图](ui/README.md) | technical-design | active | 维护语言无关布局/状态契约，原交付截图明确归档且不证明当前候选。 |
| [测试与修复交付](testing/README.md) | testing | active | 路由新测试计划，保留原 GO 结论、历史证据入口及原交付边界。 |

## 当前实现边界

Desktop 是 `.reqws/workspace.json` 与仓库生命周期的唯一 writer。插件只读 manifest，自动同步能证明归 ReqWS 所有的项目模型条目，并验证 Workspace Model 与 ProjectFileIndex 目录边界后更新状态；不等待 Go registry 或语言工具链就绪。Git Root 由用户与 GoLand 维护，插件只读检查 Directory Mappings 差异。

本地 ZIP 不签名、不发布 Marketplace、不自动更新；不提供从 GoLand 操作仓库、双向通信、语言配置 writer、远程开发或其他操作系统支持。

## 历史材料

原 squash 交付已将必要探索和中间结论合并到修复报告。该报告中的 GO 只绑定其源码和工件，不能沿用到语言解耦候选。语言解耦有独立的实现、自动化和真实 GUI 验收记录；本次文档修订只纠正当前入口和契约，不改写原报告的 GO 或限制，也不新增功能验证结论。
