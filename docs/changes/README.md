# 需求与变更索引

每个需求目录集中保存其生命周期材料；进入需求目录后，以该目录的 `README.md` 作为局部索引。

| 需求 | 状态 | 说明 | 更新日期 |
|---|---|---|---|
| [Code Humanizer 深度代码审查](code-humanizer-review/README.md) | active | 记录固定基线审查、R01–R06 与 C01/C02 整改契约及验证边界。 | 2026-09-26 |
| [Cursor IDE 工作区启动](cursor-ide-launch/README.md) | active | 让 Cursor 从 Agents Window 状态也能在新的 IDE 窗口中加载受管 `.code-workspace`。 | 2026-08-14 |
| [GitHub Actions CI 与 Release](github-actions-ci-release/README.md) | active | 定义 Desktop/GoLand 独立 CI 和版本 tag 发布流程，记录 v0.1.1 双架构正式发布与资产校验。 | 2026-09-13 |
| [全局设置与界面国际化](global-settings/README.md) | active | 新增 Settings 页面、全局默认目录与中英文界面支持。 | 2026-09-26 |
| [GoLand 工作加载集合与独立入口](goland-workspace-loading/README.md) | active | 独立入口与加载契约验收完成；后续详情方案一已实现并通过浏览器设计与交互验证，尚未发布。 | 2026-09-26 |
| [GoLand 插件 Marketplace 发布](goland-plugin-marketplace/README.md) | active | 实现独立插件签名、Release 后置提交与安全重试，分阶段完成首次上架和两版本市场验收。 | 2026-09-20 |
| [GoLand 插件支持](goland-plugin-support/README.md) | active | 通用需求/设计已同步 S1/S2 的语言无关契约，保留原交付证据并链接当前验收。 | 2026-09-19 |
| [IDE 插件兼容性与自动化回归](ide-plugin-compatibility-automation/README.md) | active | 最低 262、无上限；CI 保留平台/API，完整 IDE 自动化仅本机；记录平台/API 与本机正向回归，以及剩余验收边界。 | 2026-09-22 |
| [IDE 插件语言解耦](ide-plugin-language-decoupling/README.md) | active | S1/S2 已提交；V 完整自动化、双版本兼容、打包及必要真实 GUI 通过，GUI 为 manifest 边界集成。 | 2026-09-19 |
| [macOS 个人自用自更新](macos-self-update/README.md) | active | S1/S2、隔离升级、身份/Environment 配置及私有备份完成；正式 CI、两版本及干净用户验收待完成。 | 2026-09-19 |
| [Playwright 回归自动化](playwright-regression-automation/README.md) | active | S0–S3 的隔离 Electron/Git、D01–D12、真实 CI 和同包门禁已验证；S4/V 与手工替代仍待验。 | 2026-09-26 |
| [ReqWS Desktop MVP](mvp/README.md) | archived | 保存 MVP 1.0 的覆盖矩阵、交付快照和目标机验证证据。 | 2026-08-13 |

新增需求时使用 kebab-case 创建 `docs/changes/<需求标识或简短主题>/`，先建立局部 `README.md`，再按需添加文档并更新本索引。
