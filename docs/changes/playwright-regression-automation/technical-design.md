---
title: ReqWS Playwright 回归自动化方案与改造流程
type: technical-design
status: active
updated: 2026-09-26
---

# ReqWS 回归自动化方案与改造流程

本方案补齐 Electron 真实操作链路，并与现有本机 GoLand 自动化对接，逐项替代重复的 Computer Use 回归。

- 调研日期：2026-09-22。
- 状态：S0–S3 实施依据；隔离 Electron 链路与 CI/候选包入口已实现，实际证据见[实施记录](implementation-2026-09-26.md)。S4 跨进程入口及初始 JPS 修复已通过同候选本机联动和原生落盘门禁，见 [S4 记录](implementation-s4-2026-09-26.md)；V 替代验收仍为后续目标。
- 入库基线：`fredgnr/reqws-desktop`，`main` 提交 `fc7c31a69128039a31c0f0bc9cc0eb368feaca1c`，应用版本 `0.1.6`。初次方案审阅基于 `7f9dc8b3a17d11339cfed5d7d770f90df659fe3b`；本次已核对两提交差异并同步新的插件与 CI 边界。
- 关联工作：PR #20 是早期设计来源；插件兼容性与本机自动化已随 PR #21 合入上述 main。现行执行边界以[插件需求包](../ide-plugin-compatibility-automation/README.md)和[本机集成入口](../ide-plugin-compatibility-automation/local-integration.md)为准，完整 V 验收仍未完成。
- 初次入库仅包含方案与索引；2026-09-26 的 S0–S3 实现单独以分支 diff 和执行报告定位，不改兼容性下限或发布配置，不将本机源码测试当作 CI/精确包证据。

## 1. 目标与非目标

日常迭代的默认流程应是“编写并运行可复现测试、阅读失败证据”，而不是由 Agent 使用 Computer Use 重复点击界面。Playwright 应优先补齐 Electron 真进程的端到端验证；GoLand 原生界面交给 JetBrains Starter/Driver。Computer Use 保留为有明确触发条件的补充，不再作为所有功能变更的默认门槛。

建议将“普通功能迭代默认不使用 Computer Use、同一基线回归场景的人工／Computer Use 成本下降至少 80%”作为改造目标。该比例不是测量结果，必须先登记现有场景与实际使用成本，再按同一口径比较。

不迁移 Electron Forge，不为测试改用 electron-builder；不把 Vitest/JUnit 全量改写为 Playwright；不引入与 macOS Electron 产品无关的三浏览器矩阵；不扩大插件到语言工具链或 IDE 原生 Git 功能测试；不为测试降低 sandbox、IPC 输入校验、路径保护、签名或发布安全要求。[R1][R3][R4]

## 2. 初始基线与主要缺口

| 当前内容 | 已有基础 | 本次应补的缺口 |
|---|---|---|
| Desktop 测试 | Vitest unit/integration；jsdom + Testing Library renderer；Python workflow 回归 | 真实 Renderer → preload → IPC → Main → Git/文件系统 → UI 的自动链路 |
| 进程启动 | `startApplication` 有局部依赖注入，主入口顶层获取 single-instance lock | 在锁和 storage 初始化前完成测试实例隔离；真正的进程冷启动验证 |
| 服务装配 | `createMainServices` 统一装配状态、Git、工作区、更新与 editor；共用 activity gate 和 mutation coordinator | 只在系统边界增加可注入接口，避免成功路径替换核心业务服务 |
| 工作区集成测试 | 已有真实 bare Git fixture、回滚与保留文件检查；部分用例使用内存 state 与测试 writer | 使用真实 AppStateStore、WorkspaceFileWriter 和 UI 操作验证持久化 |
| CI | 已有 impact/docs-only 分流、project-checks、macos-package、固定名称聚合检查，以及 plugin-targets/build/verification | 新增 Electron E2E 与候选包启动证据；保留现有分流；完整 IDE 与 Desktop→IDE 联动仅本机执行 |
| 插件兼容性与 GUI | 最低 262、无上限；最低 SDK GO 2026.2、固定本机 GO 2026.2.1.1、自动 API 矩阵及显式 ZIP 本机入口 | 扩展真实 Desktop 写入驱动的本机联动，不重复搭建 Driver，不把已有正向报告当作完整替代验收 |

以上表格保留初始入库基线的缺口定义，当前实现进度见[实施记录](implementation-2026-09-26.md)。Desktop 源码和已审阅的工作区集成测试在两个初始基线之间没有变化；新增插件基础设施与仍未完成的 V 验收分别记录，不混为一谈。[R1]–[R8][R11][R15]

## 3. 测试分层与真实性边界

### 3.1 保留的底层检查

