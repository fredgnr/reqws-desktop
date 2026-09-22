---
title: IDE 插件兼容性与自动化回归改造方案
type: technical-design
status: active
updated: 2026-09-22
---

# IDE 插件兼容性与自动化回归改造方案

本方案让 ReqWS 插件从 262 系列起按公开 API 兼容性持续支持更新 IDE，同时把重复的真机操作移入可复现的自动化流程。

开发实现及验证缺口见[开发记录](implementation-2026-09-21.md)。下文描述目标契约；不代替本候选的执行证据。

执行地点已调整：CI 保留编译（包括 Starter/Driver 宿主）、单元、Light/Heavy 平台、禁用 API、结构/产物策略及跨版本 Verifier；只有启动完整 GoLand 的 Starter/Driver L2/L3 在本机运行。PR、Release、定期工作流不启动完整 IDE、不请求 License Server 或 IDE 授权凭据。开发后的实际执行范围和未完成项见[验证记录](verification-2026-09-21.md)，不能从 CI 推导 UI 已通过。插件没有 Settings configurable，打包关闭 `buildSearchableOptions`，避免选项索引任务隐式启动 IDE。

API 验证使用官方 Maven SDK 分发，不下载 OS 安装器。本机可以通过 `reqwsVerifierSdkPaths` 显式提供 JSON 路径数组以只读复用已有 SDK；产品、发行版、build 必须与选定目标一致，重复或不匹配必须失败。其他目标仍正常下载；该选项不影响编译 SDK，不启动已有 IDE，也不复用其用户配置或授权状态。

最低 SDK 的真实 Verifier 检查发现 `TrustedProjectsListener` 在 2026.2 仍为 Experimental。信任恢复与撤销改为通过公开 `TrustedProjects.isProjectTrusted` 探针检测：仅对已确认的 ReqWS 项目按当前状态等待相反信任状态，默认一秒一次；恢复后仍强制同 digest 重放，撤销后进入 Safe Mode 并撤销 live proof。普通项目、错误终态及 dispose 取消轮询。模型写入和树能力读取继续同步检查当前信任状态，安全边界不依赖轮询时延。

## 1. 决策与非目标

用户已确定以下政策：最低支持整个 `262` 系列，长期不随本机或 SDK 升级移动；不预设最高版本；更高版本依靠自动 API 兼容验证，不要求最高版本真机或 computer use 回归。每次插件相关迭代必须评估最低版本影响，确需提高下限时必须单独论证并获得用户明确批准。

目标描述文件只有：

```xml
<idea-version since-build="262"/>
```

`262.*` 是需求中的系列称呼，不能直接作为 `since-build`。不写 `until-build`、`strict-until-build`，也不把“最近验证通过的版本”回写为上限。移除上限符合当前官方的普通插件配置建议。[S1][S2]

正式支持对象是该系列起的正式发行版；范围语法本身不区分同分支 EAP，因此允许加载不等于承诺 EAP 已验证。

这次扩展到整个 2026.2 系列，明确替代先前“从 2026.2.1.1 才开始”的目标。修改描述本身不证明更早的 262 正式发行版可用，因此开放新范围前要完成最低发行版的编译、平台测试和 Verifier 检查。

不新增 IDEA 产品支持、不更改插件身份、签名身份或发布渠道，不恢复 Go 语言依赖，也不重新测试 IDE 原生 Git、Go SDK、补全、运行调试。当前 `com.intellij.modules.goland` 依赖仍保留；将来支持 IDEA 时，另行审查模块依赖并加入该产品的自动验证目标，不能把 GoLand 的 build 或结果直接套给 IDEA。[R2][S10]

## 2. 当前实现与要改的连接点

本次静态核对的基线是 Git 提交 `7f9dc8b3a17d11339cfed5d7d770f90df659fe3b`，不是新兼容范围的测试证据。

