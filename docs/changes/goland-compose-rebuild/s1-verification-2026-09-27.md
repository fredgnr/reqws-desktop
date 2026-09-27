---
title: Compose S1 状态与宿主验证记录（2026-09-27）
type: test-report
status: active
updated: 2026-09-27
---

# Compose S1 状态与宿主验证

本记录固定 S1 的生产展示契约、Content 生命周期接入、直接测试和最小宿主证据；只读审查复核已完成，G1 pass、S1 completed，允许进入 S2。

## 1. 源码与边界

从 `7eead9a` 开始，分支 `feat/plugin_rebuild_compose`；共享契约为 `4a5013e`，宿主接入为 `35979f8`，审查修复为 `428e396`，新节点定位测试修复为 `cccc3de`。正式 Factory 已挂载同一个生产 `ReqwsScreen` 最小入口，不再应用 S0 探针补丁。S2 将在此入口补齐界面；旧 Swing 内容和旧测试暂留，最终删除属于 S4。

最低版本影响：不变，整个 262 系列，无 `until-build` 或 `strict-until-build`。Kotlin JVM/Compose compiler 同源 2.3.20；Gradle 9.3.0、IntelliJ Gradle plugin 2.18.1；编译 GO 2026.2 / 262.8665.270；宿主 GO 2026.2.1.1 / 262.9437.286，JBR 25.0.3+9-508.16-nomod，macOS 15.7.3 / aarch64。

生产使用 `composeUI()` 与必需的 `com.intellij.modules.compose` descriptor 依赖；Starter 仅额外获得 Compose 编译 classpath。未更改领域同步、信任、所有权、VCS、用户工作区与加载配置写入边界。没有资源 catalog delta，复用现有文案；没有性能验收或性能基线。

## 2. 实现与验证

- [纯 Mapper](../../../integrations/goland/src/main/kotlin/com/reqws/goland/ui/state/ReqwsUiStateMapper.kt)保持投影证明、SAFE_MODE_BLOCKED/DEGRADED/ERROR、旧快照和 Git Root 优先级；行包含 `catalogRepositoryId`，Mapper 构建不可变列表快照，加载数量来自已确认投影与选择/目录存在性。
- [Presenter](../../../integrations/goland/src/main/kotlin/com/reqws/goland/ui/presentation/ReqwsPresenter.kt)只订阅一次，由 publisher 提供有序初始值；同步映射没有异步结果队列。关闭与注册竞争时立即关闭迟到 handle；终态禁止复活。复制反馈独立于错误，绑定复制的同一领域状态身份，重复复制重置内容级计时，状态更新清除反馈。
- [平台宿主](../../../integrations/goland/src/main/kotlin/com/reqws/goland/ui/platform/ReqwsComposeHost.kt)使用公开 `addComposeTab`，在 EDT 绑定唯一新 Content 的 disposer；内容不提供用户关闭按钮。挂载失败释放 session；Factory 幂等。打开文件 IO 在后台，最终打开在 EDT 再检查；同步沿用既有 coordinator。
- [Content session](../../../integrations/goland/src/main/kotlin/com/reqws/goland/ui/platform/ReqwsContentSession.kt)释放 UI listener 和内容 scope，不取消项目服务；可用性控制仍独立于内容创建。主题和 composition 生命周期不拥有项目同步。