保留现有 Vitest、Testing Library、Kotlin/JUnit 平台测试、共享 contract fixtures、Plugin Verifier 和 Python workflow 回归。输入组合、schema、安全边界、并发、原子写失败及模型归属规则，尽量在最小可验证层覆盖；E2E 不重复穷举全部组合。

### 3.2 主力新增：Playwright Electron E2E

使用仓库锁定的 Electron runtime，加载实际构建出来的 Main、preload 和 renderer。不是只启动 Vite，然后在独立 Chromium 中挂一个假的 `window.reqws`。

```text
Playwright 的真实 UI 操作
  → React
  → 原有 typed preload
  → 原有 IPC channel 与 Main Zod 校验
  → 原有 services、activity gate、mutation coordinator
  → 真实临时 Git origin / Git 子进程 / 文件系统 / 状态文件
  → 原有进度、结果与错误回传
  → UI 断言 + 进程外磁盘和 Git 断言
```

允许替换的只有明确的系统边界，例如原生文件选择器返回值、启动外部编辑器的 OS 调用、更新网络／安装器、指定写入阶段的一次性故障。成功路径不能将 WorkspaceService、AppStateStore 或整个 preload bridge 换成 fake。

Electron 官方文档提供 Playwright 路线；其支持仍有实验性边界，应先用当前仓库版本做启动及构建产物验证，再固定 Playwright 版本。[O1]

### 3.3 候选 `.app` 冒烟

源码 E2E 验证大部分逻辑；另对同一次构建得到的 `.app` 做少量真实启动、preload、资源加载、设置持久化与退出检查。复用 CI 已构建的候选包，不为了 E2E 再造一份“相似包”。

候选包启动不使用外置测试 Main 替代其真实入口。普通 CI ad-hoc 包、personal-release 签名包和真实自更新的验证结论必须分别记录，不能相互冒充。直接从可执行文件启动也不证明 Finder、隔离属性或系统信任提示已经正常。

### 3.4 GoLand 原生界面

复用现有本机 Starter/Driver，管理隔离 IDE、候选 ZIP、进程及原生 Project/Tool Window。固定 GUI 代表版本沿用 GoLand 2026.2.1.1；更高版本按现行自动 API 验证路线，不增加最高版本 GUI 矩阵。PR、Release、每周和共享工作流均不得启动完整 IDE 或要求 IDE 授权。API 验证成功不等于 UI 通过。[R8][R15][O5][O6]

### 3.5 可选的浏览器 UI／视觉层

后续确有布局、缩放、长文案与视觉回归成本时，再增加少量 Chromium-only renderer 用例。使用假 bridge 的用例必须标记为 UI-only，不计入真实 Electron E2E。优先复用已有 Testing Library；首阶段不建立第二套完整的组件测试工程。

## 4. 具体改造点

### 4.1 启动与数据隔离

当前入口在顶层设置应用名并申请 single-instance lock，随后才初始化 `app.getPath('userData')`。因此，在 `firstWindow()` 之后更改目录已经太晚。[R5]

建议将实例路径配置、锁、生命周期装配整理为可共用的 bootstrap；生产入口使用原默认值，外置测试入口仅提供隔离目录和边界适配器。共用同一真实启动实现，不能在测试中省略锁、IPC 注册或窗口安全安装。

每个测试实例拥有独立的临时根目录，其中分开存放 userData、workspace 根目录、`.code-workspace` 输出目录、Git HOME／配置、origin、日志。设置 userData 必须早于锁与任何存储读取；启动后回读实际路径并验证位于受控根目录，不只假设修改 HOME 或传入 Chromium 参数就足够。

冷启动测试要结束原进程、确认退出，再启动新进程；保留的只是该测试自己的数据。不能用 page.reload 或关闭 macOS 窗口代替完整退出。single-instance 专项测试则显式启动两个共享同一测试 userData 的进程。

候选 `.app` 默认在一次性 CI 用户环境中测试，不使用测试 Main。若要支持开发者本机运行候选包测试，应单独设计并验证受限的数据目录启动配置，或使用一次性账户／VM；在此之前，不得直接启动候选包与用户的真实 ReqWS 争锁。

清理只针对本次 fixture 创建且验证过归属的路径和 PID，不使用全局 `pkill`，不删除真实工作区。

### 4.2 服务注入范围

优先扩展 `src/main/ipc/create-main-services.ts` 的现有装配能力，而不是给 renderer 增加测试专用 IPC。[R6]

| 边界 | 自动测试策略 | 不能宣称的结论 |
|---|---|---|
| Electron native dialog | 选择／取消结果可控，验证正常请求参数和后续处理 | 没有验证系统对话框本身的外观与操作 |
| EditorLauncher 的 OS 启动调用 | 记录可执行文件、参数数组、工作目录；返回成功／失败 | 没有证明实际编辑器成功打开并加载工作区 |
| updater 外部网络／安装器 | 驱动真实更新状态机及 activity gate，覆盖结果与错误 | 没有证明真实签名包已完成升级 |
| 原子写／外部失败 | 只在指定阶段单次报错，验证原有恢复逻辑 | 不是把整条业务路径换成预制返回值 |