| 当前实现 | 问题与改造方向 |
|---|---|
| `build.gradle.kts` 的编译 SDK、Verifier 均为 2026.2.1.1，descriptor 两端均为 262.9437.286。 | 拆开最低系列、编译 SDK、GUI 代表版本和 API 验证集合。 |
| 本地 SDK override 用完整 build 字符串匹配。 | 结构化读取 ProductInfo，按任务角色校验产品和固定发行版；本机新版不能抬高发布基线。 |
| `ci-cache-config.py` 强制两个 GoLand 版本字面量相同。 | 与 Gradle 一起改为消费统一配置；不能只扩展 Verifier 就破坏缓存解析。 |
| `setup-goland` 已拆分 installer 与 Gradle 缓存，默认分支持有共享写权限。 | 保留这项优化；多 IDE 只增加版本化下载，不缓存整个测试 sandbox。 |
| `check:goland` 已含测试、禁用 API、结构/配置及 Verifier 检查。 | 保留原覆盖，接入新的目标解析、产物策略检查和结果汇总。 |
| `ManagedRootsAdapterTest` 使用 Heavy 平台测试；`ShellPresentationTest` 检查模型及过滤逻辑。 | 复用已有测试；仅为完整启动、真实监听和最终 Project 树补少量集成用例。 |

实现入口见 [R1]—[R5]。测试案例在实施阶段按实际方法清点；本方案不虚报覆盖率或“已节省”的手工比例。

## 3. 四类版本必须独立

建议新增 `integrations/goland/compatibility.properties` 作为项目配置来源。以下是拟新增的 ReqWS 属性，不是 JetBrains 自带 DSL：

```properties
minimumPlatformBranch=262
compileIdeProduct=GO
compileIdeVersion=2026.2
uiTestIdeProduct=GO
uiTestIdeVersion=2026.2.1.1
verificationProducts=GO
```

`2026.2` 是待 S1 从官方发行目录和实际分发包确认的最低正式 SDK 目标，不能只凭字符串认为已验证存在或可用。确认后固定产品发行版本，通过分发包 ProductInfo 记录实际 build；不手工编造 build。GUI 代表版本固定为现有 2026.2.1.1，不追逐“最高版本”。

| 对象 | 稳定规则 |
|---|---|
| 最低系列 | 永久显式配置 `262`，不能从当前 SDK 推导；降低或提高均需审查，尤其禁止无批准提高。 |
| 编译 SDK | 优先使用 262 最早正式发行版；禁止浮动 latest。测试工具不适配时先解决测试工具问题。 |
| GUI 代表环境 | 固定一个 262 正式版本，仅承担真实集成证据；变更需说明原因，但不意味着升最低版本。 |
| API 检查目标 | 按正式发行目录自动生成，每次运行冻结具体产品、发行版和 build。最新版本不是上限。 |

编译 SDK 使用最低支持版本是官方推荐方式。[S1] 初次迁移若发现现有生产代码依赖后续补丁才有的 API，优先用 262 基线公开 API 调整；无法满足时阻塞实施并给出证据，不能暗中退回精确补丁下限。测试框架自己的内部 API 或类路径故障，不直接等同于插件不兼容。

生产字节码目标、Kotlin API/stdlib、传递依赖和最低目标的 JBR 要一起核对。当前 Java 25 配置不因改下限而盲目升降，也不把构建机的 Java 版本当成用户 IDE 的运行时证明。

## 4. 产物范围与发布一致性

Gradle 显式读取最低系列，并显式取消普通上限。核心配置方向如下，配置加载和验证由实现补全：

```kotlin
intellijPlatform {
  pluginConfiguration {
    ideaVersion {
      sinceBuild = "262" // 实现时取统一策略配置，不从 SDK 推导。
      untilBuild = provider { null }
    }
  }
}
```

`provider { null }` 是官方文档给出的取消方式。[S3] 同时检查源 XML、patch 后 XML、ZIP 内真正加载的主 descriptor，确保没有残留 strict 上限、第二份主 descriptor 或流程重新加回的精确范围。不得为了取消上限而添加不存在的 DSL 属性。

新增产物策略检查应拒绝：`since-build="262.*"`、精确补丁下限、未经批准的 `263` 等更高下限，以及任何普通或 strict 上限。检查通过后才允许交给现有 release ZIP 校验和签名链路。

