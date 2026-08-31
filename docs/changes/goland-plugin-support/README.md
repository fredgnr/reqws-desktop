# GoLand 插件支持

本目录记录 ReqWS 本次 GoLand 支持的完整需求与实施方案。本次只交付跨 IDE manifest 契约、ReqWS Desktop 的 GoLand 入口和可本地安装的 GoLand 插件 v0.1；双向控制、ReqWS 管理的 `go.work`、插件发布与远程开发均不进入本次设计和实现。

- 状态：active
- 阶段：实现与验证中
- 更新日期：2026-08-31
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
4. 插件以 manifest 为唯一目标状态自动同步活动仓库的项目内容，只读诊断 Git Root，并由用户在 GoLand Directory Mappings 中完成 VCS 配置；
5. 通过真实 macOS GoLand 验证新增仓库、逻辑移除但保留磁盘目录、手动 Git Root 配置、重新添加、错误恢复和重启恢复。

本次明确不设计或实现：

- 从 GoLand 发起仓库增删、分支或其他 ReqWS 业务操作；
- GoLand 与 Desktop 之间的 URL scheme、socket、daemon 或其他双向通道；
- 自动生成、修改或接管 `go.work`，以及 ReqWS 专用运行配置模板；
- 插件签名、Marketplace、自定义插件仓库、自动更新或远程开发支持；
- Windows、Linux、IntelliJ IDEA、Fleet 或其他 JetBrains 产品适配。

这些内容只作为范围外事项记录，不预留本次 API，不建立工作包，也不纳入验收。

## 本轮产品取舍的文档分类结论

- 需求说明：更新。GL-04、GL-06、GL-07 与 GL-13 改为“项目模型自动同步、VCS 只读诊断、用户手动维护 Directory Mappings”，不再承诺插件自动增删 Git Root。
- 技术方案：更新。删除 VCS mapping writer、ownership、迁移与 whole-list 并发协议；配置监听只触发只读复核，`Sync Now` 也只重查 VCS。
- 测试方案：更新。以生产无 VCS mutation API、纯 inspection 的 `.idea/vcs.xml` 保持、手动配置后的事件复核和真实 GUI 平台行为归因作为新验收证据。
- 使用指南：更新。[GoLand 插件使用指南](../../guides/goland-plugin-guide.md)增加 Directory Mappings 手动配置、日常变更和排障步骤。
- 开发指南：更新。明确 VCS API 只读边界，以及旧 `.idea/reqws-vcs-ownership.json`/lock 为 inert 且不得自动迁移或清理。
- 交付记录：不创建。本轮没有发布、安装迁移、回滚或对外交付里程碑。
- 按次验证报告：已更新。[2026-08-30 GUI 验收报告](testing/verification-2026-08-30.md)绑定当日历史 exact HEAD、ZIP 和 packaged Desktop，记录 automatic refresh 与 reactivated repository live ProjectFileIndex 阻塞并判定 `NO-GO`；同 JVM recovery claims 保留符合设计，不是失败原因；[2026-08-31 待修复与未完成验证清单](testing/open-bugs-and-verification-work-2026-08-31.md)绑定当前本地 exact implementation commit 与剩余矩阵；[2026-08-17 报告](testing/verification-2026-08-17.md)已归档。

## 2026-08-30 并发与取消修复的文档分类结论

- 需求说明：更新。补充 stable `.idea` directory-inode writer lock、latest read cancellation 状态恢复和单次 apply cancellation 后同 service 可重试的验收语义。
- 技术方案与实施计划：更新。记录 native directory `flock`、非业务 cancellation event、稳定状态回滚和 newer-state gate；lock 子文件不再是互斥权威。
- 测试方案：更新。增加 lock 子文件替换后的第二 repository 同代写入、公开 `Sync Now` 恢复 read cancellation，以及首次 apply PCE 后下一 refresh 成功的确定性回归。
- 开发指南：更新。维护上述持久化和 lifecycle 不变量，避免后续实现重新引入 replaceable child lock 或一次性 coordinator。
- 用户指南：仅更新当前自动化验证摘要。Tool Window 操作与错误恢复步骤没有变化，本轮修复既有 `Sync Now` 的可达性。
- 交付记录：不创建。本次修复没有发布、安装迁移或回滚；它本身只更新自动化证据，当天后续真实 GUI 执行另行形成[2026-08-30 按次报告](testing/verification-2026-08-30.md)。

