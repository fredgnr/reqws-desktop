# ReqWS Desktop MVP

ReqWS 是一个只在本机运行的 macOS Electron 应用，用来把多个 Git 仓库组织成彼此物理隔离的 feature workspace。每个 workspace 对每个仓库都使用完整 clone 和独立 `.git`，再把它们写入一个由 ReqWS 管理的 `.code-workspace` 文件。

本仓库是技术方案 1.0 的完整源码实现。它提供本机 `.app` 的一键构建、打包、安装，以及从当前 checkout 重新构建并覆盖更新本机安装，但不包含 DMG、Developer ID 发布签名、公证、在线自动更新、账号、云同步、遥测、push、PR/MR 或 Git worktree。

## 已实现功能

- Repository Catalog：新增、编辑、删除、搜索仓库；从 Git URL 推导名称；连接测试失败不阻止保存。
- Feature Workspace：分别选择代码父目录和 `.code-workspace` 目录，勾选多个仓库后顺序 clone。
- 分支语义：优先使用已有本地 feature 分支，其次 tracking 远端 feature 分支，否则从远端默认分支创建。
- Workspace 管理：按名称、分支、仓库和路径搜索；增加或逻辑移除仓库；同步/恢复管理文件；遗忘索引但保留磁盘内容。
- 编辑器集成：VS Code、Cursor、Cursor 打开根目录、Finder 显示目录；不可用时给出明确状态。
- 进度与错误：展示当前 repo、阶段、百分比、稳定错误码和可复制日志。
- 本地持久化：schema v1 JSON state、workspace manifest 和标准 JSON `.code-workspace`。

## 运行要求

- macOS（MVP 的唯一目标平台）
- Node.js 24.x；项目附带 `.nvmrc`
- npm 11 或 Node 24 自带的兼容 npm
- Git
- 可选：Visual Studio Code、Cursor
- 首次 `npm ci` 需要网络以下载 npm 依赖和 Electron 二进制

Git 凭据完全交给系统 Git、SSH Agent、macOS Keychain 或 Git credential helper。ReqWS 会拒绝带 userinfo、token 参数、控制字符或 Git remote-helper 语法的 URL，避免把凭据写入 state、manifest 或 `.git/config`。

SSH 地址会经系统 Git/OpenSSH 访问；应用保留 `HOME` 和 `SSH_AUTH_SOCK`，因此可使用 `~/.ssh/config`、`known_hosts`、标准私钥位置及已加载到 Agent 的密钥，但 ReqWS 本身不会读取或复制私钥内容。由于 Git 以非交互模式运行，带口令的私钥应先执行 `ssh-add` 并在启动 ReqWS 时让应用继承可访问的 Agent；首次 host key 确认也应预先在终端完成。

## 快速启动

```bash
nvm use
npm ci
npm start
```

如果没有 nvm，请确认 `node --version` 为 `v24.x` 后执行后两条命令。源码模式会启动 Electron Forge/Vite，不生成 DMG。

建议在运行前做一次完整检查：

```bash
npm run check
npm audit --audit-level=low
```

当前 lockfile 的完整依赖审计与 production-only 审计均为 0 个已知漏洞。
Forge/Packager 的旧 `extract-zip` 传递依赖被定向替换为 Electron 维护的 hardened 实现；clean install、自动检查与真实 macOS package 路径均已验证。

## 一键构建、打包与安装

先退出正在运行的 ReqWS，然后在仓库根目录执行：

```bash
nvm use
npm run install:macos
```

也可以通过 Makefile 执行同一流程：

```bash
make install
```

Makefile/npm 入口会先检查当前 Node；若当前 shell 仍在 Node 22 等旧版本，会依次查找 `.nvmrc` 对应的 nvm 安装、PATH 和常见 Homebrew 路径中的 Node 24，再用它启动安装脚本。若需要显式指定，可设置 `REQWS_NODE24=/absolute/path/to/node`。

该命令会按顺序完成：

1. `npm ci` 重建锁定依赖；
2. `npm run check` 执行 typecheck、ESLint 和测试；
3. 为当前 Mac 架构生成并验证 `out/ReqWS-darwin-<arch>/ReqWS.app`；
4. 在 `/Applications` 内创建同卷 staging，将旧版临时备份后整体切换；
5. 再次验证 bundle ID、版本、CPU 架构和代码签名；对脚本可捕获的复制、发布或校验错误，尽力恢复旧版；
6. 清除外部工具可能注入的 `ELECTRON_RUN_AS_NODE`，启动 `/Applications/ReqWS.app`，并确认主进程在启动探针后仍存活。

