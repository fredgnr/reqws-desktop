---
title: IDE 兼容与自动化开发记录
type: delivery
status: active
updated: 2026-09-21
---

# IDE 兼容与自动化开发记录

本记录说明 S1–S3 的开发落点和后续执行入口；用户要求只开发、不测试或回归，因此不提供本候选的兼容通过或 GUI GO 结论。

同日后续调整将完整 GoLand 进程/UI 集成限定在本机，保留自动用例与全部 CI 平台/API 覆盖；以下为调整后的当前开发状态，未新增运行或授权证据。

后续用户授权的测试、修复及推送前验证另见[验证记录](verification-2026-09-21.md)；以下保留当时仅开发的范围说明。

## 1. 开发范围

- [统一策略](../../../integrations/goland/compatibility.properties)固定最低系列 262、编译 GO 2026.2、固定集成 GO 2026.2.1.1，以及 Verifier 1.410、Starter/Driver 262.9437.185。生产 Java/JVM 25、Kotlin 2.3.20 和 Gradle Plugin 2.18.1 保持不变；测试依赖位于独立 source set，不打入生产 ZIP。
- [Gradle](../../../integrations/goland/build.gradle.kts)生成只有 `since-build="262"` 的 descriptor，检查源/patch XML；本地编译 SDK override 结构化读取 ProductInfo，拒绝其他产品、发行版和运行时要求。最终 ZIP 校验继续核对身份、版本、描述、icon、完整性和唯一 descriptor，新增下限/双上限/产品依赖检查。
- [目标解析器](../../../scripts/ide_compatibility.py)每次从官方正式发行目录冻结目录、选择理由和目标；PR 采样支持最低版、固定代表版、各版本线首/末正式版和显式回归版本。首次迁移尚未验收，CI 当前保守使用完整稳定集合；验收后才可把 PR 的 `--mode full` 改为 `--mode pr`。发布和每周扫描一直使用完整集合。
- [API 执行与汇总](../../../scripts/run_ide_verifier.py)使用同一 ZIP，对照下载 SDK 的 ProductInfo 验证实际 build，保留每目标 verdict/日志，区分 API 不兼容与基础设施阻塞；缺报告、未知终态、非零退出、候选或快照不一致均不能通过。矩阵最多两个重任务并发，容量超限明确阻塞而不截断。
- 新门禁未真实验证前，构建阶段仍保留原 `verifyPlugin` 调用，覆盖最低 SDK 和原固定代表版本；它不代替下游完整矩阵，Release 签名前检查也不算最终签名 ZIP 的证据。新门禁验收后再移除对应重复调用，不在本次未测试开发中提前撤门禁。
- [完整 IDE 场景](../../../integrations/goland/src/integrationTest/kotlin/com/reqws/goland/WorkspaceIntegrationTest.kt)仅在本机执行，仍使用独立临时目录、普通文本 Git 仓库、真实 Driver Project 树、既有服务只读 getter 和原子选择写入；不增加生产远程端点或直接 refresh。三组场景要求九个不同 PID 正常退出，其中冷启动组重复两次。`HeavyPlatformTestCase` 和其他 Light/Heavy、所有权、安全、并发、恢复测试仍在原 CI `test` 中。
- Verifier 与完整进程任务显式不复用 Gradle configuration cache，以免冻结目标/临时进程目录跨运行复用；普通编译与依赖缓存策略保留。Starter 的 Kotlin 2.4 stdlib 只进入独立测试宿主，不改变生产 Kotlin 2.3.20 配置。
- [CI](../../../.github/workflows/ci.yml)保留两个 required check 名称，编译、L0/L1、禁用 API、结构/策略及最多两并发 API 检查仍在 CI。影响分类仅输出本机集成建议，不调度 GUI。PR/Release/定期及共享工作流删除完整 IDE job 与 License Server 配置；汇总写明 `scope=ci-api`、本机 `not-run`。发布仍对最终签名 ZIP 运行完整 API 集合并绑定公开字节，每周分别扫描 main 与已发布 ZIP。
- [本机入口](../../../scripts/run_local_ide.py)提供专用授权准备、显式 ZIP 自动集成和只读报告核对。授权配置直接在专用 profile 中复用，不复制个人凭据；独占锁、防 symlink、每轮项目记录隔离及全新业务目录保护状态。CI 入口硬性拒绝启动完整 IDE。本机报告单独绑定 ZIP/插件版本、实际代表 IDE、XML/进程结果和环境阻塞原因。

## 2. 最低版本评估