Playwright 的网页 dialog/filechooser 事件不处理 Electron 原生 dialog；应在 Main 侧做替换或通过依赖接口提供返回值。[O2]

生产构建中不得包含故障注入实现、任意执行命令接口、裸文件系统 bridge、测试控制 HTTP 服务或可绕过 schema 的总开关。保留真实 activity gate 与 mutation coordinator 的实例共享关系。

### 4.3 Git 测试数据：保留完整生产 URL 校验

现有 bare 仓库 fixture 可以复用，但现有直接 service 测试的本地路径特例不能直接用到 UI E2E：生产新仓库 URL 只接受符合规则的 HTTPS／SSH，GitRunner 又会清理所有继承的 `GIT_*` 变量。[R9][R10]

建议先验证一个 loopback HTTPS Git fixture：

```text
临时 bare 仓库
  → git http-backend
  → 仅监听 loopback 的 HTTPS 测试服务与随机端口
  → UI 输入有效的 HTTPS repository URL
  → 真实生产校验、clone、fetch 和 branch 操作
```

测试生成临时 CA／服务证书，只通过隔离 Git 配置中的 `http.sslCAInfo` 信任，不导入系统钥匙串、不关闭证书验证。Git 的 HTTP backend 与 CA 配置提供所需基础能力；阶段 S0 已以真实 HTTPS fixture 验证装配，范围和候选见[实施记录](implementation-2026-09-26.md)。[O7][O8]

该方案避免依赖外部 GitHub 可用性和用户账号，也避免用 `url.insteadOf` 改写后导致 origin 比对语义变化。不得删除 Git 环境清理规则，或把 `file://`／本地路径放进生产 schema。隔离配置要验证实际生效，并避免继承个人 Git 配置、SSH agent、代理与凭据。普通成功场景仍运行真实 Git。网络故障要在该 Git 传输层制造，不能把 Playwright page.route 或 browser offline 当作 Git 子进程网络拦截。

### 4.4 选择器与等待

优先按 role、label 和用户可见名称定位；动态仓库行、重复按钮及跨语言定位可补稳定 test ID。测试 ID 只定位元素，不代替 UI 可访问性与文本断言。避免坐标、复杂 CSS、`nth()` 拼装业务步骤。[O3]

界面状态用可等待的断言；异步磁盘变化用有超时的轮询；插件用模型与树节点最终条件。禁止为了“等加载”加入固定长 sleep。截图仅辅助视觉与失败诊断，不能代替分支、文件与持久化断言。

## 5. 首批用例范围

下表是业务场景分组，实施时可拆为独立测试；编号是拟议编号，不代表当前存在测试。

| 编号 | 操作 | 必须验证的结果 |
|---|---|---|
| D01 | 全新启动 | 页面 ready、真实 preload bridge 可用、空状态合理；无启动错误 |
| D02 | 设置与语言保存，退出后重启 | 真实 state 持久化；新进程恢复设置；中英文关键页面正常 |
| D03 | 新增／编辑／删除仓库配置及连接测试 | URL 校验和去重、成功／失败反馈、state 实际变化；不使用真实外网账号 |
| D04 | UI 创建双仓 feature workspace | 真实 clone 和目标分支；独立 `.git`；manifest、`.code-workspace`、全局索引一致；目标输出目录生效 |
| D05 | 增加仓库、逻辑移除仓库 | 活跃成员和 workspace 文件更新；被移除目录及用户文件保留 |
| D06 | 创建在正式发布前失败 | staging 清理；不存在成功工作区；错误与后续重试行为正确 |
| D07 | 正式发布后写 workspace 文件／索引失败 | 已发布目录及用户文件保留；没有虚假成功索引；显示恢复信息，不能递归删除公开目录 |
| D08 | GoLand 加载选择 all/subset/empty 与 2→1→0→2 | 独立 binding/selection 与 revision 更新；不意外改动 workspace 成员或删除目录；取消不写入 |
| D09 | GoLand 选择保存失败／revision 冲突 | 不覆盖新版本选择；错误和重试符合 UI 契约；已保存状态不被伪装成功 |
| D10 | 打开编辑器及不可用处理 | 正确入口、参数数组和错误反馈；原生打开证据另行验证 |
| D11 | preload／窗口安全 smoke | 正常 bridge 可用但无裸 Node／文件访问；导航／popup 等仍遵循既有策略 |
| D12 | 更新状态的代表性链路 | 真状态机和忙碌门禁响应正确；不把模拟下载成功当真实更新成功 |

D04 的断言从测试进程读取磁盘、执行独立 Git 检查，不只调用应用自己的 getWorkspace API，也不复用被测 writer 的同一算法计算所有 expected 值。