候选只构建一次。CI Verifier 和本机 Starter 都消费显式指定的同一候选 ZIP，不分别重编生产插件。PR 可用未签名候选；正式发布的 API 检查消费最终签名 ZIP，声称正式产物已完成本机 UI 集成时也必须使用该最终签名 ZIP，不能借用签名前证据。签名后不修改或重打包。CI/Release 汇总只证明其自动检查，本机集成单独记录为未运行、环境阻塞、失败或通过，不把 CI 绿色或 skipped 写成 UI 通过。现有签名、版本、ArchivePath 和 Marketplace 上传校验不削弱。

Marketplace 中若已有人工设置的远端兼容限制，不能假定改 GitHub ZIP 会自动覆盖它；由已授权发布流程核对。当前文档 PR 不执行市场写入。[S2]

## 5. 自动 API 兼容验证

### 5.1 冻结目标集合

在每次插件相关 PR、最终发布和定期检查开始时，由目标解析任务获取官方正式发行目录，仅选已声明产品且平台分支不低于 262 的发行版。可使用官方 Gradle 产品发行筛选能力或其官方数据源，先形成具体清单，再让所有验证任务消费同一份清单。[S3]

清单至少记录获取时间、来源、产品、发行版本、实际 build、发布渠道、选择理由和是否必需；存入 CI artifact，不提交不断变动的完整发行台账。版本按整数分段比较，不按字典序排序。重复版本按产品和 build 去重，不能跨产品合并。

矩阵分片默认最多两个重任务并发；容量不足时排队或明确报资源阻塞，不静默截断必需目标。

每周 main 与已发布候选的 API 矩阵按阶段执行，避免两组各开两个 worker 而合计四个；main 的失败不阻止已发布候选独立取证，取消的工作流不再启动后一组。

矩阵规则：

| 场景 | API 验证集合 |
|---|---|
| 插件相关 PR | 最低 262 正式发行版、固定 GUI 代表版本、262 及以后每条已发布版本线的首个和最新正式版，加已知回归涉及的中间版本。 |
| 首次开放 262 / 正式发布 | 当次目录中所有可解析的 GO 正式发行版，分支从 262 起；矩阵分片，不要求逐个 GUI 回归。 |
| 每周检查 | 对当前 main 候选和最近已发布 ZIP 分开做完整正式版 API 扫描；两者结果不可混用。 |
| EAP 探测 | 可单独运行，只作预警，不替代正式集合，也不提升最低版本。 |

PR 的代表性采样不是“每个补丁都检查过”；完整集合的发布扫描用于补足这个边界。不存在“直到最新”的配置上限或伪造的未来 build。目录发现应有短期缓存与新鲜度记录；PR/发布解析失败、必需目标缺失、空集合，均不能回退到默认推荐列表或缓存旧结果后报成功。可显式重跑已冻结清单定位故障，但正式发布前须完成新鲜目录解析。

### 5.2 结果分类与门禁

Verifier 负责二进制 API 兼容，不执行插件业务，也不证明 UI 和异步语义完全一致。[S4] 项目采用这一边界：较高版本只要求自动 API 证据，不新增该版本的真机、computer use、截图或 Starter GUI 验收。

| 结果 | 处理 |
|---|---|
| 缺类/方法、调用签名不兼容、无效插件或确实缺少声明依赖 | 正式目标阻塞候选；保留完整报告并修复。 |
| 下载、网络、目标解析、Verifier/JBR 启动失败，或没有目标报告 | 标为基础设施阻塞/未完成，不标兼容通过，也不伪装成代码不兼容。 |
| 生产代码新增 Internal / Experimental / 私有 API | 继续由现有禁用 API 检查及 Verifier 报告阻塞。测试工具依赖不混入生产判定。 |
| Deprecated / ScheduledForRemoval 等提示 | 保留并评估；可继续使用基线所需的公开 API，不为消除提示自动升版。 |
| 未发布 EAP 的问题 | 单列预警，不混入稳定版本的通过数量。 |

显式设置所用 Verifier 版本支持的失败级别，并检查每个必需目标有有效终态；不能假定 CLI 退出码或新版 Gradle 的默认行为足够。实现须对仓库固定的 Gradle Plugin 2.18.1 验证任务行为；官网最新文档的新选项不一定存在于该版本。[S5]

