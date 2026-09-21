# 使用与开发指南

按你现在要做的事选择文档。普通用户不需要先读开发环境、内部数据格式或发布流程。

## 安装和使用

| 文档 | 状态 | 说明 |
|---|---|---|
| [安装与更新](installation.md) | active | 选择正确安装包，安装 Desktop，处理首次启动提示，并区分应用更新与插件更新。 |
| [Desktop 使用指南](user-guide.md) | active | 从添加仓库开始创建第一个工作区，再学习搜索、打开、增减仓库和故障处理。 |
| [GoLand 插件使用指南](goland-plugin-guide.md) | active | 安装插件，从 Desktop 打开工作区，选择要加载的仓库，并在 IDE 中确认结果。 |

推荐顺序：**安装 Desktop → 创建工作区 → 用编辑器打开**。只有需要 GoLand 集成时，才继续安装和使用 GoLand 插件。VS Code/Cursor 不需要 ReqWS 插件。

## 开发和维护

| 文档 | 状态 | 说明 |
|---|---|---|
| [开发指南](development-guide.md) | active | 准备开发环境，定位代码，运行检查，以及构建和发布应用。 |
| [Agent 协作指南](agent-workflow.md) | active | 为 Agent 划定任务范围，选择项目 skill，并核对权限和交付结果。 |
| [Actions PR 缓存清理](actions-cache-cleanup.md) | active | 配置合入后的缓存清理，检查保护范围，并处理失败。 |

指南介绍当前操作。要查设计原因或某次测试结果，进入[需求与变更索引](../changes/README.md)。本轮截图和文档补全任务在[文档改进交接](../changes/documentation-refresh/README.md)。
