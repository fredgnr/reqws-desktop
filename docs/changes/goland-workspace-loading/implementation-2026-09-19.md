---
title: GoLand 工作加载集合实施与验证记录
type: test-report
status: active
updated: 2026-09-20
---

# GoLand 工作加载集合实施与验证记录

S0–S4 开发、自动化集成、独立源码复核和最终候选 G1–G7 隔离 GUI 验收均已完成，产品验收结论为 **GO**。GUI 包含用户手动协助；结论适用于当时的未提交工作树及已确认的 356-test 候选，不表示已经发布。

本报告保留此次验收结束时的候选范围。用户随后于 2026-09-20 选择 Product Design 方案一调整 Desktop 详情展示；该后续 renderer 改版的验证见[设计验证](../../../design-qa.md)，不把这里的原生 GUI 快照当作改版后的新验收。插件源码与工件未因该展示改版变化。

## 0.1.4 提交前验证补充（2026-09-20）

用户要求将 Desktop 和 GoLand 插件统一为 0.1.4 并提交到远端。本次同步 `package.json`、lockfile 根版本、Gradle 本地默认版本、插件诊断版本及对应测试；CI 的 `releaseVersion` 参数机制保留。下述检查针对包含详情展示改版和版本更新的提交候选：

- `npm run check`：TypeScript、ESLint、i18n（337 keys）、文档检查及 42 文件 / 445 项测试全部通过，零跳过；临时钥匙串测试在所需权限下随全量检查运行。
- `python3 -m unittest discover -s tests/workflows -p 'test_*.py' -v`：28 项通过。
- 使用下文同一精确 SDK 和 offline Verifier 参数运行 `npm run check:goland`：37 类 / 356 项测试通过，0 failed、0 errors、0 skipped；禁用符号、配置、结构与兼容性检查通过，0.1.4 在 GO-262.9437.286 上为 **Compatible**。
- 已生成 `integrations/goland/build/distributions/reqws-goland-0.1.4.zip`，核验 ZIP/JAR 完整性、唯一 descriptor、plugin ID、内嵌版本及精确 IDE 范围。生成工件不纳入源码提交。

此次没有安装 0.1.4 插件或重跑原生 GUI；下文 G1–G7 仍属于原 0.1.0 开发候选。版本更新和代码推送不等于创建 tag 或发布 Release。

## 基线与候选

源码起点为 `589b26a`，需求包来自本地跟踪分支 `origin/docs/goland-workspace-loading` 的 `5f12b33`。候选为验收时工作树相对起点的实际 Git diff，包含本包和新增源文件；该阶段尚未提交。保留已合入的自更新与活动门禁，没有切换分支或修改真实 workspace。后续获用户授权后启动隔离测试 IDE，并由用户手动安装候选，过程见下。生成目录、测试 sandbox 和 ZIP 不纳入源码变更。

目标为 GoLand 2026.2.1.1 / **GO-262.9437.286**，使用本机该 build 的 SDK 和其 JBR 25.0.3。编译、descriptor 两端和 Verifier 均为这个唯一目标；构建 Java/JVM 已更新为 25，CI 的工具链设置同步更新，现有检查和发布流程保留。

## S0：协议与公开 API 决策

`src/shared/goland-workspace.ts` 与 `loading/contract/GoLandProject.kt` 共用 `contracts/goland-project.json`。选择 ID 只参与 manifest 求交，顺序遵循 manifest；显式空数组不回填全部；已有失效 ID 读取时保留，新保存只接受当前成员。保存携带 bindingId/revision，Main 不接收 renderer 路径。read、首次 prepare 和已有 save 分别通过 typed IPC 暴露；复用 Main singleton、`WorkspaceMutationCoordinator` 与 `ApplicationActivityGate`。

实际 SDK 的 javap、生产编译、平台测试及最终 Plugin Verifier 给出以下决策：

| 接口 | 实际用途和验证 |
|---|---|
| `WorkspaceModel.update` | 公开 suspend 增量更新；没有整图替换和语言服务门禁。 |
| `LegacyBridgeJpsEntitySourceFactory.createEntitySourceForModule` | 标准 module source；真实 `.iml` 保存、项目重开和 marker 恢复通过平台回归。 |
| `ModuleTypeManager.getInstance().getDefaultModuleType().getId()` | 使用目标平台实际注册的默认模块类型；真实 GoLand 为 WEB_MODULE，不引用具体语言实现或 builder。 |
| `Project.scheduleSave()` | 在首次有效 startup 的有界等待中请求一次正常 IDE 保存；后续仍验证 metadata 和模型，不把排队当落盘。 |
| `ModuleUtilCore.getModuleDirPath` / `isModuleFile` | 核对精确模块目录与文件；不使用 Internal 的 `Module.moduleFilePath`。 |
| `DirectoryIndexExcludePolicy.getExcludeUrlsForProject` | getter 只读不可变 capability；不调用 Internal EP 辅助成员。 |
| `ProjectRootManagerEx.makeRootsChange(Runnable, RootsChangeRescanningInfo)` | 包裹真实 capability 变化、以 `TOTAL_RESCAN` 使排除策略重读；无假实体差异。 |
| `TreeStructureProvider.modify` / `ProjectView.refresh` | 公开树过滤与刷新；保留包含仓库的容器，回调不做磁盘 IO。 |
| `MutableEntityStorage` 与公开 entity pointers | 预演删除并核对消失的实体；未知子关系阻止删除，不引用 Internal order entity。 |
| `AdditionalLibraryRootsListener` | Experimental，未使用，未放宽禁令。 |

