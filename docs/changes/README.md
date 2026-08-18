# 需求与变更索引

每个需求目录集中保存其生命周期材料；进入需求目录后，以该目录的 `README.md` 作为局部索引。

| 需求 | 状态 | 说明 | 更新日期 |
|---|---|---|---|
| [Cursor IDE 工作区启动](cursor-ide-launch/README.md) | active | 让 Cursor 从 Agents Window 状态也能在新的 IDE 窗口中加载受管 `.code-workspace`。 | 2026-08-14 |
| [GitHub Actions CI 与 Release](github-actions-ci-release/README.md) | active | 为 branch push 与 pull request 建立独立的 Desktop、GoLand 插件质量检查，并在版本 tag 后生成可校验的 macOS Release 资产。 | 2026-08-17 |
| [全局设置与界面国际化](global-settings/README.md) | active | 新增 Settings 页面、全局默认目录与中英文界面支持。 | 2026-08-14 |
| [GoLand 插件支持](goland-plugin-support/README.md) | active | 已完成跨 IDE 契约、Desktop 入口与插件实现；VCS ownership review 修复已通过自动化与 261/262 Verifier，但后台 whole-list writer 的不可观察覆盖窗口仍是架构阻塞，完整 GUI 矩阵亦待验证。 | 2026-08-18 |
| [ReqWS Desktop MVP](mvp/README.md) | archived | 保存 MVP 1.0 的覆盖矩阵、交付快照和目标机验证证据。 | 2026-08-13 |

新增需求时使用 kebab-case 创建 `docs/changes/<需求标识或简短主题>/`，先建立局部 `README.md`，再按需添加文档并更新本索引。
