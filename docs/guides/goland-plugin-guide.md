---
title: ReqWS GoLand 插件使用指南
type: guide
status: active
updated: 2026-09-21
---

# ReqWS GoLand 插件使用指南

先在 Desktop 中创建工作区，再用插件把选中的仓库放进同一个 GoLand 项目。**仓库和加载选择在 Desktop 中管理；在 GoLand 中查看代码、确认同步状态和处理 Git 根目录提示。**

还没有工作区时，先完成 [Desktop 使用指南](user-guide.md#创建第一个工作区)。VS Code/Cursor 用户不需要安装这个插件。

<a id="1-安装条件与构建"></a>

## 安装插件

当前插件只针对 **macOS GoLand 2026.2.1.1 / GO-262.9437.286**。先在 GoLand 的 About 中核对完整 build，不要仅凭“2026.2”判断兼容。不要改插件描述文件强行安装到其他版本。

从[项目 Releases](https://github.com/fredgnr/reqws-desktop/releases)下载与 Desktop 配套的 `ReqWS-<版本>-goland-plugin.zip`，按同一 Release 的 `SHA256SUMS` 核对文件。**这是插件 ZIP，不是 macOS App ZIP，也不是 GitHub 自动生成的 Source code ZIP。**

1. 打开 **GoLand Settings → Plugins**。
2. 在齿轮菜单中选择 **Install Plugin from Disk**，选择插件 ZIP，不要先解压。
3. 核对插件名称 **ReqWS**（ID 为 `com.reqws.workspace`）和版本。
4. 按 IDE 提示保存工作并重启，确认插件已启用。

<!-- docs-asset: plugin-install -->

安装 Release 插件不需要单独准备 Node.js、JDK 或 Gradle。正式 Release 的插件 ZIP 使用作者签名；本地构建 ZIP 是未签名测试包。Marketplace 是否已可安装或更新，要看实际审核结果，不能由 Release 成功推断。磁盘安装的更新也可按上述步骤进行；Desktop 更新不会替你安装或更新插件。

### 从源码构建插件

只在需要测试源码版本时使用。构建需要 Node.js 24、JDK 25 和仓库锁定的工具链；在仓库根目录执行：

```bash
npm run check:goland
npm run package:goland
```

ZIP 在 `integrations/goland/build/distributions/`。有多个旧文件时，不要随意选择，应核对本次构建输出。构建、SDK 和准确产物定位方法见[插件开发入口](../../integrations/goland/README.md)。构建成功不代表已安装，更不代表真实 IDE 验收已完成。

Agent 不应借文档或构建任务安装插件、重启你的日常 IDE。此类操作需要单独调用 manual-only 的 `$reqws-goland-plugin-install`，并确认具体 ZIP 和目标 IDE。

## 首次打开工作区

用一个包含 `api`、`web`、`shared` 的工作区举例：

1. 在 **Desktop → 工作区** 找到状态为 **就绪** 的工作区，打开详情。
2. 在 **GoLand 工作加载** 中选 **指定仓库**，只勾选 `api` 和 `shared`。
3. 点击详情底部的 **保存并打开 GoLand**。只点“保存选择”不会打开 IDE。
4. GoLand 打开项目后，只在确认项目来源可信时选择信任。安全模式下插件只读展示，不会应用加载选择。
5. 打开右侧的 **ReqWS** 工具窗口，查看工作区名称、分支和状态。再到普通 **Project** 面板展开 `api`、`shared`，确认能看到其中的文件。

<!-- docs-asset: goland-selection-result -->

预期结果：选中的仓库进入项目内容；`web` 仍属于需求，但显示为 **未加载**。正常同步后，普通 Project 面板不应把 ReqWS 的内部入口和 `.reqws` 文件当作工作内容展示。初次读取和安全模式期间可能暂时看到入口内容。

Desktop 显示“选择已保存”只说明文件写好了，不代表插件已安装或已同步。插件显示“已同步”也不代表 Go SDK、语言分析、补全或运行配置已经就绪。

<a id="2-在-desktop-选择加载集合"></a>

## 换一组要加载的仓库

不必退出工作区，也不要通过手工 exclude 来代替选择。回到 Desktop 的 **GoLand 工作加载**，修改后点击 **保存选择**；已打开的插件会读取新配置。需要同时打开 IDE 时，使用 **保存并打开 GoLand**。

| 选择方式 | 当前会加载什么 | 以后给需求新增仓库时 |
|---|---|---|
| 默认全部 | 所有当前成员。 | 新成员也自动加载。 |
| 指定仓库 | 只加载勾选且仍属于需求的成员。 | 不会自动勾选新成员。 |
| 指定仓库，但全部取消勾选 | 不请求加载任何 ReqWS 仓库。 | 不会偷偷恢复成全部。 |

<!-- docs-asset: goland-switch-selection -->

比如下一步要做前端，在 Desktop 勾选 `web`，取消 `shared`，保存后去 GoLand 确认结果。`shared` 的目录和代码不会被删除，分支不会被切换，VS Code/Cursor 的 `.code-workspace` 也不变。

你自己添加到 GoLand 的其他项目根会保留。因此，“全部取消勾选”不保证整个 Project 面板变空，Libraries、Scratches 或用户内容仍可能显示。

有未保存修改时可以点击 **取消**。**重新加载已保存配置**会读取磁盘上的选择，不会替你保留当前草稿；出现并发或绑定冲突时，先记下自己的意图，再重新加载并选择。已有绑定遇到临时写入失败，可先检查权限和空间，再重试当前草稿；初次创建入口中断不能按这一方式自动认领。

已移出需求的旧选中 ID 暂不生效，同 ID 再加入时可能恢复原选择；一旦保存新的选择，Desktop 只保留当前成员并清理失效 ID。

<a id="4-project-面板与状态"></a>

## 读懂 ReqWS 面板

| 界面内容 | 怎么理解 |
|---|---|
| 工作区、分支 | 当前绑定的工作区和配置中的功能分支；不是每个仓库实时 Git 状态的汇总。 |
| 已同步 | 当前 ReqWS 配置已通过插件检查。语言服务是否就绪是另一件事。 |
| 部分可用 | 部分内容或 Git 根目录检查需要处理；继续查看各仓库的提示。 |
| 已加载 / 未加载 | 该仓库是否按当前选择进入项目内容；“未加载”通常不是错误。 |
| 目录缺失 | 请求加载的目录不存在；原选择仍保留。 |
| 被用户项目根包含 | ReqWS 没选中它，但你自己添加的其他项目根仍包含它。插件不会删除这些配置。 |
| 项目内容未生效 / 错误 | 项目内容、目录归属或读取检查失败，先查看诊断信息。 |

面板上的三个按钮不是同一种“同步”：

| 按钮 | 用途 |
|---|---|
| 立即同步 / Sync Now | 重新读取并检查当前配置。不会替你保存 Desktop 草稿，也不会增删 Git Directory Mappings。 |
| 打开清单文件 / Open Manifest File | 查看工作区成员清单，用于了解配置，不要把手工改 JSON 当作正常管理方式。 |
| 复制诊断信息 / Copy Diagnostics | 复制版本、状态和错误信息，方便报告问题；分享前仍要检查是否包含不宜公开的路径。 |

<!-- docs-asset: goland-diagnostics -->

<a id="5-git-配置与保护"></a>

## 提示“需配置 Git 根目录”怎么办

能在 Project 面板看到代码，不等于 GoLand 已把该目录配置为 Git 根。插件只检查，不替你修改。

1. 打开 **Settings → Version Control → Directory Mappings**（中文界面为“设置 → 版本控制 → 目录映射”）。
2. 根据 ReqWS 的提示，为需要的仓库目录配置 **Git**。选择仓库自身的目录，不要把内部入口当成仓库。
3. 应用设置后回到 ReqWS 面板，等待刷新，或点击 **立即同步**重新检查。

<!-- docs-asset: goland-git-roots -->

不要删除不认识的映射来消除提示。未加载仓库的映射和额外映射仍是用户配置；选择只加载两个仓库，**不保证 Git Log/Commit 也只显示这两个仓库**。原生 Git 操作和筛选继续由 GoLand 管理。

<a id="3-唯一入口"></a>

## 为什么要从 Desktop 打开

Desktop 打开的不是普通代码根目录，而是以下独立入口：

```text
<workspace>/.reqws/ide/goland/
  reqws-project.json
  .idea/
```

Desktop 用它记录“这是哪个工作区、选择了哪些仓库”。插件只读取这些业务配置，再调整属于 ReqWS 的项目内容。`.reqws/workspace.json` 中的成员信息也只有 Desktop 写入。

直接打开原工作区根目录或任意普通目录，不会触发本版插件的适配。不要手工预建入口、复制旧 `.idea` 或改绑定文件；Desktop 不迁移或删除原工作区的 `.idea`。入口已存在但来源不明、属于其他工作区或创建不完整时，会报冲突并保留原内容。

<a id="6-故障处理"></a>

## 遇到问题时

| 现象 | 处理方式 |
|---|---|
| 非 ReqWS 项目 | 确认插件已启用，并从 Desktop 的正确工作区重新打开，而不是直接打开普通代码根目录。 |
| 安全模式 | 确认项目来源后再信任；信任前只读展示是正常限制。 |
| Desktop 已保存，IDE 仍是旧选择 | 先核对工作区名称，再点击“立即同步”，查看各仓库状态和诊断信息。 |
| 绑定错误或入口冲突 | 核对 Desktop 工作区与当前 IDE 入口是否对应。保留原文件；完整配置恢复后插件会重新检查。 |
| 所有权冲突 | 用户根、标记或子配置发生了变化，插件拒绝冒险删除。先复制诊断，核对用户配置，不要删 module、ledger 或 `.idea` 强行重试。 |
| 目录缺失 | 检查仓库是否被移动或删除。不要创建空目录冒充仓库来让状态变绿。 |
| 保存失败 | 临时权限或空间问题修复后按提示重试；绑定或版本冲突需重新加载。初次中断的入口保留现场，交由维护者核对。 |

绑定失效时，插件会保留上次有效项目模型，暂停受管增删，并撤销内部入口适配；此时视图不代表新选择已生效。旧 ownership 文件不参与本版删除权限，也不会自动清理。

排障不需要清空 `.idea`、删除仓库或强制重装。插件不执行 clone/fetch/checkout 等 Git 操作，不访问仓库 URL，不写语言或构建配置，也不会删除你自己添加的项目根。

实现边界见[技术方案](../changes/goland-workspace-loading/technical-design.md)，已执行的检查见[实施记录](../changes/goland-workspace-loading/implementation-2026-09-19.md)。验证“文件是否可见”时，直接在普通 Project 面板展开具体目录；不要用 Find in Files 的结果代替项目视图。
