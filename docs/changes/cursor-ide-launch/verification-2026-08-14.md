---
title: Cursor IDE 工作区启动验证记录
type: test-report
status: active
updated: 2026-08-14
---

# Cursor IDE 工作区启动验证记录

本文记录 2026-08-14 在真实 Cursor Agents Window 状态下验证新 IDE workspace 启动方式，以及最终代码树的自动检查结果。

## 1. 验证环境

| 项目 | 值 |
|---|---|
| macOS | 26.2（25C56） |
| 架构 | arm64 |
| Cursor | 3.15.19，安装于 `/Applications/Cursor.app` |
| Node.js 完整检查 | v24.19.0 |
| workspace fixture | 两个现存根目录 `protobuf-api`、`go-useragent` |

fixture 使用此前 MVP smoke 保留的受管 `.code-workspace`；本次只读取该文件并打开编辑器，没有修改仓库内容、Cursor 设置或 ReqWS state。

## 2. 真实 Cursor GUI smoke

验证前通过 macOS accessibility tree 确认 Cursor 的活动窗口标题为：

```text
Cursor Agents
```

随后执行与新实现首选路径一致的应用内置 CLI：

```text
/Applications/Cursor.app/Contents/Resources/app/bin/cursor \
  editor --new-window \
  <multi-root.code-workspace>
```

命令退出码为 `0`。再次检查 Cursor UI，得到：

- 新窗口标题为 `reqws-smoke-happy-20260813-112005 (Workspace)`；
- 窗口具有 Explorer、Editor、Terminal 和状态栏等完整 IDE 区域；
- Explorer 同时显示 `.code-workspace` 中的 `protobuf-api` 与 `go-useragent` 两个根目录；
- 原 workspace 文件没有被当作普通 JSON 文档打开；
- 未使用 help 中标记为开发参数的 `--classic`。

因此 `editor --new-window` 可以在 Cursor 已处于 Agents Window 时创建独立 IDE workspace，并让 multi-root 配置生效。

## 3. 自动检查

定向回归：

```text
npx vitest run tests/unit/editor-launcher.test.ts
```

结果：1 个测试文件、14 项测试通过。覆盖首选 `bin/cursor`、旧版 `bin/code`、LaunchServices 回退、system/user Applications 路径、workspace/root 目标、remote CLI hook 过滤和启动失败。

第一次直接执行 `npm run check` 时，当前 shell 使用 Node v22.14.0；安装脚手架的 Node 24 环境测试按预期拒绝该路径，其他 24 个测试文件、212 项测试通过。切换到仓库要求的 Node v24.19.0 后重新执行完整检查，等价的可复现命令为：

```text
nvm use
npm run check
```

最终结果：

| 检查 | 结果 |
|---|---|
| TypeScript | 通过 |
| ESLint | 通过 |
| i18n | 269 keys，一致 |
| documentation | 通过 |
| Vitest | 25 个测试文件、214 项测试通过 |

## 4. 证据边界

- 真实 GUI smoke 验证了当前 Cursor 3.15.19 的首选 CLI 和多根 workspace；没有为了兼容测试替换本机 Cursor 为旧版本。
- `bin/code` 和 LaunchServices 回退只由进程参数单元测试覆盖。LaunchServices 是异常 bundle 的降级能力，不承诺解决 Agents Window handoff。
- GUI smoke 直接执行了实现选定的固定 CLI；ReqWS 到该命令的代码连接由 `EditorLauncher` 单元测试覆盖，没有修改用户 ReqWS state 来创建额外测试记录。
- root 入口的目标参数由自动测试覆盖，本次没有再打开一个额外真实 Cursor 窗口。
