# 使用与开发指南

第一次使用先读图解教程；安装、更新与 GoLand 插件分别有独立指南。源码候选与正式发布的差异在各页标明，需求与历史证据从文档总索引查找。

| 文档 | 状态 | 说明 |
|---|---|---|
| [图解使用教程](user-guide.md) | active | 用一个退款需求示例，演示仓库登记、工作区创建、编辑器打开、成员维护与数据恢复。 |
| [安装与更新](installation.md) | active | 说明发布包选择、首次启动、个人签名证书信任、应用内更新和源码安装。 |
| [GoLand 插件图解](goland-plugin-guide.md) | active | 说明独立 ZIP 安装、版本范围、两类加载模式、Project 面板核对、Git 配置和排障。 |
| [图片与来源](images/README.md) | active | 保存虚构数据的真实 Desktop 界面截图、工作区关系图及版本范围。 |
| [开发指南](development-guide.md) | active | 说明 Desktop/GoLand 开发环境、进程边界、阶段验证、通用追踪、签名凭据维护及打包发布流程。 |
| [Actions 缓存分层与复用](actions-cache-policy.md) | active | 说明默认分支写入、跨分支只读复用、依赖分层、CI 拆分、占用快照和迁移验收。 |
| [Actions PR 缓存清理](actions-cache-cleanup.md) | active | 说明合入后临时缓存清理范围、主分支保护、启用验收和排障。 |
| [Agent 协作指南](agent-workflow.md) | active | 说明任务提示词、项目技能路由（含签名凭据维护）、授权边界与指令回归评估。 |

指南描述当前代码的常青用法。查找需求背景、技术取舍或按次验证结果时，从[文档总索引](../README.md)进入相应需求包。