`-PreqwsGoLandSdkPath=/Users/fred/Applications/GoLand.app` 允许开发检查复用本机精确 SDK，并拒绝其他 build；路径不进入产品运行时。没有引入旧平台桥接或降级分支。

## S1：Desktop 控制面

实现独立入口服务、read/prepare/save IPC、preload 与详情页加载集合。所有 GoLand 启动动作准备并打开 `.reqws/ide/goland`；原 workspace-root fallback 已删除。首次以 exclusive mkdir 创建 shell，绑定作为最后发布标记；未知、foreign 或中断留下的 shell 保留并报错，不接管 `.idea`。

当前 manifest、目录身份、绑定和 revision 在协调器内检查；发布前再次核对，使用有上限的严格 UTF-8 读取和同目录原子写入。UI 支持 all、selected、显式空集合、取消、重载、冲突与写入失败；“已保存”不冒充插件同步成功。VS Code/Cursor 配置和需求成员不因选择变化被重写。

21 条 Desktop 文案和 6 条 Kotlin properties 文案由只读翻译子代理审校，继承主 agent 模型且 reasoning 为 high；owner 核对原文、key 顺序/集合、占位符、复数及术语后写回。`i18n:apply` 和最终 `i18n:check` 通过。

## S2–S3：模型、恢复与 shell 展示

受管 module 使用 binding UUID 稳定名称和 `.idea/reqws` 精确文件位置，只修改有 ledger、唯一 root、nonce marker、模块文件与目录身份共同证明的单根。保留原生 shell、用户额外 root 和借用 root；用户 source/exclude/未知子配置阻止级联删除。显式空集合保留承载 module。

新 `reqws-loaded-roots.json` 使用原有 verified storage 的目录句柄、原子替换和跨 JVM inode 锁。PREPARED intent 先于模型修改；取消或错误保留恢复证据，不做整图回滚。事务在持久化意图后、模型提交内、PFI 等待中和结果写回前复核 `.idea` 与 module 目录身份；已保存模块的磁盘文件缺失不能被旧 VFS 缓存冒充。连续 live 同步也保留删除 intent：`.iml` 可能尚未保存，模型暂时不存在不足以清除证据。回归真实保存早期 `.iml`、连续清空后恢复该文件再重开项目，证明旧根会按精确 marker 完成删除。证据不足时 fail closed，不重新认领未知条目。

shell 采用精确 URL exclusion 加普通 Project 树过滤两层实现，只有有效绑定且受信任时发布缓存；错误、trust 撤销与 dispose 撤销能力。平台测试验证模型无差异时启用/撤销/恢复、运行中新文件、真实注册扩展、同名用户目录和容器保留。平台断言不替代真实 GUI 的 Excluded Files 开关观察。

## S4：自动同步与旧链清理

读取器验证固定目录关系和 binding A / manifest / binding B / manifest 一致快照，两条精确 watcher 含直接父目录监听。generation、取消、trust、dispose 和最终发布门禁复核同一候选；同 manifest 的选择变化会自动应用。读取错误保留上次有效模型，恢复相同原始内容也强制全链重核，不能跳过 apply 后直接报 Synced。

状态区分已加载、未加载、缺失和用户 root 覆盖。VCS 仅只读核对 loaded 集合；额外 mapping 是用户配置观察，不写 Directory Mappings，不扫描保留仓库磁盘。

| 删除/替换范围 | 当前安全回归承接 |
|---|---|
| `ReqwsProjectModelAdapter`、`ReqwsExcludePlanner`、`ReqwsLiveProjectionVerifier` | `ManagedRootsAdapterTest`、`ShellPolicyApiTest`、`ShellPresentationTest`。 |
| `ReqwsManagedModelState` / `ReqwsManagedModelFileState` 与专用测试 | `LoadedRootsJournalTest`、`ManagedRootsAdapterTest`；通用 verified persistence 回归保留。 |
| 原 workspace-root resolver、旧入口和 active 状态文案 | Desktop service/launcher/IPC/preload/UI 测试、detector/Tool Window 测试。 |
| 旧 ledger reader、迁移和版本矩阵 | 生产符号检查及只读引用审查；旧 ledger 仅在 inert 负例出现。 |
| 既有安全并发基础 | manifest、watcher、trust/cancel/dispose、代际和目录原子存储测试继续运行。 |

没有运行时旧模式、回退 adapter、迁移 reader、Go toolchain 或 VCS 写入路径。历史报告的原结论保留，没有被当作本候选证据。

## 完成口径复查补充

技术方案第 6 节的逐项复查补齐 7 个直接平台回归：用户 SourceRoot、用户 exclude pattern、重复 marker、仓库目录替换、已保存模块文件移走、PREPARED 后取消以及 PREPARED 后 `.idea` 替换。前六类保护用户配置和恢复证据，目录替换用例还明确断言不修改当前模型、不在替换目录创建 module 目录/ledger。

