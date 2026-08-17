# ReqWS Desktop

ReqWS 是一个仅在 macOS 本机运行的 Electron 应用，用来把同一项需求涉及的多个 Git 仓库组织成彼此物理隔离的功能工作区。每个仓库使用完整 clone 和独立 `.git`；ReqWS 生成受管 `.code-workspace` 供 VS Code/Cursor 使用，也可把 workspace root 交给本地安装的 ReqWS GoLand 插件。

ReqWS 负责仓库目录、功能分支和工作区文件的创建与维护，但不会执行 pull、merge、rebase、push、创建 PR/MR 或自动删除用户工作区。

## 文档入口

| 文档 | 适合谁 | 内容 |
|---|---|---|
| [使用说明](docs/guides/user-guide.md) | ReqWS 用户 | 安装、Git 配置、仓库与工作区操作、设置、恢复和数据保护。 |
| [GoLand 插件使用指南](docs/guides/goland-plugin-guide.md) | GoLand 用户 | 图解插件编译安装、Tool Window 区块与按钮、状态、同步与安全排障。 |
| [开发指南](docs/guides/development-guide.md) | 开发者 | 环境、架构边界、测试、国际化、文档和 macOS 交付流程。 |
| [项目文档索引](docs/README.md) | 所有人 | 当前需求、技术方案、测试证据、交付记录、规范与历史资料。 |

## 主要能力

- 维护可搜索的 Git 仓库目录，并支持无凭据的 HTTPS 与 SSH 地址。
- 为一项需求顺序 clone 多个仓库，并统一切换到指定功能分支。
- 分别配置代码父目录和 `.code-workspace` 文件目录。
- 用 VS Code、Cursor、GoLand 或 Finder 打开工作区；GoLand 插件从只读 manifest 投影活动项目范围和 Git roots。
- 增加或逻辑移除工作区仓库、重新生成管理文件，以及遗忘索引但保留磁盘内容。
- 提供简体中文、英文和跟随系统的界面语言。
- 用稳定错误码、阶段信息和可复制日志说明失败，并保留已公开的可恢复工件。

## 快速开始

开发运行需要 macOS、Node.js 24、npm 和 Git：

```bash
nvm use
npm ci
npm start
```

从可信源码构建并安装到本机：

```bash
nvm use
npm run install:macos
```

不要用 `sudo` 包裹整个 npm 命令。当前构建采用 ad-hoc 签名且未经 Apple 公证，不是面向外部公开分发的 Gatekeeper-ready 安装包；安装参数、更新方式与认证准备见[使用说明](docs/guides/user-guide.md)。

## 常用开发命令

```bash
npm run check          # 类型、lint、i18n、文档和全部测试
npm run test:unit      # 单元测试
npm run test:integration
npm run test:renderer
npm run package:macos  # 生成并验证 .app，不安装
npm run check:goland   # 独立测试并验证 GoLand 插件
npm run package:goland # 生成本地磁盘安装 ZIP
```

GoLand 插件需要 JDK 21，Gradle 构建与根 `npm run check`、Electron package 相互隔离；完整磁盘安装和界面说明见[GoLand 插件使用指南](docs/guides/goland-plugin-guide.md)，当前验证状态见[GoLand 支持需求包](docs/changes/goland-plugin-support/README.md)。完整命令语义、进程职责和变更清单见[开发指南](docs/guides/development-guide.md)。不要编辑或提交 `node_modules/`、`.vite/`、`out/`、`dist/`、`coverage/` 或 `integrations/goland/build/`。

## 安全边界

- Renderer 保持 sandbox、context isolation 和 web security；Node integration 关闭。
- Preload 只暴露固定的 typed API，Main 对 IPC 输入再次做 Zod 校验。
- Git 和编辑器命令使用参数数组及 `shell: false`，凭据交给系统 Git、SSH Agent 或 credential helper。
- 路径和 manifest 在写入前执行 containment、realpath 与 symlink 校验。
- GoLand 插件只读 manifest，不访问 repository URL、不执行 Git 生命周期命令；Safe Mode 不修改项目模型或 VCS。
- 状态、manifest 和 managed workspace 文件使用原子发布或替换；逻辑移除和遗忘不会删除磁盘内容。

这些约束的实现细节和修改要求记录在[开发指南](docs/guides/development-guide.md)，需求来源及按次验证证据从[项目文档索引](docs/README.md)进入。
