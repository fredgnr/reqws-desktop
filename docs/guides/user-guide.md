---
title: ReqWS Desktop 使用指南
type: guide
status: active
updated: 2026-09-21
---

# ReqWS Desktop 使用指南

这份指南带你完成一次“添加仓库 → 创建工作区 → 用编辑器打开”，然后说明如何管理已有工作区。安装包和更新问题见[安装与更新](installation.md)；GoLand 内的操作见[插件使用指南](goland-plugin-guide.md)。

<a id="1-reqws-会做什么"></a>

## 先分清三个概念

| 名称 | 例子 | 在哪里修改 |
|---|---|---|
| 仓库列表 | 你登记了 `api`、`web`、`shared` 的 Git 地址。此时还没有克隆。 | 左侧“仓库”。 |
| 工作区成员 | `checkout-flow` 这个需求用到上述三个仓库，每个仓库在该工作区中都有一份独立 clone。 | 工作区详情中的“管理工作区”。 |
| GoLand 加载选择 | 当前只想在 GoLand 中处理 `api` 和 `shared`，暂时不加载 `web`。 | 工作区详情中的“GoLand 工作加载”。 |

**暂时不加载，不是移除仓库。** 修改加载选择不会删除代码、改变需求成员或改写 VS Code/Cursor 的 `.code-workspace`。ReqWS 在创建或添加仓库时准备功能分支；之后的提交、拉取、合并和推送由你自己完成。

<a id="2-运行条件"></a>
<a id="3-安装更新与启动"></a>

## 开始前

先按[安装指南](installation.md)安装 Desktop。运行已下载的 App 不需要 Node.js 或 JDK；系统 Git 用来连接和克隆仓库。VS Code、Cursor、GoLand 都是可选项，但 GoLand 集成还需要单独安装 ReqWS 插件。

首次使用 SSH 或 HTTPS 仓库前，先在终端确认同一地址可以访问。ReqWS 不提供输入密码、Token 或确认 SSH 主机身份的交互窗口，也不保存这些凭据。

### 个人签名版本的应用内更新