API 分片同时保存完整 Gradle 日志并实时输出到 Actions；使用 plain/info/stacktrace 日志区分配置、依赖下载/解压和验证阶段，不启用 debug 或打印环境变量。每 30 秒记录目标、宿主 PID、耗时、日志字节数、最后任务和静默时长。连续 5 分钟无输出时保存本次进程组的 CPU/内存/状态及最多四个 Java 进程的线程栈，静默诊断最多三轮，90 分钟超时前再采集一次。诊断失败只记录原因，不改变候选、目标、兼容终态或超时门禁；不扫描/终止其他进程组，不启动 IDE。取消和超时仍记录非通过终态，尽力保存已有日志供 always artifact 步骤收集。

不使用宽泛忽略文件、外部类前缀或 `continue-on-error` 隐藏兼容错误。真正的误报例外需精确匹配符号/目标，有复现、原因、负责人和复查条件，并在报告中显示。

### 5.3 更高版本发现破坏时

先确认是生产 API 不兼容还是测试工具/下载失败，再优先尝试同时兼容 262 与新平台的公开 API。不得为了让新版本通过而自动提高下限、抹掉 262 目标或改用反射/私有 API。

发现真实不兼容时记录具体版本和符号，不把“通过的最高版本”简单视为连续兼容边界，因为中间版本也可能有独立问题。无法立即修复时，明确告知已知不兼容范围并提交处置决策；必要的 Marketplace 限制或特殊发布必须另行获得授权，不自动新增 upper bound。

开放上限只意味着不预先禁止未知新版本安装。报告应写“本次 API 已验证的目标集合”，不能写成“无限版本均兼容”。用户报告的真实行为缺陷仍须处理，但不能因为没有最高版本 GUI 证据而人为判定发布不合格。

## 6. 测试分层：复用已有覆盖，不把用例全部搬到 GUI

| 层级 | 主要内容 | 运行位置 |
|---|---|---|
| L0 普通测试 | manifest、路径边界、状态机、去抖、latest-wins、原子持久化、错误分类。 | CI 保留。 |
| L1 平台测试 | 真实 Workspace Model、ProjectFileIndex、所有权保护、保存重开、取消/dispose。 | CI 的最低 262 SDK Light/Heavy 测试，不启动完整 IDE。 |
| L2 进程集成 | 真实插件加载、VFS 自动消费、服务生命周期、完整 IDE 进程冷启动。 | 仅本机固定 GoLand 2026.2.1.1 的隔离 Starter 实例。 |
| L3 自动 UI | 实际 Project 树、ReqWS 状态展示，以及普通项目不受影响。 | 同一本机代表环境，Driver 自动定位和断言，不改成人工逐步回归。 |
| L4 API 矩阵 | 对同一候选 ZIP 做跨版本二进制检查。 | 无需 GUI 的 Verifier job。 |

平台测试使用真实平台组件，并非要求大量 mock。[S6] `HeavyPlatformTestCase` 属于 L1，继续在 CI 运行；不能因名称包含 Heavy 而迁出。涉及模块、多个 roots 和保存恢复的场景保留 Heavy 测试及所有安全、所有权、并发、恢复覆盖。[S7]

跨进程/UI 使用独立 `integrationTest` source set 和 JUnit 5；现有 JUnit 4 平台测试不迁移。本机入口 `scripts/run_local_ide.py` 提供 `prepare`（专用环境交互授权准备）和 `run`（显式 ZIP 的自动集成），调用 Gradle 的专用宿主任务。入口与 Gradle 任务均拒绝 CI；不与 `test`/`check`/`buildPlugin` 自动绑定，不重新构建生产插件。

Starter、Driver 和相关测试依赖须使用互相匹配的固定版本，不使用 `LATEST`。先验证 262 代表版本的最小启动链路及库的实际接口，再扩展场景；测试依赖不得打进生产 ZIP。Driver 的测试侧实验性状态不构成放松生产禁用 API 规则的理由。[S8][S9]

### 6.1 最小完整 IDE 场景