首次执行揭示两个真实问题：`.idea` 被替换后模型仍可能先删除 roots，及已移走的 `.iml` 仍可能由旧 VFS 缓存通过校验。实现已补充事务目录身份门禁，并以磁盘存在性决定模块文件是否可用；只允许本 project 实例刚创建且从未观察到落盘的模块使用临时内存证据。最终结果以下述修复后检查为准；上一轮 338 tests/ZIP 不代表修复后的候选。

## 独立复核

用户明确授权后，由独立只读 Reviewer 检查整合 diff、未跟踪新文件和现有报告，未写文件或操作 GUI。发现并关闭一项 P2：同摘要自动刷新走 NoOp 时，新读出的 state 丢失已验证 userRootCoverage，可能把用户根覆盖提示改成普通未加载。NoOp 现按当前 event digest 从 LoadedProjectionService 读取覆盖结果，与 Applied 分支一致。

`ManagedRootsAdapterTest.testSameDigestAutomaticRefreshKeepsVerifiedUserRootCoverage` 使用真实模型与 projection service，连续两次自动刷新，断言仅应用一次且 coverage/用户 root 均保留。直接回归通过；Reviewer 复核修复与测试后确认关闭。该轮全量为 346 tests；随后首次启动修复候选为 353 tests，G5 布局修复后的最终候选为 356 tests。

GUI 修复的独立复核另发现并关闭一项 P2：首次等待期间若观察到 `.idea`，之后目录消失，下一轮不能再请求保存来重建它。实现增加独立的 `initialProjectMetadataObserved` 标记；一旦观察到任意 `.idea` 条目便永久阻止首次保存请求。跨轮回归断言没有保存请求且替换目录保持原样。Reviewer 复核修复和 20 项 metadata 回归后确认关闭，无其他明确阻塞发现。

## 实际验证

### Desktop 与工作流

- `npm run check`：TypeScript、ESLint、i18n、文档检查和 Vitest 全部通过；42 个测试文件，443 tests。
- `python3 -m unittest discover -s tests/workflows -p 'test_*.py' -v`：28 tests，通过。
- macOS 证书相关既有测试仅使用临时证书/临时 keychain，测试结束恢复原搜索列表；没有触碰生产签名凭据。

### 插件

最终命令使用真实精确 SDK：

```bash
JAVA_HOME=/Users/fred/Applications/GoLand.app/Contents/jbr/Contents/Home \
ORG_GRADLE_PROJECT_reqwsGoLandSdkPath=/Users/fred/Applications/GoLand.app \
ORG_GRADLE_PROJECT_reqwsVerifierOffline=true npm run check:goland
```

最终结果：37 个测试类、356 tests、0 failed、0 errors、0 skipped；生产禁用符号、configuration、structure、ZIP 构建和单目标 Plugin Verifier 全部通过，兼容性结论为 **Compatible**。生产检查扫描 47 个源文件与 302 个 class。直接回归选择器包括 `com.reqws.goland.loading.ManagedRootsAdapterTest`（新增安全与 no-op 回归后 17 tests、0 failed、0 skipped）、`com.reqws.goland.loading.*` 和 `com.reqws.goland.project.ReqwsProjectServiceTest`；G5 布局修复直接运行 PanelTest 23、ViewModelTest 20、FactoryTest 2 项，共 45 tests，通过，不是零执行筛选。

Marketplace TLS 在本机在线检查失败，因此本轮以 Verifier 原生 offline 模式使用完整目标 SDK，保留 API/依赖兼容性检查；CI 默认仍在线。SDK 安装布局对部分可选模块输出缺失 classpath 提示；仅以最终 Verifier verdict 作为兼容性结果。配置检查建议未来版本插件不限制 until-build，本需求明确单 build，因此保留 exact bounds。

最终候选 ZIP：`integrations/goland/build/distributions/reqws-goland-0.1.0.zip`，plugin ID `com.reqws.workspace`。这是开发构建版本；没有创建 tag、Release、PR 或推送。

## 文档与验收结论

当前使用/开发指南、插件 README、规范、CI 工具链说明和最近索引已同步。替换前 GoLand 需求/设计标记 superseded，历史验收不改写。安装技能的构建参考同步 JDK 25；后续已在用户明确授权下启动安装验收，原生界面工具无法可靠输入时暂停并请求手动协助。

| 项目 | 状态与原因 |
|---|---|
| S0–S4 实现、主代理 diff 复查 | 完成；独立复核问题已修复。 |
| 最终 Desktop / 工作流检查 | 通过，结果见上。 |
| 最终插件全量与单目标 Verifier | 通过：356 tests，GO-262.9437.286 Compatible；用户已安装 G5 布局修复 ZIP，只读确认安装 JAR 与工件一致。 |
| 独立只读 Reviewer | 通过；两项 P2 均已修复并经针对性复核关闭。 |
| G1–G7 隔离 GUI | 最终 356-test 工件全部通过，包含用户手动协助；G1–G4 使用 acceptance 夹具，G5–G7 使用 fresh 夹具。353-test 工件的早期结果仅保留为历史过程。 |
| 产品验收 GO | 通过，2026-09-20 完成；仅针对本需求、当前未提交候选和 GO-262.9437.286，不代表发布或原生 Git/语言能力的全面验收。 |