D06 和 D07 必须区分：现有仓库已经明确“发布前清理临时 staging、发布后保留公开工件”的安全语义。不能以所谓自动化回滚整洁为由改变这一契约。[R7]

复杂并发、路径攻击、权限／symlink 竞争、schema 组合仍优先由现有底层测试穷举，再选择少量 UI/IPC 代表链路。每个错误修复按故障发生层补回归，不要求所有错误都增加完整 Electron 启动。

## 6. Desktop → GoLand 的关键自动验收

这部分扩展已合入 main 的插件自动化，不建立另一套“用 Playwright 测 JetBrains 原生 UI”的框架。现行兼容性保持 `since-build="262"`，不设置 `until-build` 或 `strict-until-build`；`262.*` 只是系列表达。最低系列、编译 SDK、固定本机 GUI 代表版本和 API 目标分别管理；每次插件相关迭代评估最低版本影响，提高下限仍需证据和用户明确批准。[R8][R15]

已有本机用例由测试宿主原子写入选择；本方案新增的是改由真实 Desktop UI 写入，验证两产品进程之间的连接。现有 Project 树、监听与冷启动场景及其报告不需要重新创建，但不能直接当作新增 Playwright 联动通过证据。

建议在本机用测试编排器协调 Playwright 与现有 Driver 宿主，通过临时文件／测试进程通信共享路径和步骤状态，不给产品新增远程控制服务。复用 `scripts/run_local_ide.py` 的授权、profile 锁、候选校验及进程清理，不通过裸 Gradle 命令绕过这些保护。该联动不放入 CI，也不新增工作流授权 Secrets。

S4 实施契约：在现有入口增加显式 `--suite desktop`，默认保留原三组宿主场景。专用 profile 锁覆盖 Playwright 和 Driver 的完整会话；一次性运行目录内的 UUID 会话与递增序号绑定请求/响应，只允许创建固定场景工作区、保存两仓库加载选择和结束 Desktop 会话。Desktop 通过真实 UI 创建 Git 工作区及入口，Driver 只读回查实际 workspace/binding/revision，再观察树与模型。通信不接收任意路径或命令，不进入产品代码。两端超时、异常、跳过、证据缺失或进程异常退出均不能生成通过报告；Desktop fixture 保留在私有运行目录，避免 IDE 尚未退出时删除项目。原宿主与新增联动报告按 suite 区分，旧报告不能代替新联动证据。

完整进程退出后，还须逐个工作区独立读取原生 `modules.xml`、受管 `.iml` 和 journal，核对精确模块登记、最终选择的根及相同所有权 marker；仅从 IDE 缓存恢复的实时模型不能证明持久化成功。报告缺少四组落盘证明时不得复用。

完整场景：

1. Playwright 通过真实 Desktop UI 创建包含 repo1、repo2 的工作区。
2. Desktop 创建 GoLand 入口 `.reqws/ide/goland`，通过真实 UI 保存加载选择。
3. Starter 在隔离配置下安装同一候选插件 ZIP，启动固定 GoLand。
4. Driver 在普通 Project 面板中展开仓库和目录，断言指定普通文本文件可见；同时核对 Workspace Model／ProjectFileIndex 的相应边界。
5. 保持 IDE 打开，在 Desktop 中把选择改为 `2→1→0→2`。依赖真实文件写入、监听及插件同步；不得手动 Refresh、重开项目或调用内部同步函数来掩盖 watcher 问题。
6. 每次检查实际树和模型状态，未选择仓库不属于加载集合，原文件仍在磁盘，非 ReqWS 所有的用户模型数据未受影响。空选择可以保留中性 shell 项目，但不能把它当作加载的仓库。
7. 关闭完整 IDE 进程、确认退出，再用相同测试数据冷启动，验证选择与可见性恢复。

文件是否可见必须观察 Project 树，不用 Find in Files、搜索结果或单独的磁盘存在性代替。

补充专项：未信任项目不得写入受保护模型；完成信任后按契约同步；损坏或不匹配的 binding/manifest 不应产生越界修改。不能全局开启自动信任然后宣称 trust 流程通过。

现有 Kotlin/JUnit4 平台测试、独立 `src/integrationTest/kotlin` 和 JUnit5 宿主均保留；只扩展缺失的联动部分。Driver 依赖不能进入生产插件 JAR。本机报告必须把 IDE 内部异常显式汇总成失败，不能只依赖宿主正常退出；CI 只编译宿主并运行保留的平台/API 层，不启动 IDE 来获取 UI 证据。[R15][O5]

本机 GUI 运行前验证专用 profile、有效授权、图形会话、必要权限和可控退出；准备会话正常结束不代表授权或业务验收通过。保持日常 IDE、真实工作区、账号和授权文件隔离；profile 不上传到 CI，不自动关闭开发者正在使用的 IDE。[R15][O6]

