# 需求与变更索引

每个需求目录集中保存其生命周期材料；进入需求目录后，以该目录的 `README.md` 作为局部索引。

| 需求 | 状态 | 说明 | 更新日期 |
|---|---|---|---|
| [Cursor IDE 工作区启动](cursor-ide-launch/README.md) | active | 让 Cursor 从 Agents Window 状态也能在新的 IDE 窗口中加载受管 `.code-workspace`。 | 2026-08-14 |
| [GitHub Actions CI 与 Release](github-actions-ci-release/README.md) | active | 定义 Desktop/GoLand 独立 CI 和版本 tag 发布流程，记录 v0.1.1 双架构正式发布与资产校验。 | 2026-09-13 |
| [全局设置与界面国际化](global-settings/README.md) | active | 新增 Settings 页面、全局默认目录与中英文界面支持。 | 2026-08-14 |
| [GoLand 工作加载集合与独立入口](goland-workspace-loading/README.md) | active | 独立入口与加载契约验收完成；后续详情方案一已实现并通过浏览器设计与交互验证，尚未发布。 | 2026-09-20 |
| [GoLand 插件支持](goland-plugin-support/README.md) | active | 通用需求/设计已同步 S1/S2 的语言无关契约，保留原交付证据并链接当前验收。 | 2026-09-19 |
| [IDE 插件语言解耦](ide-plugin-language-decoupling/README.md) | active | S1/S2 已提交；V 完整自动化、双版本兼容、打包及必要真实 GUI 通过，GUI 为 manifest 边界集成。 | 2026-09-19 |
| [macOS 个人自用自更新](macos-self-update/README.md) | active | S1/S2、隔离升级、身份/Environment 配置及私有备份完成；正式 CI、两版本及干净用户验收待完成。 | 2026-09-19 |
| [ReqWS Desktop MVP](mvp/README.md) | archived | 保存 MVP 1.0 的覆盖矩阵、交付快照和目标机验证证据。 | 2026-08-13 |

新增需求时使用 kebab-case 创建 `docs/changes/<需求标识或简短主题>/`，先建立局部 `README.md`，再按需添加文档并更新本索引。