## 隔离 GUI 过程与保留现场

TEST_RUN 为 `/private/tmp/reqws-loading-gui-20260919-kam0lvc2`。成员夹具由本地 harness 创建三个独立 `.git` 仓库和普通文本，manifest 使用合法 credential-free HTTPS 测试地址，未在 GUI 重测成员创建。Desktop 从当前生产 main/preload/renderer 构建启动，bootstrap 只将 userData 指向 TEST_RUN；GoLand 官方启动器使用独立 config/system/plugins/log。用户明确批准此次终端启动例外；没有复制 IDE 或修改日常 IDE 配置。

用户通过隔离 Settings → Plugins 手动安装第一轮 346-test 候选。CUA 可访问状态和截图确认 ReqWS 0.1.0 Enabled；当时只读比较安装 JAR 与该轮 ZIP 内容一致，日志记录动态加载。未把动态加载当作 G4 的完整进程重启通过，也未把该安装当作后续修复候选的安装。

CUA 多次发送输入/坐标时返回 noWindowsAvailable，部分状态与截图不同步；因此按用户要求暂停并等待手动协助，没有以后台复制插件目录或内部 API 替代。第一轮 G1：用户在真实 Desktop 详情选择 repo1/repo2 并 Save selection，主代理确认 Desktop 首次发布 revision 1 且成员仍为三个；之后才在 Desktop 创建的合法 shell 写入 shell-probe.txt。随后从真实 Desktop Save and open 打开此 shell；用户明确授权仅信任该临时项目。