## 7. CI 与缓存

### 7.1 第一阶段只新增，不削弱现有门禁

保留 `Checks and macOS package smoke` 与 `GoLand plugin checks` 的既有 required-check 名称和有效检查。新增测试接入后扩展依赖与聚合条件，不通过改名、条件跳过或仅保留最后一个成功 step 让 PR 假绿。[R2]

在现有 impact/docs-only 分流之上增加 Electron 层，职责如下；本机集成不是 GitHub Actions job：

| 执行域／阶段 | 主要内容 | 初始策略 |
|---|---|---|
| CI impact / docs | 现有影响分类与 docs-only 检查 | 保留已实现分流；不能因新增 E2E 使纯文档触发打包或 IDE |
| CI project-checks | 现有 npm check 与 workflow 回归 | 保留当前行为 |
| CI desktop-e2e（新增） | Electron 真进程核心场景 | macOS，先单 worker，再按实测隔离能力并行 |
| CI macos-package | 现有构建和验证，加同一 `.app` 的小规模启动 smoke | 不重新构建另一份候选包；保持签名 profile 区分 |
| CI plugin-targets / build / verification / goland-plugin | 现有平台测试、结构、同 ZIP API 验证与聚合 | 保留现有配置，不重复创建旧方案中的单 job |
| 本机现有 IDE 自动化 / 新增 Desktop→IDE 联动 | 固定 IDE 原生树、真实 watcher、冷启动 | 复用显式 ZIP 与专用 profile；独立报告，不进入 PR/Release/定期 CI |
| CI required 聚合 | 汇总本次按影响应执行的 CI 项 | 必需项失败／取消／缺报告／空用例不得成功；已判定不适用与本机未运行单独标记 |

现有路径影响选择继续有效；新增 Electron 套件先采用保守影响集合，稳定后再细化。Desktop UI、Main/preload、shared、GoLand selection、构建配置、lockfile、harness 与 workflow 变更都进入分析；未知路径或分析失败不得缩小覆盖。

本机层保留独立的 not-run / environment-blocked / failed / passed 状态；CI 不运行本机层不是一次失败，也不是 UI 成功证据。变更确需本机联动验收时，在候选交接中明确报告或缺口，不把授权搬入 CI 来补报告。

纯文档改动可按仓库现有规范只做文档检查。代码候选仍保留规定的完整基线；只在中间开发检查点运行较小集合。影响选择是测试优化，不是删除正式验收范围。

### 7.2 执行频率

拟议 Electron 套件在相关代码 PR 跑核心 smoke 和受影响扩展；合并后或定期执行更完整的 Electron 故障组合，视觉层仅在建立后运行。IDE 和 Desktop→IDE 联动仍仅在本机获准环境按影响执行。发布增加精确候选包、签名和发布相关验收；不得把定期或旧候选“最近通过”当成本次通过。

更高 IDE API 目标沿用现有冻结清单、同一 ZIP 验证；不恢复已移除的 API 矩阵 `max-parallel` 限制。固定 GUI 代表版本变更时单独评估，不增加“最高 IDE 真机回归”。[R8]

### 7.3 缓存与计算量

沿用已有 Node／Electron／Gradle／IDE 下载缓存治理，不缓存“测试通过”作为执行结果。Electron E2E 使用项目已有 Electron runtime，不默认再下载 Chromium、Firefox、WebKit。只有增加 browser-only 视觉层才考虑对应单浏览器依赖。

候选包使用同 job 或明确 artifact 在检查间传递；日志、trace、截图是短保留期诊断 artifact，不进入依赖缓存，也不提交仓库。不要为每个测试文件重复打包 App 或下载完整 IDE。

不应将现有全量 npm suite 机械迁到 Linux：仓库包含 Darwin 原生安装和签名集成。是否拆出纯跨平台检查，须另以实际依赖与覆盖等价性验证。[R4]

## 8. 证据、失败传播与稳定性

每次候选检查应留下源码身份（PR head 与实际测试 merge commit 如不同则同时记录）、Electron／Playwright／IDE／JDK 版本、OS／架构、构建 profile、真实测试选择器、执行数、失败数及跳过理由。CI 产物关联保存在 CI artifact，本机候选和 UI 结果留在独立私有报告；不把授权 profile 或未经审阅的本机日志上传，也不在文档新增逐文件 checksum 台账。[R3][R15]

Electron 失败证据包括测试断言／步骤报告、Main stdout/stderr、renderer error、截图、trace、脱敏 Git 输出及必要的磁盘前后状态。IDE 加上 idea.log、框架 error 目录、Project 树和模型诊断。测试启动失败也要保留日志，不能仅依赖正常 teardown 才生成证据。

