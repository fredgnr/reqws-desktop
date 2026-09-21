# 需求与变更索引

每个目录放一项需求的背景、设计、测试和交付记录。从目录内的 README 继续查找；`active` 表示材料仍有效，不表示所有计划都已完成。

| 需求 | 状态 | 说明 | 更新日期 |
|---|---|---|---|
| [文档改进与本地交接](documentation-refresh/README.md) | active | 重写产品首页和使用路径，列出配图、中文复核和本地验收任务。 | 2026-09-21 |
| [Cursor IDE 工作区启动](cursor-ide-launch/README.md) | active | 让 Cursor 从 Agents Window 状态也能在新的 IDE 窗口中打开 `.code-workspace`。 | 2026-08-14 |
| [GitHub Actions CI 与 Release](github-actions-ci-release/README.md) | active | 说明 Desktop/GoLand 独立 CI、版本 tag 发布流程及 v0.1.1 双架构发布记录。 | 2026-09-13 |
| [全局设置与界面国际化](global-settings/README.md) | active | 说明设置页、默认目录和中英文界面的实现与验证。 | 2026-08-14 |
| [GoLand 工作加载集合与独立入口](goland-workspace-loading/README.md) | active | 记录独立入口和加载选择的验收，以及后续详情界面的实现与验证范围。 | 2026-09-20 |
| [GoLand 插件 Marketplace 发布](goland-plugin-marketplace/README.md) | active | 实现独立插件签名、Release 后提交和安全重试；首次上架及市场更新按阶段验收。 | 2026-09-20 |
| [GoLand 插件支持](goland-plugin-support/README.md) | active | 通用需求和设计已更新为语言无关规则，旧交付证据保留并链接当前验收。 | 2026-09-19 |
| [IDE 插件语言解耦](ide-plugin-language-decoupling/README.md) | active | 记录 S1/S2 及其完整自动化、双版本兼容、打包和必要 GUI 检查；结论只适用于该候选。 | 2026-09-19 |
| [macOS 个人自用自更新](macos-self-update/README.md) | active | 记录更新方案、身份与 Environment 配置、私有备份及分阶段验收；发布现状另查 Release。 | 2026-09-19 |
| [ReqWS Desktop MVP](mvp/README.md) | archived | 保存 MVP 1.0 的需求覆盖、交付快照和目标机验证证据。 | 2026-08-13 |

新增需求时，用 kebab-case 创建目录，先写清局部 README，再按需补充材料。普通用户的安装和操作步骤见[使用指南索引](../guides/README.md)，不必从这些技术记录开始。