GUI 授权边界来自[测试计划第 3 节](test-plan.md#3-本地环境与夹具)及 manual-only 安装规范。获准后须使用唯一 TEST_RUN 下的临时 Desktop userData、GoLand config/system/plugins/log 和普通文本 Git 夹具，按普通 Project 面板完成观察并保留现场；不要把本轮平台重开测试当作测试 IDE 进程冷启动验收。

### 第一轮 G1 揭示的实现问题

第一轮安装候选为独立复核 no-op 修复后的 346-test 工件。正常 Project 展开受管 module 后，可见 repo1/probe-folder/repo1-probe.txt 与 repo2/probe-folder/repo2-probe.txt，shell/notes/repo3 不在树中；VCS 未配置仅显示只读诊断，未修改 mappings。但本轮没有通过 G1：

1. 首次启动在 `.idea` 未落盘时一直等待；20:54:39 打开后，直到20:58:14 IDE File → Save All，20:58:16 才自动应用 roots。没有点 Sync Now，也没有手写 `.idea`。这证明恢复链工作，但不证明首次自动加载完整。
2. `EMPTY_MODULE` 在真实 GoLand 被标注 Unknown module type，平台采用 Web Module 代替；Heavy fixture 不能证明产品 manager 注册支持。

以上已修正：承载 module 通过公开 ModuleTypeManager 采用平台默认类型；首次 metadata readiness 使用一次性公开 Project.scheduleSave，initialized=false 不消耗标记，已有配置、曾观察到配置后消失、既有投影、失去信任或绑定变化不排保存。原有身份检查和有界轮询保留。技术设计已同步，直接检查为 ManagedRootsAdapterTest 17 tests、metadata 相关 20 tests，通过。该阶段全量为 353 tests 并通过精确目标兼容性检查；后续 G5 布局修复后最终候选为 356 tests。新候选的安装和新建入口观察见下；旧工件的局部观察不转成新工件 GO。

### 353-test 候选安装与新入口 G1

用户在具体新工件确认后手动安装 353-test 候选；只读比较隔离 plugins 中的 JAR 与新 ZIP 完全一致。Plugins 页面显示 ReqWS 0.1.0 Enabled，21:20:01 的新启动日志记录加载 ReqWS；此安装阶段重启不替代后续 G4 的显式空集合冷启动检查。

新普通文本夹具在同一 TEST_RUN 的 `fresh/` 下，使用独立 Desktop userData。旧 Desktop 经正常菜单退出后启动新实例。用户在真实 Desktop 选择 repo1/repo2 并保存；只读确认首次 binding revision 1，且 `.idea` 不存在，然后才创建 shell-probe.txt。主代理从 Desktop Save and open 打开新入口（revision 2），用户仅信任该新项目。

G1 通过：信任后自动生成 `.idea`，21:26:48 日志记录本项目 Apply ReqWS loaded repository roots；未点击 Save All 或 Sync Now。原生和受管 `.iml` 都是 WEB_MODULE；普通 Project 面板展开两个仓库及 probe-folder，明确显示 repo1-probe.txt 和 repo2-probe.txt，完整根列表没有 repo3、notes 或 shell。截图在本任务 Computer Use 输出中保留。ReqWS 显示 Loaded repositories: 2；Partially Available 的原因是只读 Git Root Not Configured 诊断，不是模型应用失败。操作者未修改 Directory Mappings。

### G2 暂不加载、空集合和恢复

用户通过真实 Desktop 取消 repo2 并 Save selection。revision 3 仅选择 repo1，插件自动收敛，ledger claims 仅有 repo1。普通 Project 面板只显示 repo1 根及 Libraries/Scratches，用户协助展开后可见 repo1/probe-folder/repo1-probe.txt；工具窗口显示 Loaded repositories: 1，repo2/repo3 为 Not Loaded。主代理未点击 Sync Now。

磁盘断言确认三个原 probe 内容都保持原样，manifest、`.code-workspace` 与保存前副本逐字节相同，三仓库分支仍为 feature/gui-loading。

用户继续通过 Desktop 取消全部选择并保存，revision 4 为显式空数组。用户截图确认 Loaded repositories: 0、全部成员 Not Loaded；主代理 Computer Use 完整窗口截图确认普通 Project 根列表仅剩 External Libraries 与 Scratches and Consoles，仓库和 shell 均未出现。ledger claims 为空。此步再次逐字节核对 manifest/`.code-workspace`、全部原 probe 和 late-repo.txt，并核对三个仓库分支、两个 shell probe 仍存在，均通过。未点击 Sync Now。

用户从 Desktop 恢复 repo1/repo2 并保存，revision 5 与 ledger claims 均恢复为两仓库。用户截图和主代理 Computer Use 状态/完整窗口截图共同确认普通 Project 中两仓库、各自 probe-folder 与原 probe 文件重新出现，late-repo.txt 保留，Loaded repositories: 2，repo3/notes/shell 未出现。此步再次核对全部原 probe、分支、manifest 与 `.code-workspace`，均保持原样。G2 的 2 → 1 → 0 → 2 全程自动同步通过，选择操作由用户协助。

空集合时 Partially Available 与 VCS_CONFIGURATION_MISMATCH 仍出现。只读检查 `.idea/vcs.xml` 是 IDE 原生的空 directory Git mapping；当前 observer 将宽范围 mapping 作为诊断，所有未加载成员都正确显示 Not Loaded，没有按缺失单仓库映射报错，也没有修改 mapping。该磁盘检查不替代 G7 的 Directory Mappings 界面观察。

Computer Use 在后台窗口的复选框/树操作间歇返回 noWindowsAvailable 或无法点击不可见元素；主代理按每步最多两次的限制暂停，用户协助保存选择、信任及展开目录。没有后台写 binding 或通过内部模型 API 代替 GUI。打开新项目时一次只读 JBR 线程采样确认 IDE 正等待原生信任对话框，采样留在 TEST_RUN/evidence，未强制终止 IDE。

### G3 显示开关与运行中新文件

用户提供的菜单截图确认 Excluded Files 原值为勾选。因菜单失去焦点便关闭，由用户按明确步骤取消勾选、展开 repo1/probe-folder 截图，再重新勾选并以同样层级截图，最后恢复勾选。两张截图都显示 repo1-probe.txt，完整根列表仅 repo1、External Libraries、Scratches and Consoles；shell、notes、repo2、repo3 均未出现。此项记录为用户协助操作的显示开关对照通过。

随后在测试 IDE 运行中创建 repo1/probe-folder/late-repo.txt 与合法 shell 中的 late-shell.txt，均有真实非空内容且原 probe 保留。后台窗口的首次截图尚未显示新文件；用户切回 GoLand 后提供完整 Project 根列表截图，清楚显示 repo1/probe-folder/late-repo.txt 和 repo1-probe.txt，shell 及 late-shell.txt 仍未出现。再次读取两个新文件和原 probe，内容均保持原样。没有点击 Sync Now 或使用搜索；PFI 排除边界由本候选已通过的平台回归承接。G3 通过，显示操作和前台截图由用户协助。

### G4 用户额外 root、显式空集合和冷启动

用户在真实 Settings → Project Structure 中选中 ReqWS 承载 module，手动将 `fresh/user-extra` 添加为 Content Root 并应用，随后在 Desktop 清空加载选择并保存。revision 6 为显式空集合，ledger claims 为空。用户完整窗口截图确认 Loaded repositories: 0，Project 中 user-extra/extra-folder/user-extra-probe.txt 保留，受管仓库和 shell 未出现。

只读解析已保存 `.idea/reqws/ReqWS-<bindingId>.iml`，其唯一 content root 为 user-extra，没有 ReqWS ownership marker 或子配置；原生 `.idea/goland.iml` 与 G4 前副本逐字节一致，user-extra-probe.txt 内容保持原样。保存后的模块副本留在临时 evidence。

用户正常 Save All、Close Project，再由 Desktop 保持空集合 Save and open（revision 7）。日志记录 21:58:49 关闭并释放项目，21:59:03 对同一新入口自动应用模型；前后测试 IDE PID 与启动时间相同，证明此步是项目重开。用户截图再次显示 user-extra/extra-folder/user-extra-probe.txt、Loaded repositories: 0，仓库与 shell 未出现。只读核对 selection/claims 为空、受管 module 唯一 root 仍为 user-extra，两个 `.iml` 与重开前副本逐字节相同，用户 probe 内容不变。项目关闭重开通过。

用户随后通过 Quit GoLand 正常退出，主代理先只读确认测试进程已消失，再按已授权官方启动器例外，用原 GOLAND_PROPERTIES 和同一 fresh shell 重启。进程标识与启动时间已变化，22:01:50 的启动日志加载 ReqWS 0.1.0，22:01:54 自动应用当前投影；四个隔离目录均与重启前一致，安装 JAR 与确认候选保持一致。主代理冷启动后只读观察普通 Project 已自动展开 user-extra/extra-folder/user-extra-probe.txt，打开 ReqWS 工具窗口显示 Loaded repositories: 0，完整截图确认受管仓库与 shell 不出现，未点击 Sync Now。revision 7、空 claims、两个 `.iml` 和用户 probe 内容均未改变。进程对照留在临时 evidence。此阶段仅完成进程重启，恢复两仓库的后续结果见下一段。

用户随后从 Desktop 恢复 repo1/repo2 并保存（revision 8）。截图同时显示两仓库各自 probe-folder/原 probe、late-repo.txt，以及 user-extra/extra-folder/user-extra-probe.txt，Loaded repositories: 2，repo3/notes/shell 未出现。只读核对 ledger 仅认领 repo1/repo2，承载 module 恰有两个仓库和 user-extra 三个 roots，用户 root 没有 ownership marker；原生 module、用户 probe、manifest 和 `.code-workspace` 均未改变。G4 完整通过，用户协助添加 root、选择保存和正常关闭操作，主代理使用已授权隔离参数完成重启与冷启动观察。

### G5 布局缺陷与修复候选

用户在同一 ReqWS 承载 module 中手动添加 repo3 Content Root，Desktop 保持只选 repo1/repo2 并保存（revision 9）。只读核对 repo3 没有 ownership marker，ledger 仍只认领 repo1/repo2；用户截图显示 Project 中 repo3 root 保留、Loaded repositories: 2，插件已产生 userRootCoverage 状态。但长状态占据整行并向左越界，仓库名和说明均被裁切；用户明确指出此缺陷。该截图中的 repo3/probe-folder 尚未展开，G5 不记为通过。

根因是 BorderLayout 的尾列按状态文本完整首选宽度分配空间，挤掉了中间仓库名。修复仅涉及 Tool Window 展示：仓库名单行改用有界双列布局，保留名称可读宽度、尊重行内 padding/gap 与左右方向；40px 行高、六行视口、无横向滚动约束保留。用户 root 状态改为简短标签，另以安全转义的 tooltip 和可访问性描述提供完整解释，且不再暗示该仓库曾被 ReqWS 选中过。模型、ownership 和加载逻辑没有变化。

两个 Kotlin properties key 经原同模型继承、high reasoning 的只读翻译代理独立审校；主代理校验 key 集合/顺序、中文原文、空占位符集、术语后写回。i18n scan/apply/check 通过（脚本的 329-key 统计属于 Desktop catalog，不作为 Kotlin 文案语言审校证据）。新增回归覆盖窄宽度、选中态、RTL、真实长说明、HTML 形状长名称、带纵向滚动的视口，以及同一 renderer 切回短状态时清除旧说明。直接 45 项 UI 回归与最终 356 项插件测试通过，精确目标 Verifier Compatible；独立 Reviewer 只读复核后无新发现。

用户确认并手动安装 356-test ZIP；只读核验安装 JAR 与新 ZIP 一致，22:16:00 新启动日志加载 ReqWS 0.1.0，随后自动应用现有投影。主代理实际窗口截图确认 repo3 名称及 Included via User Project Root 短状态完整显示，可访问性描述保留完整解释。用户协助展开 repo3/probe-folder，提供 repo3-probe.txt 的完整父子层级截图。selection revision 9 仍仅包含 repo1/repo2，ledger 仅认领这两仓库；repo3 root 无 ownership marker，原 probe 内容未变。356-test 工件的 G5 通过。上面的 G1–G4 结果保留其原工件范围，不转写为新工件完整 GUI GO。

### 356-test 工件 G6 绑定错误恢复与无绑定项目

主代理在一次性夹具中备份 revision 9 的原绑定 bytes、承载 module 和 ledger，然后原子替换为有界的损坏 JSON，构造测试计划要求的读取错误。插件自动显示 Error/BINDING_ERROR 与保留上次有效模型提示；实际普通 Project 截图显示 repo1、repo2、repo3、user-extra 的原文件全部保留，Loaded repositories: 2。失效绑定同时撤销 shell 展示能力，原生 goland 节点重新出现，符合能力失效语义。模块、ledger 与原 probe 都没有改写。

随后逐字节恢复原绑定，没有增加 revision、保存新选择或点击 Sync Now。插件自动重核并退出 Error，repo3 恢复用户 root 包含说明，shell 再次隐藏，所有仓库/用户目录及文件仍可见。主代理使用实际窗口截图核对恢复状态与 Project 层级，并确认原绑定 bytes、两个 claims 和四根 module 保持。配置错误与自动恢复部分通过；无绑定普通项目的后续观察见下一段。

用户随后协助通过原生 Open 流程打开 `fresh/unbound-project`，普通 Project 截图完整展开 `.reqws → ide → goland → shell-probe.txt`，同名目录及文件没有被过滤，根列表未出现 ReqWS module。只读核对普通项目内没有 binding、reqws-loaded-roots.json 或 ReqWS 命名 `.iml`，shell probe 内容保留；已恢复的正式测试 binding 也仍与备份 bytes 一致。G6 完整通过。文件选择器对 Open 的禁用状态由用户手动操作解决，未改用内部 API 或后台写项目模型。

### 356-test 工件 G7 只读 VCS 观察与现场保留

切回绑定项目后，用户协助打开 Settings → Version Control → Directory Mappings 并提供截图。列表为 `<Project>`、repo1、repo2、repo3 四条 Git mapping；操作者只观察，没有新增、删除或修正 mapping。磁盘只读核对同样四条记录。IDE 自身保留/检测的 Git 范围不等于 ReqWS 加载集合，插件没有承诺或实现 Git Log/Commit 自动缩小。

显示开关按前述用户操作恢复原来的勾选状态。收尾检查确认三仓库与各自 `.git`、分支、全部原 probe、late-repo.txt、notes、两个 shell probe、用户额外目录和无绑定项目文件均保留；manifest、`.code-workspace` 与测试前副本逐字节相同，原生 module 与 G4 前副本一致。测试现场保留。G7 通过。

### 当前工件 G1–G4 复测准备

为避免把 353-test 工件证据记到最终 356-test ZIP 上，同一 TEST_RUN 下新建 `final/` 普通文本夹具和独立 Desktop userData；继续复用已经核验安装的 356-test 插件及隔离 IDE 目录。该夹具仅由 harness 创建三个 Git 仓库、合法 manifest、`.code-workspace` 和 Desktop 记录，尚未创建 GoLand shell；选择保存和入口发布仍需真实 Desktop 完成。现有 `fresh/` 的 G5–G7 现场完整保留。

`final/` 首次 Desktop 保存得到两仓库选择，主代理确认 `.idea` 尚不存在并补放 shell probe；随后打开过程的 Computer Use 读取两次超时。用户后续提供的窗口中 shell 直接可见，绑定已变为 revision 7/all。只读核查确认原隔离进程 22:44 已退出，新进程未带任何四目录隔离参数，不能把该窗口当作已安装候选的产品结果。用户正常退出后，主代理按原官方启动器参数恢复隔离实例，并通过进程系统属性逐项核验 config/system/plugins/log、安装 JAR 一致性和新启动日志中的 ReqWS 加载。

该 `final/` 现场保留，不删除普通 IDE 已创建的 `.idea`。另在同一 TEST_RUN 下创建未打开的 `acceptance/` 夹具与独立 Desktop userData，重新执行首次入口验证；未预先创建其 shell 或绑定。该准备阶段没有预先记录 G1–G4 通过，实际结果见下。

### 356-test 工件新入口 G1 与 G2 复测

`acceptance/` 使用独立 Desktop userData。主代理通过真实 Desktop 选择 repo1/repo2 并保存，确认首次 binding revision 1、`.idea` 不存在后，才在合法 shell 写入非空 shell-probe.txt。随后 Save and open 发布 revision 2，用户仅信任该新项目。只读核验 GoLand 仍为原隔离进程，四个 config/system/plugins/log 属性均指向 TEST_RUN。

信任后自动生成原生与 ReqWS WEB_MODULE，ledger 认领 repo1/repo2。主代理使用 Computer Use 展开普通 Project 的两个仓库及 probe-folder，截图完整显示 repo1-probe.txt、repo2-probe.txt，根列表没有 repo3、notes 或 shell；ReqWS 状态为 Loaded repositories: 2。没有点击 Save All 或 Sync Now。当前 356-test 工件 G1 通过。

用户协助取消 repo2 并保存，revision 3 仅选择 repo1，插件自动收敛，ledger claims 仅有 repo1。用户截图显示完整的 repo1/probe-folder/repo1-probe.txt 父子层级，根列表没有其余仓库或 shell。主代理逐项确认三仓库及 `.git`、全部原 probe、分支、manifest 和 `.code-workspace` 保持原样。

用户随后通过 Desktop 取消全部选择并保存，revision 4 为显式空数组，ledger claims 为空。用户提供的普通 Project 完整截图只剩 External Libraries 与 Scratches and Consoles。主代理再次核对三仓库原 probe/分支、manifest、`.code-workspace`、notes、shell probe 和待添加的 user-extra probe，全部保留且内容未变。此阶段的 2 → 1 → 0 已通过，下文继续验证恢复两仓库。

本轮部分 Computer Use 状态与截图存在延迟，复选框/树节点操作再次出现 noWindowsAvailable。达到单步重试限制后按用户要求立即暂停，由用户协助保存选择和展开截图；未通过后台写 binding、内部模型 API 或 Sync Now 绕过自动同步验证。

用户随后恢复 repo1/repo2 并保存，revision 5 按用户点击顺序保存两 ID，插件按 manifest 顺序投影并仅认领这两仓库。用户截图完整展开两个 probe-folder，原文件均恢复可见，完整根列表没有 repo3、notes 或 shell。主代理再次核对全部仓库/原 probe/分支、manifest、`.code-workspace` 及其他探针内容未变。356-test 工件 G2 的 2 → 1 → 0 → 2 完整通过。

### 356-test 工件 G3 显示开关与新文件

用户协助切换 Project → Appearance → Excluded Files，并依次提供取消勾选、勾选、恢复原值的三张截图。各图完整展开 repo1/repo2 的 probe-folder 与原始文件，完整根列表没有 repo3、notes 或 shell。截图本身未展示菜单勾选标记，开关状态和恢复原值依据用户对实际操作的说明；未把同一静态树截图冒充程序读取的开关值。

主代理确认隔离 GoLand 进程仍在运行，随后仅在 acceptance 夹具中新建 repo1/probe-folder/late-repo.txt 和合法 shell 中的 late-shell.txt，两者均有非空真实内容，三个原 probe 保持原样。该阶段仅完成文件创建，Project 可见性由下一段单独取证。

用户切回隔离 GoLand 后提供完整 Project 截图，明确显示 repo1/probe-folder/late-repo.txt、repo1-probe.txt，以及 repo2 的原 probe；根列表没有 shell、notes 或 repo3。主代理只读复核两个新文件和全部原 probe 的真实内容均保留，未点击 Sync Now、未使用搜索。356-test 工件 G3 完整通过，开关切换和最终前台截图由用户协助。开始 G4 前已保存原生 goland.iml 的临时比较副本；待添加的 acceptance/user-extra 及其真实 probe 已存在。

### 356-test 工件 G4 用户额外 root 与空集合

用户在 Project Structure 中向 ReqWS-fd64cff3-4193-47f7-8711-e8096afc0f35 添加 acceptance/user-extra，随后通过 Desktop 清空选择并保存，binding revision 6 为显式空集合，ledger claims 为空。用户完整 Project 截图显示 user-extra/extra-folder/user-extra-probe.txt 保留，受管仓库与 shell 均不出现。

主代理只读解析已保存的 ReqWS module，确认唯一 content root 是 acceptance/user-extra，且没有 ownership marker 或子配置。原生 goland.iml 与 G4 前副本逐字节相同；三个仓库、原 probe、运行中新文件、用户 probe、分支、manifest 和 `.code-workspace` 均保留原样。临时 evidence 已保存空集合 module 副本和重开前进程标识。此阶段仅确认清空后用户 root 保留；后续分别验证项目重开、完整进程重启及恢复两仓库。

主代理通过原生 File → Save All、Close Projects in Current Window 正常保存并关闭测试项目，再从 Welcome 的 Recent Projects 打开同一 acceptance 入口。日志记录 2026-09-19 23:53:25 关闭项目、23:53:39 重新初始化并自动应用投影；进程 PID 与启动时间未变化，明确属于项目重开。用户补充的前台截图显示 user-extra/extra-folder/user-extra-probe.txt，受管仓库与 shell 未出现。revision 6、空 claims、原生及承载 module 文件、用户 probe 均与关闭前一致，项目关闭重开通过。

用户随后正常 Quit GoLand；主代理先确认 GoLand 应用进程完全消失，再使用已授权官方启动器和原 GOLAND_PROPERTIES 打开同一入口。新进程于 2026-09-20 00:05:48 启动，PID 与旧进程不同；四项 config/system/plugins/log 属性逐一验证未变，已安装 JAR 与已确认的最终 ZIP 内容一致。新启动日志加载 ReqWS 0.1.0，00:05:52 自动应用投影。

冷启动后主代理只读观察真实 Project 截图，user-extra/extra-folder/user-extra-probe.txt 自动恢复且完整可见，受管仓库与 shell 未出现；ReqWS 窗口显示 Loaded repositories: 0，三个成员均 Not Loaded。未点击 Sync Now 或手工 reapply。再次逐字节核对两个 module、用户 probe、manifest、`.code-workspace`，并核对所有仓库/探针与分支均未变。完整进程重启通过；恢复 repo1/repo2 后的实际文件树观察见下一段。

用户通过真实 Desktop 恢复 repo1/repo2 并保存，revision 7 只选择这两个仓库，插件自动恢复两个 claims。保存后的承载 module 恰有 repo1、repo2、user-extra 三个 content roots，用户 root 没有 marker 或子配置。主代理展开三个目录；由于工具截图停留在 user-extra 的 loading 状态，用户补充最终前台截图，完整显示 repo1/probe-folder/late-repo.txt、repo1-probe.txt，repo2/probe-folder/repo2-probe.txt，以及 user-extra/extra-folder/user-extra-probe.txt。完整根列表没有 repo3、notes 或 shell。原生 module、所有探针、仓库分支、manifest 和 `.code-workspace` 再次核对均保留原样。356-test 工件 G4 完整通过。

### 最终收尾与范围

2026-09-20 收尾只读复核确认最终 ZIP 未变化，隔离安装 JAR 与该 ZIP 内容相同；保留的全量报告为 37 类、356 tests、0 failures/errors/skipped。在该轮 GUI 验收结束前，自最终 356-test ZIP 构建后生产源码未再修改，收尾仅补充文档与索引。fresh 夹具的 G6 原绑定仍与故障前备份逐字节相同，原生 module 保留；acceptance 夹具保持两仓库加载及用户额外 root。两个夹具的当前工件结果共同覆盖 G1–G7，未挪用旧工件结果。

最终结论为本需求产品验收 **GO**。成员夹具由 harness 创建，选择保存和首次打开经过真实 Desktop；安装、部分选项/目录操作及截图由用户协助，项目重开与冷启动按上述实际操作记录。VCS 配置警告保持只读诊断，未修改 Directory Mappings；未追加搜索、Go SDK、补全、Git 生命周期等原生能力验收。测试现场和运行实例保留，没有自动清理、提交、推送或发布。