- 基线：262，保持；GoLand 产品限制保持。
- 官方发行目录只读核对确认最早 262 正式版为 GO 2026.2 / `262.8665.270`。另读取已有 Gradle SDK 的 `product-info.json`，产品、发行版、build 与目录一致，`minRequiredJavaVersion=25`。未下载/安装新的用户 IDE，未启动 IDE。
- 生产未新增平台 API、语言依赖或运行时要求。测试宿主使用固定 Starter/Driver，测试框架自己的依赖不作为抬高生产基线的理由。
- 最低 SDK 编译、平台测试、API 矩阵和固定环境集成均未执行；不能由元数据核对推导最低系列已经可用。
- 来源：[JetBrains 正式发行目录](https://data.services.jetbrains.com/products/releases?code=GO&type=release)、[固定 Gradle Plugin 2.18.1 Verifier 接口](https://github.com/JetBrains/intellij-platform-gradle-plugin/blob/2.18.1/src/main/kotlin/org/jetbrains/intellij/platform/gradle/tasks/VerifyPluginTask.kt)。本轮只核对接口/已有工具文件，不执行验证任务。

## 3. 手工 case 迁移映射

以下是一次性迁移说明，所有替代项仍待真实运行和失败检测证明；本次不删除尚未验证的旧覆盖，也不增加最高版本 GUI 门禁。

| 旧 case / 契约 | CI 保留 L0/L1 选择器 | 仅本机 L2/L3 选择器 | 当前处置 |
|---|---|---|---|
| G1：入口/仓库树和 metadata 隐藏 | `ShellPresentationTest.testRegisteredExclusionAndTreeFilterRevokeWithoutModelChanges` | `WorkspaceIntegrationTest.loadingAndProjectTree` | 自动替代待验证；真实 Desktop 保存/打开不由宿主夹具冒充。 |
| G2：2→1→0→2 自动刷新 | `ManagedRootsAdapterTest.testTwoOneZeroTwoPreservesUserRootInManagedModule`；`ManifestVfsWatcherTest` | `WorkspaceIntegrationTest.atomicSelectionAutomaticallyRefreshes` | 只原子写协议文件；等待本次 revision 和 live digest，再看树与数量。 |
| G3：Excluded Files 开关/运行中新文件 | `ShellPresentationTest` | 第一组覆盖树和入口隐藏，未替代开关交互的全部覆盖 | 保留未替代部分，不从基础树断言推出开关通过。 |
| G4：用户 root/空集合/冷进程 | `ManagedRootsAdapterTest.testPlatformSaveAndReloadPreservesMarkersAndExtraRoot` 及取消/ownership 系列 | `WorkspaceIntegrationTest.emptyAndNonemptySurviveColdProcesses` | 完整新 PID，保留同一 fixture/config；两次重复执行要求尚未取证。 |
| G5：用户 root 覆盖未选仓库 | `ManagedRootsAdapterTest.testBorrowedRootIsNeverClaimedOrRemoved`、`testSameDigestAutomaticRefreshKeepsVerifiedUserRootCoverage` | 不机械增加 GUI 组合 | 保留模型与用户配置保护；原手工退出条件仍适用。 |
| G6：错误/无绑定/普通项目 | `ReqwsProjectServiceTest.testOrdinaryProjectNeverRegistersForOrChurnsOnVcsEvents` 及 reader/ownership 错误回归 | 第一组包含独立普通项目 | 错误排列保留在原层，普通项目自动场景待验证。 |
| Safe Mode、dispose、原子持久化、并发 | `ReqwsProjectServiceTest`、`VerifiedAtomicStateFileTest`、`LatestWinsSyncCoordinatorTest` | 不扩展通用 GUI 矩阵 | 原有覆盖完整保留；没有全局 trust bypass。 |

## 4. 可执行入口与环境

```bash
# 基线保留测试、单次候选构建、完整正式目标 API 扫描
npm run check:goland

# 仅本机：在专用无项目环境交互准备 JetBrains Account 授权
npm run prepare:goland:authorization -- --profile "$HOME/.reqws-ide-tests/goland-2026.2.1.1"

# 仅本机：消费已有候选，不重新构建生产插件
npm run check:goland:integration -- \
  --profile "$HOME/.reqws-ide-tests/goland-2026.2.1.1" \
  --archive /absolute/path/to/candidate.zip --version 0.1.5

# 仅解析代表目标，保存冻结清单
python3 scripts/ide_compatibility.py resolve --mode pr --output /tmp/reqws-targets.json
```

完整 IDE 只在本机运行，要求图形会话、Driver 权限和有效授权。`JETBRAINS_LICENSE_SERVER` 可选；也支持用户在专用测试环境中通过 JetBrains Account 登录，不假定日常 GoLand 登录会被继承。CI 不要求授权变量或凭据；本次没有配置、登录或启动任何环境。详见[本机入口](local-integration.md)及 [GoLand 注册说明](https://www.jetbrains.com/help/go/register.html)。

每轮创建 `reqws-local-ide-*` 私有目录，业务 fixture/项目/system/plugins/logs/XML 隔离，只有专用授权 config 原地保留。旧 workspace/recent/trust 记录移到本轮私有隔离区，授权文件不复制或清理。报告路径由命令打印；CI 不收集本机 profile。IDE 异常、退出码、watchdog、强制清理、缺 XML、全跳过及场景缺失不能通过，准备窗口退出也不等于授权或 UI 通过。

每周扫描由 GitHub Actions 的 `goland-weekly.yml` schedule/manual 入口执行，不是用户机或 Codex 定时任务。main 与已发布 ZIP 各自保留目标结果与汇总 artifact，保存 14 天。Release 原签名、Secrets、tag、draft、checksum 和 Marketplace 后置提交边界保留。

## 5. 本轮验证状态与后续门槛

按本次明确要求，未运行编译、单元/平台测试、工作流回归、Verifier、Starter、GUI、打包或 `npm run docs:check`。开发过程中只读取资料、代码和差异并做静态语法核对；未合并、发布、安装或重启用户应用。

C1–C8/W1–W8 的解析、产物、影响分类、失败汇总、CI 不启动 GUI 和证据范围反例已写入工作流测试；新增 `test_local_ide_launcher.py` 覆盖可选授权、profile 保护/锁/项目状态隔离与签名前后字节不借证据。均未执行。I1–I8 的真实进程故障、监听失效、旧状态/错误树、授权复用与本机图形/权限探针仍需在 V 中取证，不能把断言代码当作演练通过。

只有最低版本编译/保留测试、完整正式 API 集合、三组固定环境场景、负向证明、CI 与文档检查完成后，才能宣称整个兼容改造已验收并移除相应重复手工门禁。本次交接范围是开发实现。