只保留三个常规自动场景组，边界组合主要留在 L0/L1：

1. **加载与树展示**：打开本地普通文本 Git fixture；Project 树仅显示被选仓库，入口元数据不泄漏。展开 `repo-a/docs/probe.txt` 并断言节点存在；不通过 Find in Files 间接证明。另开普通项目，确认无 ReqWS 模型写入。
2. **真实自动刷新**：由测试宿主按 Desktop 协议原子替换加载配置，完成 `2 → 1 → 0 → 2`；不直接调用 refresh/service 来触发被测链路。等待目标配置版本已被消费，再检查实际树节点与状态。磁盘仓库仍在，用户添加的非受管 root 不被删除。
3. **进程冷启动恢复**：退出完整 IDE 进程，在保留该 fixture 项目与指定持久配置的前提下启动新进程，验证空集合和非空集合恢复；不能只重建 adapter 或重开 project 代替进程级证明。

第 2 组验证插件消费端，不冒充真实 Electron UI 到 IDE 的整链路。共享契约改变时另补 Desktop 与插件的契约测试；只有启动协议或 OS 交互改变时才评估更小范围的跨应用检查。

### 6.2 避免自动化假通过

必须等待本次请求/配置 revision 对应的终态及模型收敛，而非任意旧 `Synced`、固定 sleep 或“后台指示器消失”。平台测试也不能假定项目打开就已执行完 `ProjectActivity`。[S11]

将 IDE 进程异常通过 Starter 的 `CIServer` 桥接到本机宿主失败，不能依赖仅适用于 TeamCity 的上报。超时、冻结、异常退出、进程未清理、XML 缺失/全跳过同样不得通过。[S8] 每轮报告绑定实际 ZIP 摘要、插件版本、实际 IDE 产品/版本/build、选择器、执行数量和退出结果；摘要只保留在本机报告，不提交源码台账。

测试驱动可通过只读 API 获取同步结果辅助等待，但必须经过真实触发链路，并保留 UI 的最终断言。需要桥接时优先使用既有服务；不得为测试向生产暴露无保护的远程命令接口。

## 7. CI 编排与缓存

保留现有必需检查名称 `GoLand plugin checks`，将其作为最终汇总；保持 `Checks and macOS package smoke` 及 Desktop、签名、发布门禁语义。新子任务失败或取消不能让汇总变绿。仅在路径分类确认无影响时，才给“不适用”的具名结果；无法判定时保守地按有影响运行。

| 改动/触发 | CI 自动执行 | 本机单独记录 |
|---|---|---|
| 纯文档 | `docs:check`、短兼容影响说明。 | 无需完整 IDE。 |
| 插件纯逻辑 | 编译、完整保留 L0/L1、产物策略及 API 矩阵。 | 按实际影响判断 L2/L3。 |
| 项目模型/VFS/生命周期/Project 树/入口契约 | 上述自动 CI 范围，包括全部 Light/Heavy 平台覆盖。 | 固定代表环境的相关 L2/L3；未运行需明确记录。 |
| SDK、依赖、descriptor、构建、打包、测试框架或分类器 | 完整自动 CI 范围与 API 矩阵。 | 固定代表环境三个场景组。 |
| 插件正式发布 | 完整保留 L0/L1、最终签名 ZIP 的完整稳定 API 矩阵及原签名/资产门禁。 | 最终签名 ZIP 的固定环境集成状态独立；签名前报告不能复用。 |

影响分类同时考虑新增、删除、重命名文件；覆盖共享 manifest/schema、Desktop GoLand 入口生成、工作流和缓存/发布脚本。分类器自身变化必须进入全插件门禁，不能靠修改 PR 文字绕过。

job 分为：影响与目标解析、基线测试/单次构建、API 分片及必需检查汇总，不包含 Starter/Driver job。影响分类中的本机集成建议只作提示，不触发用户电脑。API 每次冻结目标，最多两个重任务并发；保留逐目标终态及同一 ZIP 检查。CI 证据明确写 `scope=ci-api`、`localIntegration.status=not-run`；发布汇总不读取或伪造本机 UI 通过。更高版本仅扩展 API 集合，不增加多版本本机 GUI。

