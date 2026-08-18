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
- 按次验证报告：已创建。[2026-08-17 GUI 验收报告](testing/verification-2026-08-17.md)记录真实日常 GoLand 结果，并因 project-dispose 异常、Project View 刷新缺口、矩阵未完成和缺少 exact commit 判定为 `NO-GO`。

## 当前验证状态

功能包处于 active 的实现与验证阶段，不表示已经完成。2026-08-18 已确定新的产品边界：Project Model 继续通过 `.idea/reqws-managed-project-model.json` verified atomic ownership 自动同步；VCS Directory Mappings 完全归用户与 GoLand 所有，插件生产路径只读取当前 mappings、展示缺失/冲突/保留仓库候选提示，并在配置事件或 `Sync Now` 后重新检查。插件不得调用 mapping mutation API，不写 `.idea/vcs.xml`；未发布开发候选可能留下的 `.idea/reqws-vcs-ownership.json` 与 lock 只作为 inert 文件保留，不读取、不迁移、也不自动清理。这一取舍从产品路径移除了 whole-list writer 覆盖用户配置的风险。当前源码候选已在 JDK 21 下通过 220 项插件测试、`verifyPluginProjectConfiguration`、`verifyPluginStructure`、GO-261.25134.147 / GO-262.8665.270 Plugin Verifier（均 `Compatible`）和 ZIP 构建；ZIP SHA-256 为 `d4ee9ee6352cf8a8c0ee3ca7e198fb37357f967ca881644dcd0c1790136b7652`，大小 394,894 bytes。Desktop `npm run check` 同时通过 31 个测试文件、335 项测试。真实 GoLand 的手动 Directory Mappings、平台原生 auto-detection 归因、Project/Search/Go 功能、reopen、规模与 GUI 状态矩阵仍待绑定推送后 exact commit 验收，不能据自动化结果给出 `GO`。[真实同步态截图](ui/tool-window-implementation-2026-08-17.png)仍只作为旧候选的界面参考，[既有 2026-08-17 GUI 验收](testing/verification-2026-08-17.md)正文保持历史 `NO-GO` 证据。