同一条命令也用于从当前源码更新已安装版本。更新不会合并旧 bundle，也不会读取、移动或删除 `~/Library/Application Support/ReqWS`，所以 catalog、workspace 索引和设置会继续保留。若 `/Applications` 不可写，脚本只对最终安装事务请求 `sudo`；不要使用 `sudo npm run install:macos`。

安装替换不是面向 `SIGKILL`、系统崩溃或断电的 crash-consistent 事务。异常终止可能留下安装目录内的 `.reqws-{install,backup,failed}-*.app` 和 `/tmp/reqws-install-*.lock`；两次发布 rename 之间，`ReqWS.app` 也可能暂时缺失而旧版位于 backup。下次执行会保守停止并报告需检查的路径，不会猜测或批量删除残留。确认没有安装进程并核对 bundle 身份、backup 和 lock 中的 PID 后，再人工恢复或清理这些精确工件。

常用变体：

```bash
# 执行 clean install、检查并生成/验证 .app，但不安装
npm run package:macos

# 已经执行过 npm ci 时，跳过依赖重建
npm run install:macos -- --skip-ci

# 安装后不启动
npm run install:macos -- --no-launch

# Makefile 透传安装参数
make install INSTALL_ARGS="--skip-ci --no-launch"

# 仅查看计划
npm run install:macos -- --dry-run

# 安装到当前用户目录；该自定义目录不会使用 sudo
REQWS_APPLICATIONS_DIR="$HOME/Applications" npm run install:macos
```

完整参数可用 `npm run install:macos -- --help` 查看。当前 package 使用固定 bundle ID `com.reqws.desktop` 和通过 `codesign --verify --deep --strict` 校验的本机 ad-hoc 签名；本机构建显式关闭 Hardened Runtime，避免 macOS 26 对分别 ad-hoc 签名的 Electron Framework 执行 Team ID library validation。该签名不提供发布者身份或系统信任链，仅适合从可信源码安装到本机。把应用分发给其他用户前必须改用统一 Developer ID 签名、重新启用 Hardened Runtime 并完成公证。

## 基本使用

1. 打开 `Repositories`，录入 SSH 或 HTTPS Git 地址，确认名称和默认分支后保存。
2. 可用“测试”执行 `git ls-remote`。连接失败不会丢掉表单，也不会阻止保存。
3. 回到 `Workspaces`，点击“创建 Workspace”。
4. 输入名称和 feature 分支。代码目录的“选择”按钮选择父目录，最终目录会自动拼接 workspace 名称；也可直接输入绝对路径。
5. 选择 `.code-workspace` 文件目录并勾选至少一个仓库。
6. 创建完成后可用 VS Code/Cursor 打开，也可进入详情增加、逻辑移除或同步仓库。

创建时不会覆盖已有代码目录或 `.code-workspace`。任一 clone/分支/写入步骤失败时，ReqWS 会报告具体阶段并清理尚未发布的 staging；一旦 root 或 workspace 文件已经公开，则保留完整工件并在错误详情中提供恢复路径，不通过公开路径自动删除。逻辑移除仓库和遗忘 workspace 都不会删除本地 repo。

## 数据位置与文件所有权

全局 state 使用 Electron 的 `app.getPath('userData')`：

```text
<userData>/reqws/state.v1.json
```

macOS 的典型位置是：

```text
~/Library/Application Support/ReqWS/reqws/state.v1.json
```

每个 workspace 根目录包含：

```text
<workspace-root>/.reqws/workspace.json
<workspace-root>/<repo-name>/.git/
```

`.code-workspace` 可在另一目录中。它由 ReqWS 管理；同步、增加或移除仓库时会整体重写，手工 settings 不会被合并。全局 state 损坏时，应用会先保留带时间戳的 `.corrupt-*` 副本再报错。

## 安全与正确性边界

