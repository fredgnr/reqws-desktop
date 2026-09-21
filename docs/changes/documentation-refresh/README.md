# 文档改进与本地交接

本轮先把产品首页和使用路径改清楚，再由本地 Agent 补真实截图、核对图文并完成阅读验收。只改文档，不修改产品行为、翻译资源、安装配置或发布流程。

| 文档 | 状态 | 说明 |
|---|---|---|
| [图片与素材清单](visual-assets.md) | active | 列出 15 项静态配图、1 项可选录屏，以及每项的正文位置、画面要求和图注。 |
| [本地 Agent 交接](local-agent-handoff.md) | active | 说明接手顺序、可拆分任务、剩余中文复核范围和验收条件。 |

## 本轮已改什么

[项目首页](../../../README.md)先讲多仓需求场景、Desktop/插件分工和第一次使用，不再把证书配置、安全实现和开发检查放进上手主线。

[指南索引](../../guides/README.md)分为用户与开发者两条路径。[安装与更新](../../guides/installation.md)集中说明 Release、源码安装、首次启动、证书和更新；[Desktop 指南](../../guides/user-guide.md)按仓库登记、工作区创建、编辑器打开和维护组织；[GoLand 指南](../../guides/goland-plugin-guide.md)按安装、打开、选择仓库、确认结果和排障组织。[插件 README](../../../integrations/goland/README.md)也先给用户入口，再保留构建与内部边界。

三种概念分别解释：仓库列表、工作区成员、GoLand 加载选择。关键操作保留“不删除本地代码”“保存不等于 IDE 已同步”“加载选择不等于 Git Log/Commit 筛选”等限制。

[文档规范](../../standards/documentation-standard.md)、目录索引和[中文写作规范](../../standards/chinese-writing.md)同步调整；现有 documentation skill 已链接中文写作要求。未修改 UI catalog，没有绕过 i18n 流程。

## 核对依据和事实修正

代码基线为 `30ea9432efe7b9062ed68a7aa803543288f45abd`（2026-09-21 读取的 `main`）。文案依据用户指定的 [shuorenhua SKILL](https://github.com/MrGeDiao/shuorenhua/blob/5a9eafefe03807404135f4d2ee4f42fe61d58759/SKILL.md)，本次有结构调整授权，不限于原句润色。

| 原文问题 | 处理依据与改法 |
|---|---|
| 首页和 Desktop 指南仍把源码构建放在安装主线。 | 已核对 [v0.1.5 Release](https://github.com/fredgnr/reqws-desktop/releases/tag/v0.1.5) 的 App、插件、校验文件和更新元数据，改为先介绍下载使用，再介绍源码。 |
| 首页/旧指南出现 JDK 21、`since-build: 261`、261/262 通用支持等旧说法。 | 按当前[构建配置](../../../integrations/goland/build.gradle.kts)和[插件入口](../../../integrations/goland/README.md)说明 JDK 25、唯一 GO-262.9437.286 目标；区分安装成品与源码构建条件。 |
| 首页开头仍说把 workspace root 交给插件。 | 改为从 Desktop 打开独立 `.reqws/ide/goland` 入口，普通根目录不触发当前适配。 |
| “当前不签名、不通过 Release 分发”与现状冲突。 | 当前 Release 提供独立作者签名插件；Marketplace 状态仍以真实审核为准，不能把 bootstrap 或发布成功写成已上架。 |
| 状态名称和操作入口不便于对照界面。 | 核对 [Desktop 中文资源](../../../src/renderer/locales/zh-CN.json)、[加载选择组件](../../../src/renderer/components/GoLandLoadingSection.tsx)、[插件中文资源](../../../integrations/goland/src/main/resources/messages/ReqwsBundle_zh_CN.properties)和[插件描述](../../../integrations/goland/src/main/resources/META-INF/plugin.xml)，使用真实名称。 |

本轮没有改写旧验收报告，也没有把“存在发布附件”当成干净用户安装、证书信任、两版本更新或当前 GUI 已通过的新证据。原使用指南文件路径不变；已迁移安装内容保留入口链接和原主要章节锚点。

## 还缺什么

真实截图和录屏尚未制作，正文中的 `docs-asset` 注释只是插入位置，不是图片或测试证据。配图任务在[素材清单](visual-assets.md)，验收顺序在[本地交接](local-agent-handoff.md)。当前正文没有引用尚不存在的图片文件。

本轮对 15 份改动文本做了基础检查：UTF-8、代码围栏、元数据、最近一级索引、改动文件之间的链接与章节锚点，以及 15 个配图 ID 的对应关系。该检查未发现错误，但没有覆盖未下载文件的全部链接和锚点。

本次环境未能通过 Git 下载完整 checkout（无法解析 `github.com`），通过 GitHub 连接器读取源码和提交文档；因此没有运行仓库级 `npm run docs:check`，也没有运行 macOS/GoLand GUI。对本轮文本的检查不能替代上述检查。本地接手时记录真实结果，不照抄为 PASS。
