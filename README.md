# ReqWS Desktop

**把一个需求涉及的多个 Git 仓库，放进一个独立的工作区。**

例如，开发 `checkout-flow` 需要同时修改 `api`、`web` 和 `shared`。ReqWS 会把这三个仓库分别克隆到新的目录，在每个仓库中准备好 `feature/checkout-flow` 分支，再帮你用 VS Code、Cursor 或 GoLand 打开。

另一个需求使用另一套目录。两套工作区不共用 `.git`，也不会改动你原来已有的本地仓库。创建的是新的 clone，不会带入原目录中尚未提交的修改。

[下载与安装](docs/guides/installation.md) · [Desktop 使用指南](docs/guides/user-guide.md) · [GoLand 插件使用指南](docs/guides/goland-plugin-guide.md)

## 它解决什么问题

不用每次手动重复“建目录 → 克隆几个仓库 → 准备分支 → 配置编辑器工作区”。先保存常用仓库的 Git 地址，之后按需求勾选即可。

下面是目录示例，不是真实界面截图：

```text
需求 checkout-flow                   另一个需求 fix-login
└── checkout-flow/                  └── fix-login/
    ├── api/      独立 clone             ├── api/      另一份 clone
    ├── web/      独立 clone             └── web/      另一份 clone
    └── shared/   独立 clone
```

<!-- docs-asset: overview -->

| 你要做的事 | 在 ReqWS 中怎么做 |
|---|---|
| 为一个需求准备多个仓库 | 添加仓库地址，创建工作区时勾选仓库并填写功能分支。 |
| 并行处理不同需求 | 分别创建工作区，每个工作区使用独立的代码目录。 |
| 找回之前的工作 | 按工作区名称、分支、仓库名或路径搜索，再用编辑器打开。 |
| 需求中途增加仓库 | 在工作区详情中添加；ReqWS 会克隆仓库并准备该工作区的功能分支。 |
| GoLand 当前只需要其中两个仓库 | 在 Desktop 中选择要加载的仓库，保存后由 GoLand 插件更新项目视图。 |

ReqWS 管理工作区，不代替日常 Git 操作。提交、拉取、合并、推送和创建 PR/MR，仍在终端、编辑器或 Git 平台中完成。ReqWS 不会自动删除你的代码目录。

## Desktop 和 GoLand 插件，各自做什么

**Desktop 是管理入口。** 在这里保存仓库地址、创建工作区、增减需求成员、选择 GoLand 要加载的仓库，并打开编辑器。仓库配置、工作区索引和设置保存在本机。

**GoLand 插件是 IDE 适配层，需要单独安装。** 它读取 Desktop 保存的选择，把选中的仓库显示在同一个 GoLand 项目中，并提示哪些 Git 根目录需要手动配置。它不克隆仓库、不切分支，也不回写 Desktop 配置。

例如，需求包含 `api`、`web`、`shared`，但现在只改后端：在 Desktop 的“GoLand 工作加载”中选“指定仓库”，保留 `api`、`shared`，然后点击“保存并打开 GoLand”。`web` 仍在磁盘上，也仍属于该需求，只是不由 ReqWS 加载进当前 IDE 项目。

<!-- docs-asset: goland-selection-result -->

这里的“加载”针对项目内容，不等于 Git Log/Commit 的筛选。插件不会改动 GoLand 的 Directory Mappings；使用 VS Code 或 Cursor 时则不需要安装 ReqWS 插件。

## 第一次使用

先按[安装指南](docs/guides/installation.md)安装 Desktop，并让系统 Git 能访问你的仓库。当前 Release 的 Desktop 安装包面向 **macOS Apple silicon（arm64）**；GoLand 插件的当前目标是 **GoLand 2026.2.1.1 / GO-262.9437.286**。

1. 在 **仓库 → 添加仓库** 中填写 Git 地址、名称和默认分支。保存这里只是登记仓库，不会立即克隆。
2. 在 **工作区 → 创建工作区** 中填写名称和功能分支，选择代码位置、`.code-workspace` 文件位置，再勾选仓库。
3. 等待状态变为 **就绪**，然后用 VS Code 或 Cursor 打开。使用 GoLand 时，先安装独立插件，再按[首次打开步骤](docs/guides/goland-plugin-guide.md#首次打开工作区)操作。

仓库较多时会依次克隆。创建过程中请保持 ReqWS 运行；完整步骤、操作结果和失败处理见 [Desktop 使用指南](docs/guides/user-guide.md)。

## 使用前需要知道

每份工作区都是完整 clone，会占用独立磁盘空间。ReqWS 不使用 Git worktree，不持续替你对齐各仓库分支，也不提供云同步。

“从工作区移除仓库”和“移除工作区记录”都会保留磁盘文件；暂时不想在 GoLand 中看见某个仓库，应调整加载选择，不要移除需求成员。`.code-workspace` 由 ReqWS 生成和维护，重新生成时会覆盖手工修改。

个人签名版支持在设置中手动检查、下载和安装 Desktop 更新，但不是 Apple 公证版本。首次安装、证书信任及源码构建的区别见[安装与更新](docs/guides/installation.md)。Desktop 更新不负责更新 GoLand 插件；Marketplace 是否可安装，以实际审核结果为准。

## 开发与项目资料

从源码启动开发实例，需要 macOS、Node.js 24、npm 和 Git。在仓库根目录执行：

```bash
nvm use
npm ci
npm start
```

这不是已安装 App 的更新方式。源码安装、打包和检查命令见[开发指南](docs/guides/development-guide.md)；GoLand 插件源码构建另需 JDK 25，见[插件开发入口](integrations/goland/README.md)。

[项目文档索引](docs/README.md)收录需求、设计和验证记录。参与本轮文档维护时，从[文档改进与本地交接](docs/changes/documentation-refresh/README.md)查看待补截图、对应段落和验收要求。
