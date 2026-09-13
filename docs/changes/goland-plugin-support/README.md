# GoLand 插件支持

本目录保留跨 IDE manifest、Desktop GoLand 入口及已交付插件的通用契约、实现和验证依据，并说明后续语言解耦的替代范围。

- 状态：active（本目录记录的原实现已完成当时约定的 GO；不代表后续解耦已实现）。
- 更新日期：2026-09-13。
- 原工作分支：`feat/goland-plugin-support`。

## 后续规范与替代范围

后续开发遵循[IDE 插件开发与测试规范](../../standards/ide-plugin-development-testing.md)和[语言解耦方案](../ide-plugin-language-decoupling/README.md)。本目录需求/设计中 `go.mod`、Go Modules registry、Go package 运行配置及语言工具链可用性作为成功条件的条款，已被新方案选择性替代，不再作为未来候选验收要求；其余 manifest、路径、ownership、trust、同步恢复和 VCS 只读约束仍有效。

代码仍包含旧 Go 检查链，清理尚未实施。旧技术细节和使用指南可解释该版本实际行为，不能反过来扩大新规范范围；实现交付时再同步更新当前行为说明。历史报告与校验清单保持原样。

## 文档

| 文档 | 类型 | 状态 | 说明 |
|---|---|---|---|
| [需求说明](requirements.md) | requirements | active | 保留原支持契约；Go 专属成功条款按上文由新规范替代。 |
| [技术方案](technical-design.md) | technical-design | active | 保留现有实现及通用安全/恢复设计；Go 检查与补偿是待清理的旧行为。 |
| [界面设计与截图](ui/README.md) | technical-design | active | 保留布局契约和经过复核的原交付截图，不作为解耦后状态语义证明。 |
| [测试与修复交付](testing/README.md) | testing | active | 路由新测试计划，保留原 GO 报告、源码校验清单及原交付边界。 |

## 原版本交付边界

Desktop 是 `.reqws/workspace.json` 与仓库生命周期的唯一 writer。原插件只读 manifest，自动同步能证明归 ReqWS 所有的项目模型条目，并等待 Workspace Model、ProjectFileIndex 和 Go Modules registry 后更新状态。最后一层是待删除的旧实现，不是新版本的目标。Git Root 由用户与 GoLand 维护，插件只读检查 Directory Mappings 差异，这个边界不变。

本地 ZIP 不签名、不发布 Marketplace、不自动更新；不提供从 GoLand 操作仓库、双向通信、语言配置 writer、远程开发或其他操作系统支持。

## 历史材料

原 squash 交付已将必要探索和中间结论合并到修复报告。该报告中的 GO 只绑定其源码和工件，不能沿用到语言解耦候选。此次仅更新规范、方案及导航，没有代码清理、发布、安装或数据迁移。
