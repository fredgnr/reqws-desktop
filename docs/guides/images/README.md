# 用户指南图片

本目录保存用于解释 ReqWS 使用流程的界面截图和关系示意图；图中的仓库地址、用户名和业务数据均为虚构。

## 图片索引

| 图片 | 状态 | 内容与用途 |
|---|---|---|
| [工作区概览](workspaces-overview.png) | active | 两项需求、各自分支和编辑器入口；用于项目首页与创建完成步骤。 |
| [工作区关系图](workspace-model.svg) | active | 仓库配置与独立 clone 的关系；手工编写的 SVG 示意，非产品界面。 |
| [添加仓库](add-repository.png) | active | Git 地址、名称、默认分支及测试/保存入口。 |
| [创建工作区：基本字段](create-workspace-fields.png) | active | 名称、功能分支、最终代码目录和入口文件目录；表单上半部分。 |
| [创建工作区：选择仓库](create-workspace-repositories.png) | active | 勾选三个相关仓库及创建按钮；表单下半部分。 |
| [默认目录与语言](settings-directories.png) | active | 默认父目录和 `.code-workspace` 目录的区别；截图为开发模式。 |
| [GoLand 加载选择](goland-loading-selection.png) | active | 三个成员中选择两个加载进 GoLand；截图停在未保存草稿。 |
| [工作区维护](manage-workspace.png) | active | 详情滚动至成员增删和移除记录区域，说明磁盘内容保留。 |

## 来源与边界

- 截图日期：2026-09-26。
- 原始界面截图来自 [bb11fa7](https://github.com/fredgnr/reqws-desktop/commit/bb11fa7bf64990a4a6bbdbfc134485d53a507e58) 的 React 组件、样式和中文资源。rebase 后已用 Git diff 核对：所涉及的界面组件、样式及 GoLand 加载选择契约与 v0.1.7 相同。
- 设置页已在 rebase 到 `main@e90cd31` 后的 `c5fd111` 源码上重新截取，示例 API 的版本标注为 0.1.7；其余图不含旧版本标注，保留原始截图。
- 截取方式：Vite 本地 renderer 预览，通过内存中的 typed-API 形状示例提供仓库、工作区和编辑器可用性，再通过实际界面交互截取。没有使用真实 userData、个人仓库、Git 远端连接或 IDE 启动。
- 这些图片解释按钮和数据关系，不是 clone 成功、发布版自更新或 GoLand 插件同步的验收证据。设置图中的“开发模式”提示属于截图预览环境，不代表 v0.1.7 正式发布包禁用了更新。
- PNG 为未经重绘的 1280×720 界面截图；较长表单按上下部分分别展示。SVG 的文字与结构用于概念说明，不冒充应用功能页面。

更新对应界面后，应同时检查截图、文中按钮名称和图注；不要复用旧的 GoLand 项目模型截图作为当前插件状态证明。只提交无凭据的示例数据，保留原始截图，避免用图片替代可搜索的操作文字。

返回[指南目录](../README.md)。