## 2026-08-30 首次取消恢复修复的文档分类结论

- 需求说明：更新。首次 valid read/apply 的一次 PCE 或 coroutine cancellation 必须从单次 startup trigger 自动恢复，不依赖隐藏 Tool Window、manifest 改写或新 VFS event。
- 技术方案与实施计划：更新。记录 completion-cleanup 后的一次性 service-scope retry、source generation/exact state-version gate、固定延迟与 dispose/owner cancellation 边界。
- 测试方案：更新。增加首次 read/apply × PCE/CE 四项 production startup 入口回归，以及 retry 上限和 dispose 负向用例。
- 开发指南与用户指南：更新。维护有界、cancellation-neutral、无热循环的首次启动恢复不变量，并说明单次瞬时取消会自动重试。
- 交付记录：不创建。本次修复没有发布、安装迁移或回滚；它本身只更新自动化和产物证据，当天后续真实 GUI 执行另行形成[2026-08-30 按次报告](testing/verification-2026-08-30.md)。

## 当前验证状态

功能包处于 active 的实现与验证阶段，不表示已经完成。Project Model 继续通过 `.idea/reqws-managed-project-model.json` verified atomic ownership 自动同步；VCS Directory Mappings 完全归用户与 GoLand 所有，插件生产路径只读取当前 mappings、展示缺失/冲突/保留仓库候选提示，并在配置事件或 `Sync Now` 后重新检查。插件不得调用 mapping mutation API，不写 `.idea/vcs.xml`；未发布开发候选可能留下的 `.idea/reqws-vcs-ownership.json` 与 lock 只作为 inert 文件保留，不读取、不迁移、也不自动清理。

当前源码已固定为本地 implementation commit `26fb3c6517b1fcb46b2e82ed9336e24b1d2a8945`。JDK 21 完整门禁通过 35 个 XML suites、345 项插件测试（0 skipped/failure/error）、production source/composed-JAR forbidden-symbol gate、`verifyPluginProjectConfiguration`、`verifyPluginStructure`，以及 GO-261.25134.147 / GO-262.8665.270 Plugin Verifier（均 `Compatible`）。exact ZIP SHA-256 为 `b8fec9f55ff15f98532dc898d044dfbbb253024fff5081e7f167651a435b35df`，ZIP 内 JAR SHA-256 为 `17f20c0dce78f933352eb1b9c219839c2cdd2763973c5fd1f262ed51731072f2`。Desktop `npm run check` 同时通过 31 个测试文件、335 项测试。代码复核未发现剩余 P0/P1；现有 tracked 截图/文档隐私也已收口。

该 implementation commit 尚未推送到配置的 GitHub `origin`，当前 exact ZIP 也尚未重新安装并执行完整 §8 GUI。2026-08-30 历史 GUI 已证明旧候选的安装、初始双仓库投影、手动 Directory Mappings、VCS 只读边界和 repo-a 主要 Go/Git smoke，同时暴露 automatic watcher、live `ProjectFileIndex`、Go registry/PACKAGE 与 Tool Window truthfulness 缺陷；这些缺陷已有当前实现和自动化覆盖，但不能继承为 GUI `PASS`。恢复、规模、视觉/无障碍、用户配置保护和 close/exit 矩阵仍未完成，因此当前 verdict 继续为 `NO-GO`。[复审通过的浅色同步态截图](ui/tool-window-implementation-2026-08-30.png)只作为历史候选界面证据，不改变 verdict。
