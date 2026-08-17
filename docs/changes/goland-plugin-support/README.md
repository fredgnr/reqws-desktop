# GoLand 插件支持

本目录记录 ReqWS 本次 GoLand 支持的完整需求与实施方案。本次只交付跨 IDE manifest 契约、ReqWS Desktop 的 GoLand 入口和可本地安装的 GoLand 插件 v0.1；双向控制、ReqWS 管理的 `go.work`、插件发布与远程开发均不进入本次设计和实现。

- 状态：active
- 阶段：实现与验证中
- 更新日期：2026-08-17
- 实施分支：`feat/goland-plugin-support`

## 文档

| 文档 | 类型 | 状态 | 说明 |
|---|---|---|---|
| [需求说明](requirements.md) | requirements | active | 定义本次交付的用户行为、范围、边界和验收标准。 |
| [技术方案](technical-design.md) | technical-design | active | 记录 Desktop、manifest、GoLand 插件、策略 B 项目模型、VCS、构建和安全设计。 |
| [探索与实施计划](implementation-plan.md) | technical-design | active | 记录工具链、项目模型决策、剩余验证工作和各工作包退出条件。 |
| [Tool Window 界面设计](ui/README.md) | technical-design | active | 保存视觉实施基线、原型图和待后续验收的真实实现截图。 |
| [测试与验证](testing/README.md) | testing | active | 索引自动化测试、Plugin Verifier、macOS GoLand GUI smoke 和后续按次验证证据。 |

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

- 需求说明：更新。跨 IDE manifest、Desktop 启动和插件行为已成为当前有效范围，但完成定义仍要求 exact-head 验证。
- 技术方案：更新。工具链已锁定，项目模型策略 A 因所有权安全门禁失败，生产设计选择策略 B，并固化 VCS、VFS 和 Safe Mode 方案。
- 测试方案：更新。自动化、Plugin Verifier 和 GUI 验收命令已按实际 Gradle 任务与目标版本校正。
- 使用指南：更新。[GoLand 插件使用指南](../../guides/goland-plugin-guide.md)提供图文磁盘安装、打开、信任、Tool Window 区块与按钮、同步和排障步骤。
- 开发指南：更新。JDK 21、独立 Gradle 构建、根级命令、CI 隔离和证据要求已经确定。
- 交付记录：不创建。本次仍没有签名、Marketplace、独立插件发布渠道或迁移里程碑。
- 按次验证报告：已创建。[2026-08-17 GUI 验收报告](testing/verification-2026-08-17.md)记录真实日常 GoLand 结果，并因 project-dispose 异常、Project View 刷新缺口、矩阵未完成和缺少 exact commit 判定为 `NO-GO`。

## 当前验证状态

功能包处于 active 的实现与验证阶段，不表示已经完成。当前工作树的 Desktop 全量检查、182 项插件测试/结构检查、arm64 Desktop 打包与插件 ZIP 构建已通过，插件产物对 `GO-261.25134.147` 和 `GO-262.8665.270` 均由 Plugin Verifier 判定为 Compatible；当前 ZIP SHA-256 为 `8c79cafbbc507366654c679a05f24dd15a9aabb6e64b39c64b5a910447ff59c8`。新版候选已安装到日常 GoLand 2026.1.3，冷启动后达到 `Synced`；Tool Window 已按原型收口为紧凑状态徽标、摘要卡、无多余滚动条的三行仓库卡和分层操作区，并归档了[真实同步态截图](ui/tool-window-implementation-2026-08-17.png)。该图仅是后续验收输入；[既有 2026-08-17 GUI 验收](testing/verification-2026-08-17.md)仍绑定旧候选并保持 `NO-GO`，直到新的 exact-candidate GUI 矩阵重新覆盖 project close/IDE quit、Project View 刷新和全部视觉/安全场景。
