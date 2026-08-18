# GoLand 插件支持

本目录记录 ReqWS 本次 GoLand 支持的完整需求与实施方案。本次只交付跨 IDE manifest 契约、ReqWS Desktop 的 GoLand 入口和可本地安装的 GoLand 插件 v0.1；双向控制、ReqWS 管理的 `go.work`、插件发布与远程开发均不进入本次设计和实现。

- 状态：active
- 阶段：实现与验证中
- 更新日期：2026-08-18
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

## 本轮 review 修复的文档分类结论

- 需求说明：不更新。本轮只修复实现没有满足既有 ownership、用户配置保护和最终收敛要求的问题，不改变用户行为或验收语义。
- 技术方案：更新。Project Model 与 VCS ownership 改用 `.idea` 下插件自有的 verified atomic 文件，并补全冷启动恢复、VCS pending/tombstone 与 pooled writer merge-retry 设计。
- 测试方案：更新。增加原子状态失败窗口、legacy PSC 迁移、同 JVM/cold recovery 和后台 auto-detect 确定性并发回归。
- 使用指南：不更新。[GoLand 插件使用指南](../../guides/goland-plugin-guide.md)的安装、界面和用户操作没有变化。
- 开发指南：更新。补充两个 ownership 文件、迁移边界和 VCS revision/snapshot 并发约束。
- 交付记录：不创建。本轮没有发布、安装迁移、回滚或对外交付里程碑。
- 按次验证报告：已创建。[2026-08-17 GUI 验收报告](testing/verification-2026-08-17.md)记录真实日常 GoLand 结果，并因 project-dispose 异常、Project View 刷新缺口、矩阵未完成和缺少 exact commit 判定为 `NO-GO`。

## 当前验证状态

功能包处于 active 的实现与验证阶段，不表示已经完成。2026-08-18 工作树候选已用更强持久化与并发协议修复 Project Model 跨事务 ownership、手动 same-digest reconcile 和 VCS 并发覆盖问题：Project Model 与 VCS 分别使用 `.idea/reqws-managed-project-model.json`、`.idea/reqws-vcs-ownership.json` 的 verified atomic 状态；VCS 以 v2 pending add/remove、删除前 tombstone 及 mapping revision/full-snapshot quiescent merge-retry 防止 UI/pooled auto-detect 竞争被静默当作成功，但公开 API 仍没有 CAS，不能宣称平台级线性化。Node.js 24 下 Desktop 全量检查为 31 个文件/335 项测试；JDK 21 下插件 250 项测试、项目配置/结构检查与 ZIP 构建均通过，GO-261.25134.147 / GO-262.8665.270 fresh Plugin Verifier 均为 Compatible。当前 ZIP SHA-256 为 `fcd7b8b7213c091dadaf33ba601188c6fc73543481b4e14f77a70c020d8bb5b9`。这些自动化与哈希绑定当前代码候选，但尚未把该 ZIP 安装到真实 GoLand 重新执行完整 GUI 矩阵，因此不能继承前一候选的 GUI/冷启动结论。按原型收口的[真实同步态截图](ui/tool-window-implementation-2026-08-17.png)同样绑定更早候选，只作为界面参考；[既有 2026-08-17 GUI 验收](testing/verification-2026-08-17.md)继续保持 `NO-GO`，直到新的 exact-candidate 验证重新覆盖 atomic ownership recovery、manual reconcile、UI/pooled VCS 并发修改、project close/IDE quit、Project View 刷新和全部视觉/安全场景。
