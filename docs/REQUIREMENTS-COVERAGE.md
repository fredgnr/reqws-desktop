# MVP 需求覆盖矩阵

| 方案条目 | 实现位置 | 验证 |
|---|---|---|
| US-01 Repository Catalog | `repository-service.ts`、Repositories UI、repository IPC | repository/service/schema/renderer tests |
| URL 推导名称与可编辑字段 | shared/renderer repository utils、RepositoryDialog | shared-utils、renderer tests |
| 连接测试失败仍可保存 | GitRunner `lsRemote`、repository handler/dialog | IPC、GitRunner、renderer tests |
| US-02 多仓库 workspace | WorkspaceService、CreateWorkspaceDialog | workspace integration、renderer tests |
| 每 repo 完整独立 clone | GitRunner `clone`、独立 `.git` 校验 | Git/workspace integration、安全回归 |
| feature/default branch 语义 | BranchService | local bare remote integration tests |
| sibling staging、非覆盖发布 | WorkspaceService、atomic writer | rollback/TOCTOU tests |
| 独立 workspace file 目录 | WorkspaceFileWriter、目录 IPC/表单 | writer、IPC、renderer tests |
| 记住两个目录 | AppSettings/getSettings IPC、创建表单 defaults | state、IPC、renderer tests |
| US-03 多字段搜索 | shared search/renderer utils、WorkspacesPage | shared/renderer tests |
| Ready/Missing/Error | WorkspaceService evaluate/load、详情恢复 UI | workspace integration、renderer tests |
| US-04 增加仓库 | WorkspaceService add、详情 drawer | workspace integration |
| 已有匹配 clone 可安全复用 | origin + contained real `.git` 校验 | path/workspace security tests |
| US-05 逻辑移除且保留磁盘 | WorkspaceService remove、确认文案 | integration、renderer tests |
| US-06 VS Code/Cursor/Finder | EditorLauncher、editor IPC/UI | editor launcher/IPC tests |
| Git 缺失降级 | main service factory、availability UI | IPC/editor tests |
| typed narrow preload | shared ReqwsAPI、Preload | preload contract tests |
| main-side schema validation | Zod schemas、全部 invoke handlers | schema/IPC tests |
| Electron renderer hardening | create-window/security | security tests |
| JSON 原子写入/损坏备份 | AtomicJsonStore/AppStateStore | atomic/state tests |
| manifest 快照与 managed workspace | WorkspaceFileWriter/WorkspaceService | writer/workspace tests |
| 并发 mutation 不丢更新 | shared WorkspaceMutationCoordinator | concurrency integration tests |
| 不保存/输出 Git 凭据 | URL schemas、GitRunner redaction | schema/Git/repository tests |
| 不 push、不 worktree、不删除 repo | Git/Workspace service 命令范围 | integration tests + code audit |
| 本机 package/install/update 脚手架 | `scripts/install-macos.mts`、Forge app identity/signing、npm scripts | catchable-failure transaction、interrupted-artifact fail-closed tests + target Mac package/install smoke |

macOS GUI、Finder、已安装编辑器、SSH Agent 与公开 HTTPS transport 已在目标机完成 smoke，证据列在 `VERIFICATION.md`。由于目标机没有一个已由 credential helper 无交互授权、且不会被 Git 配置改写为 SSH 的私有 HTTPS fixture，私有 HTTPS helper 认证仍标记为外部证据缺口。