沿用默认分支独占共享缓存写入、PR/Release 只读策略。下载缓存键包含产品、发行版、OS/架构；基线/固定 GUI installer 可长期复用，高版本 installer 只保留有收益的少量条目或本次临时目录，不把所有矩阵下载永久塞入 cache。编译 SDK 与 API 目标分开后，`ci-cache-config.py` 和对应工作流测试必须一起更新。[R3][R4]

禁止跨分支恢复整个 IDE config/system/plugins/project sandbox；避免把上次项目状态误当成恢复成功。日志、矩阵快照和测试结果走有保留期的 artifacts，而不是依赖 cache。保留现有缓存用量报告，比较新增矩阵前后的实际容量和命中情况，不预先承诺加速比例。

## 8. 本机环境、授权准备与隔离

本机自动集成前检查图形会话、固定 IDE、候选 ZIP、Driver 所需权限及实际授权状态。`JETBRAINS_LICENSE_SERVER` 只是可选方式；没有该变量时允许在专用测试环境通过 JetBrains Account 交互登录。`prepare --profile <专用目录>` 打开无项目的固定 GoLand，用户自行完成登录/权限准备并正常退出；此步骤只记授权准备会话已结束，不宣称授权或 UI 测试通过。

授权复用采用显式、持久、仅供测试使用的 profile/config，IDE 直接读写该目录；不从日常 GoLand 复制许可证、账号令牌或整套配置，不假定日常登录会被继承。profile 必须由本入口从空目录初始化并带所有权标记，使用独占锁防止两个测试会话共享配置。Starter 宿主使用本轮独立 JVM `user.home`；排除带全局进程清理的可选 `ide-starter-junit5`，只按本轮进程句柄退出和清理，保留 JUnit 5 执行、IDE 错误上报与进程终态检查。目录留在本机，不进 cache、报告或 CI artifact；不自动注销或清理授权文件。

固定 SDK/installer 与测试依赖在 profile 的独立下载缓存保留，以稳定测试 IDE 二进制路径和本机权限准备；不能把该缓存扩展为业务 workspace、system 或整套 sandbox 复用。每次仍核对 SDK ProductInfo 与实际运行目录。

每轮新建独立运行目录，业务 fixture、`.idea`、system、plugins 和日志全部重新创建。专用 config 中的 workspace 存储、IDE 自动生成的 projects 欢迎工作区、recentProjects 与 trusted-paths 等项目记录在启动前移入该轮的私有隔离区，不复制授权内容；禁止在授权准备窗口打开真实项目或导入/同步个人设置。冷启动组仅在同一轮、同一 fixture 内保留其持久模型。固定测试正向 fixture 可逐项预信任，Safe Mode 负向测试不得全局绕过信任。

本机报告区分 `passed`、`failed`、`environment-blocked` 和 `not-run`；明确记录授权弹窗、图形会话缺失、权限拒绝、启动/连接未完成等原因。缺授权或无法确认启动状态不能绿色通过。授权准备成功退出也不能代替候选的九个 IDE 进程及三个自动场景结果。

基础设施不可用时记录具体阻塞，先修环境或测试工具；不能自动转成“请用户手测最高版本”。确需用户级安装、系统首次授权/对话框、跨应用激活或难以稳定复现的原生问题时，才提出最小 computer use 例外：说明无法由当前自动化证明的契约、目标环境和预期结果，并取得相应授权。

例外按具体缺陷而不是 IDE 版本号触发；不得把“最高版本还没截图”当作例外理由。不能在此文档任务中安装或重启用户本机 GoLand。

## 9. 每次迭代的最低版本评估

计划开始与最终评审各做一次，记录在现有任务/PR，不为普通迭代创建独立巨型报告：

```text
最低版本评估
- 基线：262（保持 / 申请提高）
- 本次新增平台 API、依赖、运行时要求：
- 262 最早正式版本上的证据，或无运行时影响的理由：
- 高版本自动 API 目标与结果入口：
- 是否影响固定环境集成；若不运行，说明分类理由：
- 结论：保持基线 / 阻塞待修复 / 单独申请升版
```

