---
title: ReqWS 使用说明
type: guide
status: active
updated: 2026-08-14
---

# ReqWS 使用说明

本指南帮助 macOS 用户安装 ReqWS，并安全地配置 Git 仓库、创建和维护彼此隔离的多仓库功能工作区。

## 1. ReqWS 会做什么

ReqWS 把同一项需求涉及的多个 Git 仓库分别完整克隆到一个工作区根目录，并让它们使用同一个功能分支。每个仓库都有独立的 `.git`，不会共享 Git worktree 或对象目录。ReqWS 还会生成一个由自己管理的 `.code-workspace` 文件，便于用 VS Code 或 Cursor 一次打开全部仓库。

ReqWS 不会执行 pull、merge、rebase、push 或创建 PR/MR，也不会自动删除本地仓库或工作区目录。

## 2. 运行条件

- macOS；Windows 和 Linux 不是当前目标平台。
- Git。缺少 Git 时仍可编辑仓库列表和设置，但不能测试连接、创建工作区或添加仓库。
- 从源码安装时需要 Node.js 24.x、兼容的 npm，以及首次下载依赖和 Electron 二进制的网络连接。
- VS Code 和 Cursor 是可选项；Finder 打开目录不依赖它们。

Git 认证由系统 Git、SSH Agent、macOS Keychain 或 Git credential helper 完成。ReqWS 不保存账号、密码、Token 或私钥。

## 3. 安装、更新与启动

当前可复现的安装方式是从可信源码构建。在仓库根目录执行：

```bash
nvm use
npm run install:macos
```

也可以执行 `make install`。安装脚本会重建锁定依赖、运行完整检查、为当前 Mac 架构打包并验证应用，然后安装到 `/Applications/ReqWS.app` 并启动。若 `/Applications` 不可写，脚本只在最终安装事务中请求提权；不要运行 `sudo npm run install:macos`。

更新已安装版本时，退出 ReqWS，在目标源码版本上再次执行同一命令。替换应用不会删除仓库目录，也不会迁移或清空用户数据。

常用变体：

```bash
# 只生成和验证 .app，不安装
npm run package:macos

# 安装后不启动
npm run install:macos -- --no-launch

# 仅查看将执行的步骤
npm run install:macos -- --dry-run

# 安装到当前用户的 Applications 目录
REQWS_APPLICATIONS_DIR="$HOME/Applications" npm run install:macos
```

完整参数见 `npm run install:macos -- --help`。当前本机构建使用 ad-hoc 签名，没有 Developer ID 和 Apple 公证，不是 Gatekeeper-ready 的公开分发包；只应从可信源码在本机使用。

## 4. 首次使用前配置 Git

### SSH 仓库

ReqWS 会让 Git 继承正常的 `HOME` 和 `SSH_AUTH_SOCK`，因此可以使用 `~/.ssh/config`、`known_hosts` 和 SSH Agent。带口令的私钥应先在终端加载：

```bash
ssh-add ~/.ssh/id_ed25519
```

首次连接某个主机时，也应先在终端完成 host key 确认。ReqWS 的 Git 操作是非交互式的，不能在应用窗口中回答密码或 host key 提示。

### HTTPS 仓库

先用系统 Git 配置可用的 credential helper。仓库地址只能保存不含凭据的 HTTPS URL；不要把用户名、密码或 Token 写进 URL、query 或 fragment。

支持的地址形式包括：

```text
https://example.com/team/repository.git
ssh://git@example.com/team/repository.git
git@example.com:team/repository.git
```

本地路径、`file://`、明文 HTTP、Git remote-helper 语法及带凭据的地址会被拒绝。

## 5. 设置语言和默认目录

打开一级导航中的“设置”：

1. “界面语言”可选“跟随系统”“简体中文”或 “English”。保存后当前窗口立即切换，重启后继续生效。
2. “工作区默认父目录”用于预填新工作区的代码父目录。
3. “`.code-workspace` 文件存放目录”用于预填管理文件的目录。
4. 目录可以留空；留空时每次创建工作区都必须选择。

设置页只能通过系统目录选择器填写路径。已保存目录若后来被删除或变得不可访问，设置页会要求重新选择。默认目录只影响以后打开的创建表单；在单次创建中换目录不会反写全局设置。

## 6. 管理仓库目录

进入“仓库”，选择“添加仓库”：

1. 输入不含凭据的 HTTPS 或 SSH 地址。
2. 名称会从地址自动推导，也可以在保存前修改；名称将成为工作区内的目录名。
3. 确认默认分支，例如 `main`。这里的值必须与远端实际分支一致。
4. 可选择“测试连接”。测试会执行只读远端查询并显示远端默认分支；失败不会清空表单，也不会阻止保存配置。
5. 选择“添加仓库”保存。此时只写入仓库目录，不会立即 clone。

仓库列表可以按名称、URL 和默认分支搜索。编辑仓库地址或默认分支只影响以后创建或添加的仓库，不会改写已有工作区中的 clone。

删除仓库记录只会把它从可选目录移除：

- 不删除任何本地 clone。
- 不修改已创建工作区的 manifest 或 `.code-workspace`。
- 若仍被工作区引用，确认框会列出相关工作区。

## 7. 创建功能工作区

至少保存一个仓库后，进入“工作区”并选择“创建工作区”：

1. 输入工作区名称。名称会用于生成安全的目录名和 `.code-workspace` 文件名。
2. 检查功能分支。默认值是 `feature/<工作区名称 slug>`，可以改为其他合法 Git 分支名。
3. 选择代码父目录。ReqWS 会在其下拼接工作区名称；也可直接输入最终绝对路径。
4. 选择现有的 `.code-workspace` 存放目录。
5. 搜索并勾选至少一个仓库。过滤列表不会清除已经勾选的项目。
6. 选择“创建工作区”，并保持应用运行直到操作结束。