自建 Electron fixture 必须验证真实 context 被 tracing 覆盖，不能只在配置里写一个 `trace` 参数就假设成功。手动 `context.tracing` 不记录 expect 断言，因此还要关联测试报告；它也不能代替 Main 或 Git 子进程日志。[O4]

初始核心套件不设置自动重试，让不稳定性暴露。后续至多增加受控诊断重试，保留首次失败并显式标记 flaky，不采用“重试至通过”。建议在迁移前完成一组独立重复运行和有意失败实验；连续几十次通过只是初步稳定性证据，不代表已经统计证明 99% 可靠。

必须为 CI 编排及本机报告分别增加负向回归：应执行套件匹配为空、全部 skip、缺报告、子进程错误、启动超时或候选不一致，均不能成功；CI 必需依赖取消必须失败，本机 IDE 内部异常必须进入本机失败报告。正常 docs-only 分流和明确不适用项不属于漏跑。沿用 `tests/workflows` 验证状态聚合，同时断言 CI 不启动完整 IDE、不要求 IDE 授权，不只测试 YAML 存在。

视觉基线仅覆盖少数关键页面，固定 OS、窗口尺寸、字体、locale 和测试数据；只屏蔽真正无关动态内容。截图更新须人工审阅，不能在失败重试中自动接受新图。[O9]

## 9. 分阶段实施

2026-09-26 开始在 `feat/playwright-automation` 实施 S0–S3。以下实现约束先行确定，实际执行结果另记，不把开发中状态视为阶段通过：

- 共用 bootstrap 在申请实例锁前设置外置测试入口传入的绝对 userData、sessionData 和 logs 路径；生产入口不读取测试控制变量。测试入口与生产入口共享真实生命周期、服务装配、IPC 和窗口安全。
- 测试只注入 native dialog、editor OS spawn、Git OS spawn 和 updater adapter 边界；Git wrapper 在生产环境清理后设置固定 `GIT_CONFIG_NOSYSTEM=1`，使用独占 HOME/XDG 和 CA，禁用继承凭据、代理及 hooks。URL 校验及 TLS 验证保持原样。
- 源码 E2E 每次重建独占 `.vite/e2e` 下的 Main/preload/built renderer，单 worker、零重试；生产包沿用 Forge。精确包 smoke 只接受 CI 一次性用户环境中的显式 `.app`，不提供本机真实账户启动捷径。
- S3 初步稳定性门槛定为核心 smoke 连续 20 次独立新进程运行、首次失败为零，并完成故意失败及证据完整性检查；此门槛不宣称 99% 可靠，也不代表 S4/V 通过。
- 本次不改插件 API、SDK、schema 或最低版本：仍为整个 262 系列，无兼容上限。S4/V 和旧手工门槛保持待验。

阶段是工作拆分，不要求每阶段单独 PR；可以在一个集成分支交付，最后统一验收。

| 阶段 | 范围 | 完成条件 |
|---|---|---|
| S0：清单与可行性 | 登记现有手工步骤；验证当前 Electron + Playwright、built renderer、sandbox、原生边界、loopback Git 与 macOS runner；对齐现行插件与本机边界 | 能启动、操作、退出；不触碰真实数据；至少一个故意失败能产生完整失败证据；明确 source／packaged 差异 |
| S1：最小基础设施 | bootstrap、受限 DI、fixture 隔离、真实 Git origin、清理与证据；test runner include/exclude | 真实业务服务未被替换；目录与 PID 隔离、异常清理、锁语义通过；生产包不含测试控制逻辑 |
| S2：Desktop 核心链路 | D01–D09 优先，后补 D10–D12 的代表场景；必要底层测试同步补充 | UI 与独立磁盘／Git 断言一致；发布前后失败语义正确；冷进程持久化通过 |
| S3：CI 与候选包 | 新 job、同包 smoke、失败 artifact、聚合门禁与 workflow 负向测试 | 失败／取消／零测试不可假绿；不重复打包；核心套件达到约定的初步稳定性门槛 |
| S4：本机跨进程扩展 | 复用现有 Driver、显式 ZIP、授权/profile 与报告，补 Desktop 真实 UI 驱动；保留树、watcher、trust、冷启动 | 真 UI 与模型证据一致；不手动刷新；同一候选 ZIP；异常传播；CI 不启动 IDE |
| V：替代验收与规范切换 | 核对旧手工步骤到新测试的映射；故意破坏关键环节；修改 AGENTS 与指南 | 对每条撤销的手工门槛，均有范围等价、真实候选、负向失败证明与稳定证据 |

S1 后可并行推进 Desktop 场景、CI／证据、插件测试；共同修改 bootstrap、IPC contract、构建脚本的任务必须先约定接口和文件归属，由主任务统一集成。独立验收任务只读复核候选与证据，不自行放宽门槛。使用 subagent 时遵守仓库现行模型／reasoning 与授权约束，不假设本次已启动任何 subagent。