升版申请必须指出最低必需新版本、失败复现、基线公开 API 替代方案及不采用的原因、对用户影响和迁移方案。不得以依赖机器人升级、开发机升级、减少 CI 成本或修复一个失败测试为充分理由。

CI 对最终产物的下限漂移强制失败，策略文件变化必须在 diff 中可见；最终批准仍来自用户明确决定，不能只靠 PR 复选框。每次扩大自动 API 集合不视为升版，也不触发真机矩阵。

## 10. 迁移、证据与实现边界

具体任务与验收见 [实施与验收](implementation-plan.md)。先建立替代测试的正向及失败注入证据，再删除对应的常规 computer use 条款；不能一开始把所有 GUI case 删除，也不能在自动化替代完成后继续把旧手工清单叠加为永久门禁。

新的成功证据分别标记 `API_VERIFIED`、`PLATFORM_TESTED`、`IDE_INTEGRATION_TESTED`；未运行、环境阻塞和通过分开。报告包含候选 commit、插件版本、ZIP artifact 入口、目标产品/发行版/build、工具版本、实际测试选择器和非零执行数量。高版本只有 API 验证是本策略下的正常状态，不伪写成 GUI 通过。

文档不提交源码/工件 SHA 清单。需要比对单次运行的同一 ZIP 或 JAR 时，在 CI 日志/artifact 中保留临时结果；Git 提交和既有 release 工件机制继续作为追踪入口。

本轮调整代码和工作流的执行地点，不运行测试、GUI、授权准备、安装或登录；同步开发标准、插件 README、开发/验收指南和发布入口。历史验收结论保持原义，新的本机路径仍待验证。

## 11. 核对来源

官方资料于 2026-09-21 读取；下面是能力与语法依据，矩阵频率、分层和门禁是 ReqWS 的设计决策。实现须再核对固定工具版本的实际接口，不机械套用官网新版本默认行为。

- S1：[Build Number Ranges](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html)：合法下限、开放上限和最低 SDK 构建原则。
- S2：[Plugin Configuration File](https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html#idea-version)：普通/严格上限及 Marketplace 限制。
- S3：[IntelliJ Platform Extension](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html)：取消 untilBuild、产品信息和 Verifier 目标选择。
- S4：[Verifying Plugin Compatibility](https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html)：二进制验证范围。
- S5：[Gradle Tasks](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html)：输入 ZIP、报告与失败等级。
- S6：[Testing Overview](https://plugins.jetbrains.com/docs/intellij/testing-plugins.html)：真实平台组件的模型测试。
- S7：[Light and Heavy Tests](https://plugins.jetbrains.com/docs/intellij/light-and-heavy-tests.html)：项目隔离与 Heavy 测试。
- S8：[Integration Tests Introduction](https://plugins.jetbrains.com/docs/intellij/integration-tests-intro.html)：Starter、进程隔离及 IDE 异常桥接。
- S9：[UI Testing](https://plugins.jetbrains.com/docs/intellij/integration-tests-ui.html)：Driver 的组件定位和 UI 断言。
- S10：[Plugin Compatibility with IntelliJ Platform Products](https://plugins.jetbrains.com/docs/intellij/plugin-compatibility.html)：产品模块依赖。
- S11：[Testing FAQ](https://plugins.jetbrains.com/docs/intellij/testing-faq.html)：生命周期和 ProjectActivity 等待。
- S12：[IntelliJ Platform Testing Extension](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-testing-extension.html)：按任务隔离的测试入口。
- R1：[插件构建配置](../../../integrations/goland/build.gradle.kts)与 [SDK/工具版本配置](../../../integrations/goland/settings.gradle.kts)。
- R2：[插件主描述](../../../integrations/goland/src/main/resources/META-INF/plugin.xml)。
- R3：[CI 缓存读取器](../../../scripts/ci-cache-config.py)。
- R4：[GoLand CI 准备](../../../.github/actions/setup-goland/action.yml)与 [CI 工作流](../../../.github/workflows/ci.yml)。
- R5：[既有插件测试](../../../integrations/goland/src/test/kotlin/com/reqws/goland)。
