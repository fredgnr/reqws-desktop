---
title: 文档图片与素材清单
type: guide
status: active
updated: 2026-09-21
---

# 文档图片与素材清单

本地 Agent 按这份清单补拍真实界面，将图片放到对应步骤旁边。当前 15 项静态配图全部待拍，另有 1 项可选录屏；本文的画面描述、alt 和图注是制作要求，不是已经取得的证据。

## 统一示例和拍摄要求

用同一个工作区 `checkout-flow` 串起所有图，成员名称为 `api`、`web`、`shared`，创建时分支为 `feature/checkout-flow`。第一次在 GoLand 中加载 `api` 和 `shared`；演示切换时改为 `api` 和 `web`。

使用获准且可以公开展示的 Git 测试仓库，示例路径使用 `/Users/demo/Work/checkout-flow` 一类不含私人信息的形式。示例仓库中可放 `docs/overview.txt` 等普通文本，便于在 Project 面板展开确认；不要求 Go 项目、`go.mod` 或语言服务就绪。没有合适环境时记录缺口，不为截图绕过应用的 URL 校验或擅自创建远端仓库。

正式使用截图来自当前实际 App/插件。浏览器 fixture 或原型只能明确标为“演示数据/原型”，不能替代安装、IDE 加载或更新的真实画面。不要复用旧版本截图而不标注来源。

静态图统一计划存放在 `docs/assets/screenshots/`，文件名与下表 ID 一致，扩展名 `.png`。该目录尚未创建；添加首张图时，同时建立 `docs/assets/README.md`、`docs/assets/screenshots/README.md`，并把上级入口加入 `docs/README.md`。每张实际图片都要收录到最近一级索引，状态使用项目允许的值。

建议在高分辨率下截图，裁到相关区域，保证缩至 GitHub 正文宽度后仍能读到按钮。编号要对应正文动作，不能只靠颜色区分状态。不要在截图中重画按钮、篡改状态或制造不存在的功能。

## 第一批：首次阅读和上手必须补齐

以下 9 项为 P0。正文已用 `<!-- docs-asset: ID -->` 标好位置。

### overview

文件：`docs/assets/screenshots/overview.png`。位置：[项目首页](../../../README.md)“它解决什么问题”之后。

拍摄 Desktop 工作区列表，至少显示两个需求，其中 `checkout-flow` 有三个仓库。标注 ①工作区名称与分支，②仓库数量，③编辑器入口，④搜索。让读者看懂“按需求组织工作”，不要把状态卡片裁掉只剩一排按钮。

Alt：ReqWS 工作区列表中有两个独立需求，每行显示分支、仓库和编辑器入口。
图注：一个需求对应一个工作区；找到它后，可以直接用编辑器打开。

### repository-add