建议最终顺序：`S0 → S1 → S2/S3` 先建立 Electron 主线；`S4` 在现有本机插件实现上扩展；所有替代项完成 `V` 后再移除对应 Computer Use 要求。

## 10. 未来每次开发的回归流程

```text
判断本次变更涉及的产品边界与现有测试
  → 在最小合适层补一个能发现问题的回归
  → 实现变更并执行直接受影响检查
  → 集成候选执行原质量基线 + 所需 Electron 自动测试；IDE 联动在本机独立执行
  → 先读断言、trace、Main/IDE 日志及磁盘证据定位失败
  → 只对无法被现有自动证据覆盖的具体 OS 风险申请 Computer Use
  → 在 PR 记录实际执行、失败修复、未覆盖项和是否需要原生补验
```

新增“自动化优先”的开发规范，但不要把它写成无条件取消全部 GUI 验收。普通表单、筛选、弹窗、工作区 CRUD、选择切换与错误提示不应每次都回到 Computer Use。

原生补验申请应明确：哪一项断言仍无自动证据、为何现有测试不能证明、使用哪个候选包、哪几个步骤、什么结果算通过。已经被等价自动化覆盖的相同步骤不得重新扩展成全量人工回归。

建议新增或更新的文件（均为拟议路径）：

```text
playwright.config.ts
src/main/bootstrap.ts                         # 名称以最终最小重构为准
src/main/index.ts
src/main/ipc/create-main-services.ts
tests/e2e/fixtures/                           # 实例、Git、native ports、evidence
tests/e2e/desktop/                            # 核心 Electron 场景
tests/e2e/packaged/                           # 精确候选包 smoke
integrations/goland/src/integrationTest/kotlin/ # 扩展现有宿主，不重新搭建
scripts/run_local_ide.py                      # 复用现有本机保护，仅按需扩展
.github/workflows/ci.yml
tests/workflows/
docs/standards/regression-testing.md
docs/changes/playwright-regression-automation/ # 本次仅方案与索引，实施时更新状态
AGENTS.md
docs/guides/development-guide.md
```

