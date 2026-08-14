---
title: Cursor IDE 工作区启动需求与技术方案
type: technical-design
status: active
updated: 2026-08-14
---

# Cursor IDE 工作区启动需求与技术方案

本文定义 Cursor 已处于 Agents Window 时，ReqWS 如何显式打开新的 IDE 窗口并加载受管 `.code-workspace`。

## 1. 问题与范围

ReqWS 原先通过以下 LaunchServices 请求打开 Cursor：

```text
/usr/bin/open -a Cursor <workspace-file-or-root>
```

该请求只指定应用和目标路径，没有表达“打开新 IDE 窗口”的意图。Cursor 已处于 `Cursor Agents` 窗口时，当前窗口可以接收或忽略文件打开事件，导致按钮返回成功但 `.code-workspace` 中的多根目录没有生效。

本次只修复 Cursor 启动策略，不改变：

- `.code-workspace` 文件格式、路径或生命周期；
- workspace、manifest 和全局 state；
- Renderer 按钮、preload API、IPC channel、schema 或错误码；
- VS Code 和 Finder 的启动方式。

## 2. 需求与验收条件

“Cursor”按钮和“用 Cursor 打开代码目录”都必须打开新的完整 IDE 窗口，而不是把目标交给现有 Agents Window。

验收条件：

1. Cursor 已显示 `Cursor Agents` 时，打开受管 workspace 会出现标题对应 `<name> (Workspace)` 的新 IDE 窗口。
2. IDE Explorer 加载 `.code-workspace` 中的全部根目录；目标文件不能被当作普通 JSON 文档打开。
3. 打开代码目录的回退入口也进入新的 IDE 窗口，并以 workspace root 为目标。
4. Cursor 未运行时使用同一启动策略，不要求用户安装 PATH 中的 `cursor` shell command。
5. `/Applications/Cursor.app` 与 `~/Applications/Cursor.app` 都从实际检测到的 bundle 解析启动器。
6. 路径或应用不存在时沿用现有稳定错误；所有进程使用固定可执行文件、参数数组和 `shell: false`。

始终创建新 IDE 窗口是有意行为：它避免 Agents Window 的活动状态决定 workspace 是否生效。

## 3. 启动决策

### 3.1 首选内置 Cursor editor CLI

从检测到的应用 bundle 拼接：

```text
<Cursor.app>/Contents/Resources/app/bin/cursor
```

当该文件可执行时，workspace 和 root 分别使用：

```text
cursor editor --new-window <workspace-file>
cursor editor --new-window <workspace-root>
```

`editor` 显式选择应用自带的编辑器路由；`--new-window` 表达必须新建窗口的意图。ReqWS 不搜索或执行用户 PATH 中的同名命令，避免 shell 配置、remote CLI 或第三方 shim 改变行为。

Cursor 的 bundle 脚本在继承 `VSCODE_IPC_HOOK_CLI` 时会主动搜索 PATH 中的 remote CLI。ReqWS 启动 `bin/cursor` 或 `bin/code` 前复制当前环境并只移除该 hook，确保固定 bundle 路径不会再次转发到远程上下文；其他正常环境变量继续保留。LaunchServices、VS Code 和 Finder 不做这项专用过滤。

### 3.2 兼容回退

部分旧版 Cursor bundle 只包含继承自 VS Code 的 `bin/code`。若 `bin/cursor` 不可执行而 `bin/code` 可执行，则执行：

```text
code --new-window <target>
```

旧启动器不一定识别 `editor` 路由词，因此该回退只传正式的 `--new-window` 参数。若两个内置 CLI 都不可用，最后沿用 `/usr/bin/open -a Cursor <target>`，保留旧安装的基础打开能力；这种异常 bundle 无法保证 Agents Window 兼容，属于已知降级边界。

不会在正常路径使用 `--classic`：当前 Cursor 将其标为开发参数，且 `editor --new-window` 已足以打开并加载 IDE workspace。

### 3.3 错误与进程处理

启动前继续按顺序检查目标绝对路径和 Cursor 应用。CLI 或 LaunchServices 的同步 spawn 异常与非零退出都映射为现有 `EDITOR_NOT_FOUND`、`launching` 错误，并在 detail 中记录失败的固定启动器或退出码。

CLI 已存在但执行失败时不再静默尝试 LaunchServices，避免把“IDE 启动失败”伪装成成功并重新落入 Agents Window。

## 4. 安全与兼容边界

- 可执行路径只从已检测到的标准 Cursor bundle 派生。
- workspace 路径作为单独参数传递，不进入 shell 字符串。
- Cursor CLI 子进程不继承 `VSCODE_IPC_HOOK_CLI`，不会因 remote terminal hook 绕回 PATH 中的 CLI。
- 继续使用 `stdio: 'ignore'`，不读取或保存 Cursor 会话、凭据或用户配置。
- Cursor bundle 内部布局不是 macOS 的平台契约，因此保留 `bin/code` 和 LaunchServices 两级回退。
- 本次不声明最低 Cursor 版本；真实 GUI 证据必须记录测试版本。

## 5. 测试计划

自动测试覆盖：

- system Applications 和 user Applications 的 bundle 路径解析；
- `bin/cursor` 的 `editor --new-window` 参数与 `shell: false`；
- remote CLI hook 被移除，其他进程环境保持不变且输入对象不被修改；
- `bin/code` 与 LaunchServices 两级回退；
- workspace file、workspace root、应用和目标缺失；
- spawn 异常与非零退出的稳定错误。

真实 macOS GUI smoke 覆盖：

1. 记录 Cursor 版本，并确认初始窗口标题为 `Cursor Agents`。
2. 使用含至少两个现存目录的 `.code-workspace` 触发新启动策略。
3. 确认新窗口标题包含 `(Workspace)`，且 Explorer 显示两个 workspace root。
4. 记录自动检查命令、结果和未覆盖的旧版回退边界。

## 6. 文档与交付影响

完成实现后更新[使用说明](../../guides/user-guide.md)，明确 Cursor 操作会打开新的 IDE 窗口。按次验证证据收录在本需求包；没有发布、迁移或用户数据回滚，因此不创建交付文档。