| V-ID / 检查 | 命令或真实 selector | 执行结果 |
|---|---|---|
| V04 / S1.1 | `test --tests 'com.reqws.goland.ui.state.ReqwsUiStateMapperTest'` | 初始 22 用例通过；最终增加选择/目录/投影组合后为 23。 |
| V04–V06 / 集成直接回归 | `compileKotlin compileTestKotlin compileIntegrationTestKotlin test --tests 'com.reqws.goland.ui.*' --tests 'com.reqws.goland.project.TerminalStatePublisherTest'` | 最终 10 类、95 用例，0 failure/error/skipped；含 58 个原测试和 37 个新增测试。 |
| V05 | `ReqwsPresenterTest` | 9 用例：订阅期间更新/重入、1000 次通知、重复 bind、关闭竞争、终态、复制反馈及销毁后无写入。 |
| V06 / 平台 | `ReqwsActionDispatcherTest` / `ReqwsContentSessionTest` | 3 + 2 用例；新鲜状态动作校验、一致复制、重复同步委派、真实 Content disposer 和独立项目 job。 |
| V06 / 最小宿主 | `ComposeContentLifecycleTest.contentLifecycleIsIndependentOfProjectSynchronization()` | 最终 1 用例，0 failure/error/skipped，1 进程正常退出；详见下节。 |
| 依赖/产物 | `exportPluginArchivePath`、禁用符号检查、宿主入口 `check_compose_artifact` | pass；57 个生产源文件/329 个自身 class；候选 ZIP 无重复宿主库或测试控制类。 |

95 用例构成为旧 Mapper 20、旧 Panel 23、旧 dispatcher 4、availability 4、Factory 2、publisher 5、新 Mapper 23、Presenter 9、动作 3、Content 平台 2。中间检查未替代最终集成的 `check:goland` 与 S2 组件验证。

## 3. 真实宿主与失败保留

[本机入口](../../../scripts/run_compose_content.py)显式消费 `build/release/plugin-archive.txt` 中的 0.1.7 ZIP，不重建生产插件；只使用专用 profile 与临时普通文本 Git fixture，不触及日常 IDE 或用户项目。自动流程不发送键鼠输入；最终一轮用户明确留出桌面可见时段。IDE 错误检查保持开启；确切 ZIP 摘要只存本地报告。

| 私有运行目录后缀 | 结果 |
|---|---|
| `9shzdih8` | `35979f8` 候选通过，1 用例，进程 `started → passed → exited`。 |
| `c8gt7zi9` | `428e396` 候选失败于重建后的旧 Driver 节点定位；1 failure，进程正常退出。层级输出已存在新 `reqws.status`，不能以截图或这个旧运行记 pass。 |
| `q1hflzye` | 同 `428e396` 生产 ZIP，加 `cccc3de` 测试，重新按 tag 查询新节点且专用窗口可见后通过；1 用例/0 skipped，进程 `started → passed → exited`。不单凭前台截图归因。 |

最终真实覆盖：未打开面板时投影和加载选择刷新；3 轮隐藏/显示保持 owner 未销毁；重复 Factory 调用仍只有一个 Content；删除内容立即使旧 owner disposed；内容不存在时项目继续同步；重建后新 owner 和 Compose 语义节点可用；fixture 原文件保留；项目关闭并正常退出。这是 G1 最小宿主范围，不宣称完整动态卸载、系统键鼠/VoiceOver 或 S4 的 20 轮最终资源验收。

## 4. 独立审查与交接

只读 Reviewer `/root/s1_review` 使用 `astra` profile，继承实际 `gpt-6-astra` / `xhigh`；Main 从本会话可信 `turn_context` 核验为 verified，配置摘要在忽略目录 `build/reports/compose-s1/review-model.json`。Reviewer 未修改文件、执行测试或启动 IDE。

固定 `35979f8` 审查发现 P2：新增宿主类会被旧 `integrationTest` 一并发现，破坏原三组/九进程报告。`428e396` 为旧任务固定 `WorkspaceIntegrationTest` selector，专用任务固定自己的类；Reviewer 已确认该项关闭，95 个测试证据有效，未发现新增生产阻塞。Reviewer 对最终 `cccc3de` 测试修复与 `q1hflzye` 同候选运行完成窄复核，确认未发现新增阻塞，S1 限定范围证据成立。Main 据此判定 **G1 pass、S1 completed**，交接 `4a5013e` 契约和 `cccc3de` 集成源码给 S2。

本地日志保存在忽略目录 `integrations/goland/build/reports/compose-s1/`，JUnit 位于 `build/test-results/test/`；宿主原始报告在 `reqws-compose-s1-host-*` 临时目录。文件今后不可用时应重新运行，不能由文档推断仍有原始证据。