- Renderer 开启 `sandbox`、`contextIsolation` 和 `webSecurity`，关闭 Node integration。
- Preload 只暴露固定、typed 的 ReqWS API；所有 IPC 入参都在 main process 用 Zod 再校验。
- Git 和 `/usr/bin/open` 都使用参数数组与 `shell: false`；可控参数有额外格式检查或 `--` option terminator。
- 生产仓库地址仅接受无凭据 HTTPS、SSH URI 或 SCP-like SSH；拒绝 HTTP/Git/file/local path、敏感 query、控制字符、option 前缀和 remote-helper 语法。
- Git 子进程清除继承的 `GIT_*` 环境变量并关闭终端凭据提示，避免外部启动环境重定向仓库 metadata。
- repo 路径使用 realpath/父路径 containment 校验，拒绝符号链接逃逸。
- 读取 manifest 时会和全局索引绑定校验，不能借篡改 manifest 把写入或 Git 操作重定向到任意路径。
- 应用使用 single-instance lock；所有 workspace mutation 在同一进程内共享 FIFO 协调器，避免并发写入丢更新。
- state、manifest 与 managed workspace 文件使用同目录临时文件、flush 和原子发布/替换；首次发布不会覆盖竞态中出现的文件。
- 创建根目录先在同父目录完成 sibling staging，再以一次 rename 发布，因此半成品不会逐项出现在最终目录。发布后的 root 或 workspace 文件若遇到后续 state 写入失败会保留为可恢复工件并写入错误详情，ReqWS 不会再通过公开路径自动删除它们。

## 开发与测试

```bash
npm run typecheck
npm run lint
npm test
npm run test:unit
npm run test:integration
npm run test:renderer
npm run check
```

测试覆盖 JSON 原子存储、损坏备份、path/symlink containment、repository CRUD/引用、Git 命令/输出限制/凭据清理、真实临时 bare remotes 的分支语义、workspace 创建/回滚/并发/manifest 篡改、IPC、Preload、窗口安全、编辑器启动、React UI，以及 macOS app 整体替换、进程内错误回滚、遗留事务工件 fail-closed、安装锁、ad-hoc/Hardened Runtime 兼容性和安全启动环境。

Vite/Vitest 配置使用 `.mts`，避免 CommonJS package 在未来 `native` config loader 下解析 ESM 配置的兼容问题。Electron Forge Vite plugin 仍属于上游 experimental 集成，因此 package 与 lockfile 固定了当前验证版本。

## 项目结构

```text
src/
  main/
    ipc/                 main-side handlers、schema validation、依赖组装
    services/            state、Git、branch、workspace、path、editor 服务
    create-window.ts     安全 BrowserWindow
    index.ts             single-instance 与 Electron 生命周期
  preload/               窄类型化 contextBridge API
  renderer/              React 页面、对话框、进度、错误与样式
  shared/                类型、Zod schema、IPC channel、纯函数
tests/
  unit/                  服务与安全契约测试
  integration/           真实本地 Git/工作区集成测试
  renderer/              jsdom 交互与无障碍测试
scripts/
  run-install-macos.mjs  兼容旧 shell Node 的 Node 24 启动器
  install-macos.mts      macOS clean build、package 与本机安装脚手架
docs/
  reference/             原始技术方案、HTML 原型、预览图与 handoff
  VERIFICATION.md        验证证据与 macOS smoke checklist
```

## 已知范围限制

- 只支持 macOS；Windows/Linux 不是产品目标。
- 没有 clone 取消、并发 clone、进度字节数或浅 clone。
- 没有 pull/merge/rebase/push/测试运行器或 PR/MR API。
- 不自动删除被逻辑移除的 repo，也不删除用户已有或身份发生变化的路径。
- 不合并用户手工编辑的 `.code-workspace` settings。
- 不生成 DMG 等公开分发安装包；本机 `.app` 使用 ad-hoc 签名，不包含 Developer ID 发布签名、公证或在线自动更新。
- Node.js 没有暴露 macOS 的目录 `RENAME_NOREPLACE`/fd-relative file API；因此在恶意本地进程恰好于检查与 rename/open 之间交换一个空目录或父路径时，仍存在极窄 TOCTOU 窗口。正常并发、非空目标和已存在文件均会被拒绝；ReqWS 对已公开工件不做自动删除以优先保护用户数据。

原始方案和原型保存在 `docs/reference/`，实现验证详情见 `docs/VERIFICATION.md`。