文件：`docs/assets/screenshots/repository-add.png`。位置：[Desktop 指南](../../guides/user-guide.md#添加仓库)。

拍“添加仓库”对话框，展示 ①Git 仓库地址，②名称，③默认分支，④测试连接结果，⑤添加仓库按钮。URL 使用真实可用且可公开的测试地址，图中不出现凭据。

Alt：添加仓库对话框填写了地址、名称和默认分支，连接测试已返回结果。
图注：这里只登记仓库；创建工作区时才会克隆代码。

### workspace-create

文件：`docs/assets/screenshots/workspace-create.png`。位置：[Desktop 指南](../../guides/user-guide.md#创建第一个工作区)。

拍完整创建表单，填写统一示例，勾选三个仓库。标注 ①工作区名称和功能分支，②最终代码目录，③`.code-workspace` 存放目录，④仓库选择，⑤提交按钮。代码父目录与最终目录不要混为一谈。

Alt：创建 checkout-flow 工作区的表单，列出功能分支、代码路径、工作区文件位置和三个选中仓库。
图注：代码目录和编辑器工作区文件可以放在不同位置；提交前确认最终路径。

### workspace-ready

文件：`docs/assets/screenshots/workspace-ready.png`。位置：同一篇 Desktop 指南中“操作结束后”一段。

用同一工作区拍操作完成后的列表或详情，清楚展示名称、分支、三个成员、**就绪**和编辑器按钮。不能把创建中的画面配上成功图注。

Alt：checkout-flow 工作区已就绪，包含三个仓库，可用编辑器按钮已经启用。
图注：状态变为“就绪”后，再用编辑器打开。

### plugin-install

文件：`docs/assets/screenshots/plugin-install.png`。位置：[GoLand 指南](../../guides/goland-plugin-guide.md#安装插件)。

一张图可以由同次真实操作的两帧组成：①Plugins 齿轮菜单中的 Install Plugin from Disk，②选定 ZIP 后显示的 ReqWS 名称和版本。展示 ZIP 文件名，但不要暴露私人目录；核对当前目标 build。未取得安装/重启授权时，不为了截图执行操作。

Alt：GoLand 的磁盘安装插件入口和待安装 ReqWS 插件的名称、版本。
图注：选择独立的 goland-plugin ZIP，不是 Desktop App 或 Source code ZIP。

### goland-selection-result

文件：`docs/assets/screenshots/goland-selection-result.png`。复用位置：[项目首页](../../../README.md)的 Desktop/插件分工、[GoLand 首次打开](../../guides/goland-plugin-guide.md#首次打开工作区)。

使用同一工作区制作左右对照，必要时改为上下排列以保证可读性。左侧是真实 Desktop：“指定仓库”，勾选 `api`、`shared`，保留“保存并打开 GoLand”。右侧是真实 GoLand：普通 Project 面板展开这两个仓库的 `docs/overview.txt`，ReqWS 面板显示对应状态，并能说明 `web` 未加载。正常状态下不应把 `.reqws` 或独立入口显示为业务目录。

Alt：Desktop 选中了 api 和 shared，GoLand 的 Project 面板对应显示这两个仓库，web 仍是未加载成员。
图注：左侧决定要加载哪些仓库，右侧确认结果；未加载的仓库仍保留在工作区和磁盘上。

这张图只说明项目内容，不暗示 Git Log/Commit 已按同样范围筛选。不要用 Find in Files 结果替换右侧 Project 画面。

### goland-switch-selection

文件：`docs/assets/screenshots/goland-switch-selection.png`。位置：[换一组要加载的仓库](../../guides/goland-plugin-guide.md#换一组要加载的仓库)。

拍同次切换的前后对照：从 `api + shared` 改为 `api + web`，保存后在普通 Project 面板展开文件确认。标明“保存前/同步后”，不要把未保存草稿当成已生效配置。另在文字中保留空选择仍保留用户内容的限制。

Alt：加载选择从 api 和 shared 改为 api 和 web，保存后 GoLand 项目内容随之改变。
图注：回到 Desktop 换一组仓库并保存；没有删除原仓库，也没有改变需求成员。

### goland-git-roots

文件：`docs/assets/screenshots/goland-git-roots.png`。位置：[GoLand 指南](../../guides/goland-plugin-guide.md)“提示需配置 Git 根目录怎么办”。

展示 ①ReqWS 的“需配置 Git 根目录”提示，②Settings → Version Control → Directory Mappings 中具体仓库目录及 Git 类型。配置应由用户操作，画面不暗示插件自动填写。保留与此次操作无关的用户映射。

Alt：ReqWS 提示需配置 Git 根目录，GoLand 的 Directory Mappings 中可以为仓库选择 Git。
图注：项目内容加载与 Git 根目录配置是两回事；插件只检查，不修改映射。

### workspace-management

文件：`docs/assets/screenshots/workspace-management.png`。位置：[管理已有工作区](../../guides/user-guide.md#管理已有工作区)。

拍详情中的“管理工作区”和真实移除确认框，标注 ①添加成员，②移除成员，③移除工作区记录，④保留本地文件的提示。重新生成文件入口若不在同一区域，不要拼成它在同一菜单下；用另一局部或在图注明确入口。

Alt：工作区管理区提供成员操作，移除确认框说明不会删除本地文件或文件夹。
图注：移除成员、移除记录和删除磁盘文件不是同一个操作。

## 第二批：设置、排障和安装补充

以下 6 项为 P1。没有真实授权环境或触发条件时保持待拍，不制造系统提示或错误。

| ID / 文件名 | 正文位置 | 必须展示的内容 | Alt 和图注草案 |
|---|---|---|---|
| `desktop-settings.png` | [Desktop 指南](../../guides/user-guide.md#设置语言和默认目录) | 界面语言、两个默认目录、保存设置；使用非敏感路径。 | Alt：设置页中的语言和新工作区默认位置。图注：默认目录用于以后的创建表单，改完要保存。 |
| `desktop-error.png` | [Desktop 排障](../../guides/user-guide.md#操作失败时先看哪里) | 可控测试失败的真实错误码、阶段、详情和复制日志按钮；不破坏真实工作区。 | Alt：操作错误面板显示错误码、阶段及复制日志入口。图注：报告问题时保留错误码和操作阶段，不只截一句失败。 |
| `goland-diagnostics.png` | [ReqWS 面板说明](../../guides/goland-plugin-guide.md#读懂-reqws-面板) | 工作区/分支/状态、仓库状态和三个按钮；必要时单独裁一张局部，不露完整私人诊断。 | Alt：GoLand ReqWS 面板中的状态、仓库列表及三个操作按钮。图注：立即同步用于重新检查，复制诊断信息用于报告问题。 |
| `macos-first-open.png` | [Desktop 安装](../../guides/installation.md#安装-desktop) | 真实未知开发者提示与隐私安全页中仅针对 ReqWS 的放行入口；记录 macOS 版本。 | Alt：macOS 隐私与安全性中针对 ReqWS 的首次打开提示。图注：核实来源后只为这个 App 放行，不关闭全局保护。 |
| `desktop-update.png` | [应用内更新](../../guides/installation.md#个人签名版本的应用内更新) | 同一真实安装版的检查、发现版本、下载完成、安装确认；并列帧标明先后。无新版本时只拍实际状态，不能伪造可更新。 | Alt：ReqWS 设置页中的检查、下载和安装更新步骤。图注：用户逐步确认更新；下载完成后仍需在安装阶段验证签名。 |
| `certificate-trust.png` | [公开证书信任](../../guides/installation.md#安装与信任公开证书) | login 钥匙串、已核实的公开证书和仅 Code Signing 信任项，其他用途默认。没有授权时不导入或改信任。 | Alt：公开证书的代码签名信任项，其他用途保持默认。图注：仅在该个人签名版本需要时添加信任，普通用户不导入私钥。 |

P1 表格中的 ID 为文件名去掉 `.png`，与正文注释完全一致。

## 可选录屏和其他素材

可选录屏 `workspace-walkthrough.mp4`：用约 30–60 秒展示选择已有仓库、创建工作区、状态就绪、打开编辑器。较长的克隆等待可以剪掉，但要标注跳过等待，不能借剪辑声称实际创建耗时。静态步骤和文字必须独立成立，视频不是必读材料，也不是自动化验收证据。

另外需要一份拍摄记录，放在实际截图目录的 README 中，记录每张图的来源 commit、App/插件版本、GoLand 完整 build、macOS、界面语言、拍摄日期及数据来源。不要把软件包或源码的逐文件 checksum 清单写进文档；包校验沿用 Release 或测试日志。

目前 README 的目录示例可直接阅读，不必为了装饰再制作架构海报。确实要补概念图时，标明“示意图”，且只表达工作区隔离和两端分工，不画不存在的控制入口。

## 嵌入方式和完成条件

在对应 `docs-asset` 注释处加入实际 Markdown 图片链接，随后紧跟图注；可以保留注释方便下次替换。相对路径按所在正文计算，例如 README 从 `docs/assets/...` 引用，指南从 `../assets/...` 引用。不要直接把本地绝对路径写进链接。

同一图片复用时不要复制成多个内容相同的文件。截图目录 README 用表格收录每个实际资产，状态使用 `active` 等允许值，并写明用途和版本。可选视频应按项目规范建立自己的索引位置。

完成 P0 后，逐条核对“正文动作 → 图中编号 → 真实结果”。未完成项继续标为待拍；图片存在、链接有效、图注正确、缩小后可读，才算该项完成。最后运行 `npm run docs:check`，再按[本地交接](local-agent-handoff.md)做首次阅读验收。