安装方式、首次启动和更新按钮已移到[安装与更新](installation.md#个人签名版本的应用内更新)。默认源码构建不启用应用内更新，导入证书也不会使它变成可更新版本。

### 安装与信任公开证书

只有需要为已核实的个人签名版本添加信任时，才按[公开证书说明](installation.md#安装与信任公开证书)操作。普通用户不需要维护者的 P12、私钥或私有仓库权限。

<a id="6-管理仓库目录"></a>

## 添加仓库

在左侧选择 **仓库**，点击 **添加仓库**。

1. 填写 **Git 仓库地址**。支持不带凭据的 HTTPS 和 SSH 地址。
2. 检查 **名称**。添加时会按地址自动填写，也可以修改；这个名称会成为工作区内的仓库目录名。
3. 填写 **默认分支**，例如 `main`，必须与远端实际分支一致。
4. 点击 **测试连接**，查看连接结果和远端默认分支。测试失败仍可保存，但创建工作区前需要解决连接问题。
5. 点击 **添加仓库**。回到列表后应能看到这条记录；此时还不会克隆代码。

<!-- docs-asset: repository-add -->

可保存的地址示例：

```text
https://example.com/team/repository.git
ssh://git@example.com/team/repository.git
git@example.com:team/repository.git
```

这些是格式示例，实际使用时替换为自己的仓库地址。本地路径、`file://`、明文 HTTP、Git remote-helper 语法及带凭据的地址不被接受。

列表支持按名称、URL 或默认分支搜索。编辑地址或默认分支只影响以后创建或添加的 clone，不会修改已有工作区。删除仓库记录也不会删除本地代码或改变已有工作区；仍被引用时，确认框会列出相关工作区。

<a id="7-创建功能工作区"></a>

## 创建第一个工作区

下面用 `checkout-flow` 作为工作区名称，选择 `api`、`web`、`shared` 三个仓库。示例名称和路径只用于说明，请换成自己的配置。

进入 **工作区 → 创建工作区**，填写这些字段：

| 字段 | 示例 | 要注意什么 |
|---|---|---|
| 工作区名称 | `checkout-flow` | 用来生成默认目录名和 `.code-workspace` 文件名。 |
| 功能分支 | `feature/checkout-flow` | 各仓库会使用这个分支名；可以改成其他合法 Git 分支名。 |
| 工作区代码目录 | `/Users/you/Work/checkout-flow` | 点“选择…”时选父目录，ReqWS 会拼接名称；也可直接输入最终绝对路径。 |
| `.code-workspace` 文件存放目录 | `/Users/you/Workspaces` | 选择已存在的目录，可以与代码位置不同。即使用 GoLand，也需要填写。 |
| 选择仓库 | `api`、`web`、`shared` | 至少勾选一个。搜索过滤不会取消之前的勾选。 |

<!-- docs-asset: workspace-create -->

提交前检查最终路径。代码目录和 `.code-workspace` 文件必须尚不存在，ReqWS 不会覆盖它们。若默认目录后来被删除或失去访问权限，需要重新选择；本次选择不会自动改动全局设置。

点击 **创建工作区** 后，仓库会依次克隆。分支按以下顺序处理：已有本地同名分支时使用它；否则跟踪远端同名分支；远端也没有时，从该仓库配置的远端默认分支新建。

操作结束后，工作区列表应出现 `checkout-flow`，状态为 **就绪**。每个仓库都有独立 `.git`；`.code-workspace` 中包含该需求的仓库目录。

<!-- docs-asset: workspace-ready -->

操作窗口打开时请保持 ReqWS 运行。当前不支持取消、并发克隆、浅克隆或字节级进度。失败时先查看错误：尚未完成的临时目录会被清理；如果最终目录或工作区文件已写好、随后才发生保存错误，ReqWS 会保留这些文件，并在错误详情中说明恢复位置。

<a id="8-打开和维护工作区"></a>

## 打开工作区

工作区列表可以按名称、分支、仓库名、代码路径或工作区文件路径搜索。找到状态为 **就绪** 的工作区后，选择对应入口：

| 入口 | 打开什么 |
|---|---|
| VS Code | ReqWS 生成的 `.code-workspace`。 |
| 列表中的 Cursor | 在新的 Cursor IDE 窗口中打开 `.code-workspace`。 |
| 详情中的 Cursor 菜单 | “打开工作区文件”打开 `.code-workspace`；“用 Cursor 打开代码目录”打开代码根目录。 |
| GoLand | ReqWS 准备的独立 IDE 入口。先安装插件，再按下方说明操作。 |
| 在 Finder 中显示 | 工作区代码根目录。 |

编辑器没被检测到，或工作区尚未就绪时，相应按钮不可用。Cursor 通常使用应用内置的 CLI，不要求你另外安装 PATH 中的 `cursor` 命令；旧版或非标准 bundle 缺少 CLI 时会退回系统打开方式，可能无法正确从 Agents Window 打开工作区，可更新或重新安装官方 Cursor。

### 在 GoLand 中使用受管工作区

打开工作区详情，在 **GoLand 工作加载** 中选择 **默认全部**，或用 **指定仓库** 勾选本次需要的仓库，然后点击 **保存并打开 GoLand**。

不要用 GoLand 直接打开普通代码根目录来代替这一步。Desktop 会准备 `.reqws/ide/goland` 入口；插件读取其中的绑定信息，才知道该加载哪个工作区。

保存成功只表示配置已写入。是否已经在 IDE 中生效，要去 GoLand 的 ReqWS 面板查看，并在普通 Project 面板展开仓库目录确认。完整安装、状态解释和 Git 根目录配置见[GoLand 插件使用指南](goland-plugin-guide.md)。

## 管理已有工作区

打开工作区详情。**工作区文件**可展开查看完整路径和文件维护说明；增减成员、遗忘记录在 **管理工作区** 中。

<!-- docs-asset: workspace-management -->

| 操作 | 会发生什么 | 不会发生什么 |
|---|---|---|
| 添加仓库 | 克隆仓库，准备工作区功能分支，更新成员清单和 `.code-workspace`。 | 不自动推送分支。 |
| 从工作区移除仓库 | 从成员清单和 `.code-workspace` 中移除。 | 不删除磁盘上的仓库目录。 |
| 重新生成工作区文件 | 按成员清单重新生成 `.code-workspace`。 | 不保留你手工加在该文件中的 settings。 |
| 移除工作区记录 | 从 ReqWS 列表中移除这条记录。 | 不删除代码、成员清单或 `.code-workspace`。 |
| 取消 GoLand 中的某个加载勾选 | 保存后，插件不再请求加载该仓库。 | 不移除需求成员，不切分支，也不修改 `.code-workspace`。 |

添加新成员后，“默认全部”会加载它；“指定仓库”不会自动勾选它。只是暂时不需要某个仓库时，用加载选择，不要通过移除成员实现。移除记录后也不要假定能自动导回：当前没有自动导入或路径重映射流程。

<a id="5-设置语言和默认目录"></a>

## 设置语言和默认目录

在左侧打开 **设置**。界面语言可选 **跟随系统**、**简体中文**、**English**；点击 **保存设置** 后立即生效，重启后仍保留。

**工作区默认父目录**和 **`.code-workspace` 文件存放目录**用于预填以后的创建表单。设置页通过目录选择器选路径；可以留空，但每次创建时就需要手动选择。已保存目录失效后，要回到设置中重新选择。

<!-- docs-asset: desktop-settings -->

<a id="4-首次使用前配置-git"></a>

## Git 连接准备

### SSH 仓库

SSH 可以使用 `~/.ssh/config`、`known_hosts` 和 SSH Agent。带口令的私钥先在终端加载：

```bash
ssh-add ~/.ssh/id_ed25519
```

首次连接主机时，也先在终端完成主机身份确认。ReqWS 会继承正常的 `HOME` 和 `SSH_AUTH_SOCK`，但应用中的 Git 操作不能回答交互提示。

### HTTPS 仓库

HTTPS 使用系统 Git 的 credential helper，例如已配置的 macOS Keychain。不要把用户名、密码或 Token 填进仓库 URL、query 或 fragment。旧配置中不符合当前规则的地址会保留显示，但修正前不能用于新的工作区清单。

<a id="9-状态与恢复"></a>

## 操作失败时先看哪里

| 状态或错误 | 先做什么 |
|---|---|
| 就绪 | 路径和管理文件可用，可以继续打开和维护。 |
| 路径缺失 | 打开详情，确认缺的是代码目录、成员清单还是 `.code-workspace`。仅后者缺失且代码目录、清单有效时，可以重新生成。 |
| 异常 | 先复制错误日志，核对保留的文件，再决定恢复方式。不要手改清单或新建同名目录覆盖现场。 |
| `GIT_NOT_FOUND` | 安装并确认系统 Git 可用，再刷新；也要检查从 Finder 启动的 App 能否找到 Git。 |
| `REPOSITORY_UNREACHABLE` / `CLONE_FAILED` | 在终端用相同 URL 检查网络、凭据和 SSH 主机身份。 |
| `DEFAULT_BRANCH_NOT_FOUND` | 修正仓库列表中的默认分支，再重新创建。 |
| `WORKSPACE_ROOT_EXISTS` / `WORKSPACE_FILE_EXISTS` | 换名称或位置。若只是旧记录占用路径，先核对磁盘文件，再移除旧记录。 |

错误面板会显示错误码、阶段和技术信息，并提供复制日志按钮。报告问题时附上版本、做了什么、错误码和脱敏日志，不要上传凭据或私钥。若 state 损坏，保留 ReqWS 生成的 `.corrupt-*` 副本，不要覆盖它。

<!-- docs-asset: desktop-error -->

<a id="10-数据位置与备份"></a>

## 数据在哪里

典型的全局状态位置：

```text
~/Library/Application Support/ReqWS/reqws/state.v1.json
```

工作区内的主要文件：

```text
<workspace-root>/.reqws/workspace.json
<workspace-root>/<repository-name>/.git/
```

使用 GoLand 后还会有 `.reqws/ide/goland` 入口；`.code-workspace` 可以放在另一个目录。备份应用数据前先退出 ReqWS，保留全局 state、整个工作区和对应的 `.code-workspace`。当前没有云同步或跨机器自动迁移，不要只复制 state 就假定所有路径会自动改变。

<a id="11-当前限制"></a>

## 当前不做的事

ReqWS 仅面向 macOS，不提供 Windows/Linux 应用；不提供 Git worktree、批量 pull/merge/rebase/push、PR/MR 或测试运行器。创建之后，不会持续替你检查或切换各仓库分支。

GoLand 插件只支持当前文档注明的目标版本，不生成或修改 `go.work`，不管理语言工具链，也不从 IDE 回写工作区成员。Git Directory Mappings 由用户维护，旧 ownership/lock 文件不会自动迁移或清理。

默认源码构建使用 ad-hoc 签名，不启用更新。个人签名版的更新、平台限制和首次安装要求见[安装指南](installation.md)。移除、遗忘操作有意保留磁盘文件，真正删除由你在核对路径后自行处理。

<a id="12-依据与进一步资料"></a>

## 继续查阅

GoLand 操作看[插件使用指南](goland-plugin-guide.md)，开发命令看[开发指南](development-guide.md)。功能设计和按次验证记录从[需求与变更索引](../changes/README.md)进入；[历史参考](../reference/README.md)中的原型和旧方案不代表当前界面。
