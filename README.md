# ReqWS Desktop

ReqWS 是一个仅在 macOS 本机运行的 Electron 应用，用来把同一项需求涉及的多个 Git 仓库组织成彼此物理隔离的功能工作区。每个仓库使用完整 clone 和独立 `.git`；ReqWS 生成受管 `.code-workspace` 供 VS Code/Cursor 使用，也可把 workspace root 交给本地安装的 ReqWS GoLand 插件。

ReqWS 负责仓库目录、功能分支和工作区文件的创建与维护，但不会执行 pull、merge、rebase、push、创建 PR/MR 或自动删除用户工作区。

## 文档入口

| 文档 | 适合谁 | 内容 |
|---|---|---|
| [使用说明](docs/guides/user-guide.md) | ReqWS 用户 | 安装、个人签名版的证书信任与应用内更新、Git 配置、工作区操作和数据保护。 |
| [GoLand 插件使用指南](docs/guides/goland-plugin-guide.md) | GoLand 用户 | 图解插件编译安装、Tool Window 区块与按钮、状态、同步与安全排障。 |
| [开发指南](docs/guides/development-guide.md) | 开发者 | 环境、架构边界、测试、国际化、文档和 macOS 交付流程。 |
| [项目文档索引](docs/README.md) | 所有人 | 当前需求、技术方案、测试证据、交付记录、规范与历史资料。 |

## 主要能力

- 维护可搜索的 Git 仓库目录，并支持无凭据的 HTTPS 与 SSH 地址。
- 为一项需求顺序 clone 多个仓库，并统一切换到指定功能分支。
- 分别配置代码父目录和 `.code-workspace` 文件目录。
- 用 VS Code、Cursor、GoLand 或 Finder 打开工作区；GoLand 插件从只读 manifest 自动投影活动项目范围，并只读检查用户在 GoLand Directory Mappings 中维护的 Git roots。
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

不要用 `sudo` 包裹整个 npm 命令。默认源码构建采用 ad-hoc 签名且未经 Apple 公证，不启用应用内更新；安装参数与 Git 认证准备见[使用说明](docs/guides/user-guide.md)。

## 个人签名版：首次安装、证书信任与自更新

应用内更新面向 macOS Apple silicon（arm64），需要先手动安装使用固定证书签名、内置更新器的版本。旧 ad-hoc 版本和默认 `npm run install:macos` 构建不能仅靠导入证书获得更新能力。当前正式发布与干净用户环境验收仍待完成，以下用于受控个人试运行，进度见[实施记录](docs/changes/macos-self-update/implementation-2026-09-19.md)。

1. 从[可信 Releases](https://github.com/fredgnr/reqws-desktop/releases)取得明确支持应用内更新的 `ReqWS-<版本>-macos-arm64.zip`，以及对应版本源码中的[公开证书 `reqws-signing.cer`](build/certificates/reqws-signing.cer)。核对发布说明、校验信息和维护者通过可信渠道提供的证书 SHA-256 指纹；不要只凭证书名称判断身份。
2. 退出已有 ReqWS，备份应用数据后解压 ZIP，将 `ReqWS.app` 放到自己可写的 `~/Applications/ReqWS.app` 或 `/Applications/ReqWS.app`。只替换 App，保留用户数据和 workspace；不要从 ZIP、Downloads 或临时目录运行后直接更新。
3. **需要为该个人签名版本添加信任时**，打开“钥匙串访问”，选择“登录（login）”，导入已核对的 `.cer`。双击 `ReqWS Personal Code Signing`，展开“信任”，仅将“代码签名”设为“始终信任”，其他用途保持系统默认；关闭窗口并按 macOS 提示授权。不要把顶部的所有用途统一设为“始终信任”。具体步骤见[安装与信任公开证书](docs/guides/user-guide.md#安装与信任公开证书)。
4. 首次启动若被“未知开发者”提示拦截，在确认来源和签名可信后，按系统设置 → 隐私与安全性中的提示，仅为 ReqWS 选择“仍要打开”。证书信任不等于 Apple 公证，也不代替这一步；不要全局关闭 Gatekeeper。
5. 打开 ReqWS 的“设置 → 应用更新”，依次选择“检查更新”“下载更新”“安装并重启”。只有维护者发布更高版本后才会发现更新；应用不会后台自动下载或在普通退出时安装。

配置证书信任时只需导入公开 CER，**不要导入 P12、私钥或维护者的密码**。是否需要额外的用户证书信任仍以目标 Mac 的验收结果为准；若已正常更新，不必额外扩大信任。若出现签名无效、证书不匹配或应用损坏，停止安装并联系维护者，不通过信任陌生证书绕过。完整说明与 Apple 操作参考见[使用指南](docs/guides/user-guide.md#个人签名版本的应用内更新)。

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
- GoLand 插件只读 manifest，不访问 repository URL、不执行 Git 生命周期命令；VCS Directory Mappings 在任何模式下都只读，Safe Mode 还禁止项目模型修改。
- 状态、manifest 和 managed workspace 文件使用原子发布或替换；逻辑移除和遗忘不会删除磁盘内容。

这些约束的实现细节和修改要求记录在[开发指南](docs/guides/development-guide.md)，需求来源及按次验证证据从[项目文档索引](docs/README.md)进入。
