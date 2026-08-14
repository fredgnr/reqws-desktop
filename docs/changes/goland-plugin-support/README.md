# GoLand 插件支持

本目录记录 ReqWS 本次 GoLand 支持的完整需求与实施方案。本次只交付跨 IDE manifest 契约、ReqWS Desktop 的 GoLand 入口和可本地安装的 GoLand 插件 v0.1；双向控制、ReqWS 管理的 `go.work`、插件发布与远程开发均不进入本次设计和实现。

- 状态：draft
- 更新日期：2026-08-14
- 实施分支：`feat/goland-plugin-support`

## 文档

| 文档 | 类型 | 状态 | 说明 |
|---|---|---|---|
| [需求说明](requirements.md) | requirements | draft | 定义本次交付的用户行为、范围、边界和验收标准。 |
| [技术方案](technical-design.md) | technical-design | draft | 定义 Desktop、manifest、GoLand 插件、项目模型、VCS、构建和安全设计。 |
| [探索与实施计划](implementation-plan.md) | technical-design | draft | 为 macOS 本地 Codex agent 规定实验门禁、工作包、提交边界和退出条件。 |
| [测试与验证](testing/README.md) | testing | draft | 索引自动化测试、macOS GoLand GUI smoke 和后续按次验证证据。 |

## 本次交付边界

以下能力必须在同一个 feature 分支内形成一个可验收闭环：

1. 把现有 `.reqws/workspace.json` 固化为 Desktop 唯一写入、IDE 适配器只读的跨 IDE 契约；
2. 在 ReqWS Desktop 中增加 GoLand 探测、可用性展示和打开 workspace root 的入口；
3. 在同一仓库中增加可构建、测试、通过磁盘安装的 GoLand 插件；
4. 插件以 manifest 为唯一目标状态，同步活动仓库的项目内容、Git Root 和 ReqWS Tool Window；
5. 通过真实 macOS GoLand 验证新增仓库、逻辑移除但保留磁盘目录、重新添加、错误恢复和重启恢复。

本次明确不设计或实现：

- 从 GoLand 发起仓库增删、分支或其他 ReqWS 业务操作；
- GoLand 与 Desktop 之间的 URL scheme、socket、daemon 或其他双向通道；
- 自动生成、修改或接管 `go.work`，以及 ReqWS 专用运行配置模板；
- 插件签名、Marketplace、自定义插件仓库、自动更新或远程开发支持；
- Windows、Linux、IntelliJ IDEA、Fleet 或其他 JetBrains 产品适配。

这些内容只作为范围外事项记录，不预留本次 API，不建立工作包，也不纳入验收。

## 文档分类结论

- 需求说明：创建。该变更跨 Electron、文件契约和 JetBrains Platform，需要稳定的行为边界。
- 技术方案：创建。项目模型、VFS 监听、VCS 映射、兼容性和安全策略需要独立评审。
- 测试方案：创建。真实 GoLand 项目模型和 GUI 行为无法完全由现有 Vitest 覆盖。
- 使用指南：暂不更新。能力尚未实现，避免把 draft 行为写入常青指南；实现并验收后再更新。
- 开发指南：暂不更新。插件构建命令、JDK 和调试流程需要在构建 spike 后固化。
- 交付记录：暂不创建。当前没有可发布插件资产、迁移或发布里程碑。