已新增 `test:e2e`、`test:e2e:smoke`、`test:e2e:negative`、`test:e2e:packaged`，当前调用方式见[开发指南](../../guides/development-guide.md#2-启动与日常命令)。前三者重建源码产物；packaged 仅消费真实托管 CI 中已有的精确 `.app`，不运行外置测试 Main。插件复用现有 `check:goland:integration` 与 `prepare:goland:authorization`，不以裸 Gradle 入口绕过本机保护。Vitest 与 Playwright discovery 显式分开，TS／ESLint 覆盖测试代码；运行期间不得编辑 trace 所收集的源码，以免归档读到变化中的文件。

## 11. 手工用例替代登记

映射至少包含以下字段：

| 字段 | 要求 |
|---|---|
| 旧用例／步骤 ID | 指向原规范与真实步骤，不只写“工作区功能” |
| 原始断言 | 用户看到什么、磁盘保留什么、哪个 IDE/OS/profile |
| 新自动测试 | 实际文件、完整测试选择器、执行层 |
| 真实性差异 | 是否 stub 了 dialog、launcher、updater，哪些结论尚不能证明 |
| 负向证据 | 对应环节故意破坏后是否失败，或可控错误是否正确捕获 |
| 候选与稳定证据 | 同一源码／包、运行记录、flaky 处理 |
| 迁移决定 | 保留、部分替代或撤销重复手工步骤；批准依据 |

关键负向实验示例：使 preload 断开，D01/D04 必须失败；阻止真实 manifest 写入，D04 必须失败；在隔离开发副本临时破坏 watcher，实时同步用例必须失败；使已发布工件被错误删除，D07 必须失败。实验在一次性副本或测试适配器中完成，不把故障开关发布到产品。

## 12. Computer Use 保留范围与最终标准

默认保留“按风险触发”，不是“每次发布全量重复”：

- 更改原生 dialog/Finder/外部编辑器集成时，对真实 OS 交互补验；不重测已自动覆盖的业务结果。
- 更改签名身份、打包 profile、系统信任路径、安装行为时，对相应真实候选补验。
- 更改 updater 安装／签名验证路径时，进行真实两个版本间更新及重启恢复验收；能脚本化的仍先脚本化，Computer Use 只补剩余系统交互。
- Electron/IDE 大版本或图形行为发生变化、自动化无法判定的窗口／焦点问题，触发有限的探索测试，而不是自动扩展为全矩阵。

Playwright 的 native dialog、sandbox 默认值及调试 fuse 限制需要在 S0 检查。当前 Playwright Electron 文档将 `chromiumSandbox` 默认值列为 false；选定版本的 harness 应显式启用并验证 Chromium sandbox，不能只检查 BrowserWindow 的 `sandbox: true`。同时保持 contextIsolation 与 CSP 策略；不得为了测试连接修改发布包的安全 fuse 或签名配置。无法附加到真实 release 包时，应记录该边界并安排精确包的原生验收，而不是换成测试包宣布 release 通过。[O2]

本改造完成的标准不是“仓库装上 Playwright”，而是：关键场景有真实链路和独立结果断言；失败证据足以远程诊断；自动化能可靠拒绝故意制造的问题；普通迭代无需 Computer Use；例外范围明确；旧手工步骤只在证明等价后退出。

衡量时同时跟踪转换场景数、同一场景的 Computer Use 调用／耗时、首次失败率、flaky 比例、P95 运行成本、零测试误通过次数及人工例外原因。不用截图数量或 mock 用例数量作为替代率。

## 参考资料

仓库依据按初始源码审阅和本次入库复核区分。未变化的 Desktop 源码保留初始固定链接；已更新的 package、CI、AGENTS 与插件边界使用入库基线或库内链接。官方工具文档沿用初次方案来源，实施前仍需用锁定工具版本验证；本次文档入库不宣称运行验证。

### 仓库依据

- [R1：package.json](https://github.com/fredgnr/reqws-desktop/blob/fc7c31a69128039a31c0f0bc9cc0eb368feaca1c/package.json)
- [R2：CI](https://github.com/fredgnr/reqws-desktop/blob/fc7c31a69128039a31c0f0bc9cc0eb368feaca1c/.github/workflows/ci.yml)
- [R3：AGENTS.md](https://github.com/fredgnr/reqws-desktop/blob/fc7c31a69128039a31c0f0bc9cc0eb368feaca1c/AGENTS.md)
- [R4：开发指南](https://github.com/fredgnr/reqws-desktop/blob/7f9dc8b3a17d11339cfed5d7d770f90df659fe3b/docs/guides/development-guide.md)
- [R5：Main 入口](https://github.com/fredgnr/reqws-desktop/blob/7f9dc8b3a17d11339cfed5d7d770f90df659fe3b/src/main/index.ts)
- [R6：服务装配](https://github.com/fredgnr/reqws-desktop/blob/7f9dc8b3a17d11339cfed5d7d770f90df659fe3b/src/main/ipc/create-main-services.ts)
- [R7：工作区集成测试](https://github.com/fredgnr/reqws-desktop/blob/7f9dc8b3a17d11339cfed5d7d770f90df659fe3b/tests/integration/workspace-service.test.ts)
- [R8：当前插件兼容与自动化需求包](../ide-plugin-compatibility-automation/README.md)
- [R9：GitRunner](https://github.com/fredgnr/reqws-desktop/blob/7f9dc8b3a17d11339cfed5d7d770f90df659fe3b/src/main/services/git-runner.ts)
- [R10：Repository URL 策略](https://github.com/fredgnr/reqws-desktop/blob/7f9dc8b3a17d11339cfed5d7d770f90df659fe3b/src/shared/repository-utils.ts)
- [R11：插件构建配置](https://github.com/fredgnr/reqws-desktop/blob/fc7c31a69128039a31c0f0bc9cc0eb368feaca1c/integrations/goland/build.gradle.kts)
- [R12：GoLand workspace/selection 服务](https://github.com/fredgnr/reqws-desktop/blob/7f9dc8b3a17d11339cfed5d7d770f90df659fe3b/src/main/services/goland-workspace-service.ts)
- [R13：窗口与安全配置](https://github.com/fredgnr/reqws-desktop/blob/7f9dc8b3a17d11339cfed5d7d770f90df659fe3b/src/main/create-window.ts)
- [R14：Forge 配置](https://github.com/fredgnr/reqws-desktop/blob/7f9dc8b3a17d11339cfed5d7d770f90df659fe3b/forge.config.ts)

- [R15：当前本机集成入口与报告边界](../ide-plugin-compatibility-automation/local-integration.md)
- [入库基线 PR #21](https://github.com/fredgnr/reqws-desktop/pull/21)

### 官方工具文档

- [O1：Electron Automated Testing](https://www.electronjs.org/docs/latest/tutorial/automated-testing)
- [O2：Playwright Electron API](https://playwright.dev/docs/api/class-electron)
- [O3：Playwright Best Practices](https://playwright.dev/docs/best-practices)
- [O4：Playwright Tracing](https://playwright.dev/docs/api/class-tracing)
- [O5：JetBrains Integration Tests Introduction](https://plugins.jetbrains.com/docs/intellij/integration-tests-intro.html)
- [O6：JetBrains Integration Tests UI Testing](https://plugins.jetbrains.com/docs/intellij/integration-tests-ui.html)
- [O7：Git HTTP Backend](https://git-scm.com/docs/git-http-backend)
- [O8：Git Configuration](https://git-scm.com/docs/git-config)
- [O9：Playwright Visual Comparisons](https://playwright.dev/docs/test-snapshots)