此前保存的默认目录若已被删除或不可访问，对应目录字段会显示警告并要求重新选择。为本次创建选择替代目录后警告会消失，但该选择不会自动写回“设置”中的全局默认值。

最终代码目录和 `.code-workspace` 文件必须尚不存在；ReqWS 不会覆盖它们。所选仓库按顺序处理，每个仓库的分支规则相同：

1. 使用已有的本地功能分支；
2. 否则跟踪已有的远端功能分支；
3. 否则从该仓库配置的远端默认分支新建功能分支。

如果远端默认分支不存在、地址不可达或分支名非法，创建会失败。最终目录公开前的临时 staging 会被清理；如果完整目录或 workspace 文件已经公开后才发生 state 写入错误，ReqWS 会保留这些工件并在错误详情中给出恢复路径，不会冒险自动删除。

## 8. 打开和维护工作区

工作区列表可按名称、分支、仓库名、代码路径或 workspace 文件路径搜索。操作按钮包括：

- “VS Code”：打开生成的 `.code-workspace`。
- “Cursor”：用 Cursor 打开 `.code-workspace`。
- 详情中的“用 Cursor 打开代码目录”：直接打开工作区根目录。
- “在 Finder 中显示”：定位代码根目录。

编辑器未安装或工作区不是“就绪”状态时，相应按钮会禁用。

在详情面板中可以：

- 添加仓库：clone 新仓库并切换到工作区功能分支，然后更新 manifest 和 `.code-workspace`。
- 移除仓库：只更新 manifest 和 `.code-workspace`，保留磁盘上的仓库目录。
- 重新生成工作区文件：根据 manifest 覆盖生成 `.code-workspace`；手工加入其中的 settings 不会保留。
- 移除工作区记录：只从 ReqWS 索引中遗忘该工作区，代码目录、manifest 和 `.code-workspace` 全部保留。

工作区 Git 变更在同一应用实例中串行执行。操作窗口打开期间不要退出 ReqWS。

## 9. 状态与恢复

| 状态 | 含义 | 建议操作 |
|---|---|---|
| 就绪 | 代码目录、manifest 和 `.code-workspace` 均存在，且没有未处理的变更错误。 | 可以打开、添加或移除仓库。 |
| 路径缺失 | 代码目录、manifest 或 workspace 文件至少一项不存在；详情会列出具体缺失项。 | 先查看详情；若仅 workspace 文件缺失且 root、manifest 有效，可重新生成。不要创建同名新目录覆盖现场。 |
| 异常 | 上一次工作区变更失败，或一致性校验发现问题。 | 打开详情并复制错误日志；核对磁盘工件后再尝试重新生成，避免手工改 manifest。 |

常见问题：

- `GIT_NOT_FOUND`：安装 Git，确认从 Finder/LaunchServices 启动的应用也能访问 Git，再刷新。
- `REPOSITORY_UNREACHABLE` 或 `CLONE_FAILED`：先在终端用相同 URL 验证凭据、网络和 host key。
- `DEFAULT_BRANCH_NOT_FOUND`：修正仓库目录中的默认分支，再重新创建。
- `WORKSPACE_ROOT_EXISTS` 或 `WORKSPACE_FILE_EXISTS`：选择新的名称或位置；ReqWS 不覆盖现有内容。若只是旧索引仍占用路径，先确认磁盘内容，再移除旧工作区记录。
- 设置目录不可用：在“设置”中重新选择已经存在且可访问的目录。
- state 损坏：ReqWS 会保留带时间戳的 `.corrupt-*` 副本并报告错误；不要覆盖该副本，先保存错误日志和现场文件。

错误面板会显示稳定错误码、阶段和技术信息，并提供复制日志按钮。报告问题时一并提供错误码、操作阶段、应用版本和可脱敏的日志；不要提交仓库凭据或私钥。

## 10. 数据位置与备份

macOS 上的典型全局状态位置是：

```text
~/Library/Application Support/ReqWS/reqws/state.v1.json
```

每个工作区根目录包含：

```text
<workspace-root>/.reqws/workspace.json
<workspace-root>/<repository-name>/.git/
```

`.code-workspace` 可以位于另一个目录。备份或迁移前先退出 ReqWS，并同时保留全局 state、工作区根目录和对应的 `.code-workspace`。当前版本没有自动导入、云同步或跨机器迁移流程；不要只复制 state 后假定路径会自动重映射。

## 11. 当前限制

- 仅支持 macOS，且没有 Windows/Linux 构建。
- clone 不支持取消、并发、浅克隆或字节级进度。
- 不提供 pull、merge、rebase、push、PR/MR、测试运行器或 Git worktree。
- `.code-workspace` 由 ReqWS 整体维护，不合并手工 settings。
- 逻辑移除和遗忘操作有意保留磁盘文件；需要删除时由用户在核对路径后自行处理。
- 本机构建采用 ad-hoc 签名，不含 Developer ID、公证、DMG 或自动更新。

## 12. 依据与进一步资料

- [全局设置需求包](../changes/global-settings/README.md)记录语言和默认目录的当前设计、验收范围与验证证据。
- [MVP 实现快照](../changes/mvp/README.md)保存初始需求覆盖、交付和验证历史；其状态为 archived，不代替当前代码和测试。
- [历史参考](../reference/README.md)保存冻结的原始方案与原型，只用于追溯来源。
- 其他当前需求、技术决策和验证证据从[文档总索引](../README.md)继续查找；若没有 active 设计，操作现状以代码和自动化测试复核。
