---
title: 语言解耦 V 验收记录（2026-09-19）
type: test-report
status: active
updated: 2026-09-19
---

# 语言解耦 V 验收记录（2026-09-19）

本报告记录 S1/S2 组合候选按[V 执行计划](final-acceptance.md)完成的自动化、兼容、装配和必要真实 GUI 验收证据。

## 1. 当前结论与候选

**V 最终验收通过。** 完整插件自动化、原双版本 Plugin Verifier、候选打包、真实 GoLand 中的必要 GUI 链及同候选项目关闭重开均已有当前候选证据，并完成独立审查。GUI 属于 manifest 边界集成，没有把测试辅助层操作写成 Desktop E2E。S1 的 108 项和 S2 的 85 项是历史阶段集合，没有相加或用作本轮完整套件结果。

- 源码：`70e566dfd69403ca57631af3025c68790ab15745`（`Remove Go compensation scheduling and dependencies`），其父提交为 S1 `8d2561a6b6492f437bad7709b591f72ee8ee3c2d`。
- 起始工作区：干净、detached HEAD。完整检查开始时没有未提交差异；之后只维护本报告、当前说明及相关索引，未改运行代码、依赖或构建输入。
- 本轮未提交差异：验收报告、需求包当前状态及其索引，以及包外开发/用户指南、IDE 插件开发测试规范和插件 README 的当前状态/报告入口；实际文件以 `git diff --name-status HEAD` 与 `git status --short` 为准。没有创建分支、提交、推送、修改 CI 或把生成物纳入变更。
- 原始执行日志与元数据：`/tmp/reqws-v-20260919/`，属于本机测试输出，不提交。报告使用 Git、命令、结果路径和工件入口绑定证据，不记录逐文件或工件摘要表。

## 2. 环境与实际命令

本机为 macOS 15.7.3（24G419）、arm64。使用现有 Homebrew JDK 21.0.12.1，`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`；Gradle Wrapper 9.3.0、IntelliJ Plugin Verifier 1.410 及已有 IDE 缓存均保持不变。平台测试使用既有 GoLand 2026.1.3；兼容矩阵为 GoLand 2026.1.3 和 2026.2。

2026-09-19 00:03:48～00:05:43（Asia/Shanghai）在仓库根目录实际执行：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  npm run check:goland
```

`package.json` 中该入口展开为以下原有命令，没有 `--tests` 过滤器，也没有修改任务或禁用测试：

```bash
./integrations/goland/gradlew -p integrations/goland \
  test verifyForbiddenProductionSymbols verifyPluginProjectConfiguration \
  verifyPluginStructure verifyPlugin
```

完整日志为 `/tmp/reqws-v-20260919/check-goland-full.log`，执行身份、时间及退出码见同目录 `check-full-metadata.json`。退出码 0，`BUILD SUCCESSFUL in 1m 55s`，20 个 actionable tasks 中 7 个实际执行、13 个 UP-TO-DATE；`test`、符号门禁、配置/结构验证、`buildPlugin` 和 `verifyPlugin` 实际执行。生产和测试编译使用相同提交输入的增量结果，完整测试本次重新执行，未复用 S2 局部 XML 作为结果。

首次在受限 sandbox 内执行相同入口时，Gradle 无法写入已有 Wrapper 的 `.zip.lck`，00:03:22 即退出 1，尚未执行测试；保留 `check-goland.log` 和 `check-metadata.json`。后续获得所需运行权限后成功执行上述完整检查，没有清空缓存、下载替代工具链或将首次零执行当作通过。

## 3. 完整自动化与兼容结果

### 3.1 测试范围与计数

本次新生成的 35 个 XML 测试类共执行 **392 项，失败 0、错误 0、跳过 0**。XML 原始时间为 2026-09-18 16:04:37～16:05:11Z，对应 Asia/Shanghai 的 2026-09-19 00:04:37～00:05:11，处于本轮调用内；全类明细与计数见 `/tmp/reqws-v-20260919/test-summary.json`。

| 测试包（前缀 `com.reqws.goland`） | 类数 | 执行数 |
|---|---:|---:|
| 根包：bundle / plugin | 2 | 2 |
| `diagnostics` | 2 | 7 |
| `manifest` | 3 | 33 |
| `persistence` | 1 | 11 |
| `project` | 9 | 124 |
| `projectmodel` | 5 | 82 |
| `sync` | 3 | 40 |
| `ui` | 5 | 50 |
| `vcs` | 3 | 21 |
| `watch` | 2 | 22 |
| 合计 | 35 | 392 |

结果入口为 `integrations/goland/build/test-results/test/TEST-<完整类名>.xml` 和 `integrations/goland/build/reports/tests/test/index.html`。完整 XML 已另存 `/tmp/reqws-v-20260919/test-results/`，Verifier 结果另存同目录 `pluginVerifier/`，避免后续执行覆盖本轮证据；这些本地生成输出不提交。

| 覆盖目标 | 本轮证据与结果 |
|---|---|
| T-01 仓库契约 | PASS：manifest 33 项、service 73 项及 adapter 49 项完整执行，包含普通文本 Git fixture 和 `go.mod` 位置/无效内容对照。 |
| T-02 目录范围 | PASS：planner/model/adapter 全类覆盖添加、移除、重加、受管模型和真实 PFI；保留 notes、非 owned 条目和 retained 数据。 |
| T-03 成功与错误 | PASS：applier 10 项、coordinator 30 项及 UI 全类覆盖 PFI 失败、dirty baseline、同 digest 恢复和通用状态。 |
| T-04 事件与恢复 | PASS：service/tracker/coordinator、watch、debouncer 及生命周期完整保留集合执行；覆盖普通 roots、自事件抑制、合并、取消和恢复。 |
| T-05 只读与保护 | PASS：VCS 21 项、原子状态 11 项及 manifest/ownership/model 安全回归完整执行。 |
| T-06 清理与构建 | PASS：原生产符号门禁及自检、配置/结构验证、双版本 Verifier 通过；实际安装身份见第 4 节。 |
| T-07 必要 GUI | PASS：真实操作链、同候选项目重开、保护复核及独立证据审查完成；见第 5 节。 |

### 3.2 符号、装配与兼容

`verifyForbiddenProductionSymbols` 实际执行且通过，原扫描器自检保留，扫描 45 个 `src/main` 文件和装配 JAR 的 311 个 class。`verifyPluginProjectConfiguration` 与 `verifyPluginStructure` 实际执行且通过。

| 原 Verifier 目标 | Exact build | 结果 | 结果入口（相对 `integrations/goland/build/reports/pluginVerifier/`） |
|---|---|---|---|
| GoLand 2026.1.3 | GO-261.25134.147 | PASS：Compatible | `GO-261.25134.147/report.html` 及该目录 `plugins/com.reqws.workspace/0.1.0/verification-verdict.txt` |
| GoLand 2026.2 | GO-262.8665.270 | PASS：Compatible | `GO-262.8665.270/report.html` 及该目录 `plugins/com.reqws.workspace/0.1.0/verification-verdict.txt` |

Verifier 检查的是本轮 `build/distributions/reqws-goland-0.1.0.zip`，插件 ID `com.reqws.workspace`、版本 `0.1.0`。日志中的 IDE layout 缺失 classpath 警告未改变两项最终 `Compatible` verdict；报告保留原始输出，不将这些警告隐藏或改写为无警告。Verifier 下载插件/依赖量为 0 B。

## 4. GUI 候选与装配状态

2026-09-19 00:06:42～00:06:43（Asia/Shanghai）实际执行显式打包入口：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  npm run package:goland
```

结果 PASS，退出码 0，12 个 actionable tasks 中 2 个实际执行、10 个 UP-TO-DATE。原符号门禁再次实际执行；`buildPlugin` 复用本轮完整检查刚生成、功能输入未变的 ZIP。命令、时间和输出见 `/tmp/reqws-v-20260919/package-metadata.json`、`package-goland.log`。

本轮构建并安装的工件为 `/Users/fred/.codex/worktrees/0f30/reqws-desktop/integrations/goland/build/distributions/reqws-goland-0.1.0.zip`，609385 bytes。包内 `reqws-goland/lib/reqws-goland-0.1.0.jar` 的 descriptor 为 ID `com.reqws.workspace`、版本 `0.1.0`，只声明 platform/goland/vcs 依赖。该 JAR 与当前 composed JAR 字节相同；临时比对保存在 `/tmp/reqws-v-20260919/artifact.json`，具体摘要不抄入报告。工件源码为第 1 节提交；后续文档差异不改变功能输入。

主验证应用为 `/Users/fred/Applications/GoLand.app`，版本 2026.2.1.1、build 262.9437.286，实际 IDE runtime 为 JBR 25.0.3+9-b508.16；此版本与原双版本 Verifier 目标分别记录。安装前实际打开 Installed 设置并搜索 ReqWS，结果为 `Nothing found`，当时没有已安装的 ReqWS 或已验证回滚 ZIP。

安装准备曾受 Computer Use 控制问题阻塞：安装技能限定的旧 `sky` 运行时能打开设置，但随后插件管理按钮多次无响应、坐标操作返回 `noWindowsAvailable`。用户随后已明确允许使用新版 `cua_repl`；首次尝试仍有同样的按钮/坐标问题，于是请求用户仅打开 `Settings → Plugins → Install Plugin from Disk` 文件选择框，不提交安装。

续轮只读检查确认 IDE 已处于 `Choose Plugin File`；主 agent 使用获准的 `cua_repl` 原生路径导航及键盘操作选中本工作树的准确 ZIP，在 `Open` 提交控制前再次临时校验确认工件未变，工具控制阻塞已解除。

在操作点请求并得到用户对该精确 ZIP 安装、必要重启及隔离 fixture 信任的明确确认后，主 agent 点击 `Open` 提交安装。Settings 中实际显示 `ReqWS 0.1.0 Enabled`，点击 `OK` 应用；IDE 动态加载插件，没有要求重启，因此没有执行 IDE 重启。

安装与加载身份 PASS：IDE 日志在 2026-09-19 00:21:47（Asia/Shanghai）记录 `load plugin 'ReqWS' (com.reqws.workspace, 0.1.0)`；安装后的 `/Users/fred/Library/Application Support/JetBrains/GoLand2026.2/plugins/reqws-goland/lib/reqws-goland-0.1.0.jar` 与本轮已验证 JAR 逐字节相同。证据保留于 `/tmp/reqws-v-20260919/install-log-excerpt.txt` 和 `installed-artifact.json`。这建立了源码、打包、安装及加载工件的绑定，仍不替代 V-G 场景结果。

00:22:25 通过 UI Open 打开隔离 workspace 后，Computer Use 读取多次超时。一次有界线程诊断显示原生 `TrustedProjectStartupDialog` 模态循环等待；该快照未发现 `com.reqws` 执行栈、Java BLOCKED 或 deadlock 标记，不能据此解释 Computer Use 超时根因或证明候选打开成功。线程输出为 `/tmp/reqws-v-20260919/goland-open-threads.txt`，过程状态为 `gui-execution.json`。后续信任 UI 恢复可读，主 agent 按既有授权点击 `Trust Project`，父目录信任保持未选，用户随后也确认已信任。

已创建隔离 fixture `/private/tmp/reqws-v-20260919/workspace`，辅助脚本为 `/tmp/reqws-v-20260919/fixture-helper.py`；repo-a/repo-b active、repo-c retained，均由本地 `git init` 建立，只含普通文本；notes 为普通目录，不含 Go 构建文件。保护项包括 `user-hidden` 非 owned exclude、`.idea/vcs.xml`、`.idea/user-sentinel.xml` 以及 inert 旧 ownership JSON/lock。初始保护比对没有变化，三份仓库仍存在；此时尚未打开候选，不能将该基线检查当作 GUI 用户配置保护通过。

辅助脚本通过 `add-c` / `remove-b` / `readd-b` 模拟有效原子 manifest 替换，`check` 比对文本与保护文件；允许 ReqWS/IDE 修改的模型文件另作语义检查。按此辅助层执行属于 manifest 边界集成，不能写成 Desktop 创建/启动 E2E。

### 初次 fixture 错误与重建边界

初次打开暴露测试辅助层构造错误：`.idea/workspace.iml` 使用 `file://$MODULE_DIR$/..`，IDE 对 `.idea` module 的路径解释形成意外父目录 Content Root，Project 树出现第二个父根，ReqWS 报 `PROJECT_MODEL_APPLY_FAILED`。该次准备未满足有效 fixture 前置条件，不统计为有效 V-G1 验收，也不将其写成候选功能通过。

主 agent 通过 UI `File → Close Project` 关闭 fixture（无未保存内容），保存 `/tmp/reqws-v-20260919/gui-checkpoints/G1-invalid-fixture/` 和 `fixture-correction.json`。在关闭状态将 content root / `user-hidden` exclude 改为隔离 workspace 的绝对路径，恢复 fixture 的 modules/VCS 初始内容；错误基线保留在 `fixture-baseline-invalid/`，有效基线重建在 `fixture-baseline/workspace/`。初次打开时 VCS 格式及自动探测变化来自 IDE 行为，现有证据不能归因为插件写入。

修正仅涉及一次性 fixture 的 `.idea/workspace.iml`、`.idea/modules.xml` 和 `.idea/vcs.xml`，源码、依赖、JAR/ZIP 均未变；既有自动化与兼容证据仍绑定相同候选。真实 GUI 已从修正后的 V-G1 重新开始，旧错误 fixture 的结果不沿用。

## 5. 必要 GUI 与条件扩展

| 场景 | 状态 | 当前证据 / 缺口 |
|---|---|---|
| 安装与加载身份前置 | PASS | 用户精确确认后已安装并动态加载；Installed UI、IDE 日志和已安装 JAR 字节比对共同绑定工件。 |
| V-G1 打开有效 workspace | PASS | 00:28:45 截图显示 Tool Window 可用，a/b Active，c 不在活动列表且 Project 树排除；notes 保留，user-hidden 仍排除。因保留 c 的既有 VCS mapping 显示 Partially Available / `VCS_CONFIGURATION_MISMATCH`，不是模型/PFI 失败。证据：`G1-open`。 |
| V-G2 自动添加 repo-c | PASS | 辅助层原子替换后自动出现 3 个 Active，00:29:13 显示 Synced；没有点击 Sync Now。证据：`G2-add-c`。 |
| V-G3 自动逻辑移除 repo-b | PASS | 00:29:40 自动只显示 a/c，Project 树 b 排除；三份仓库及文本仍在。保留 b 的既有 VCS mapping 产生预期只读诊断 Partially Available，没有用手动同步覆盖自动结果。证据：`G3-remove-b`。 |
| V-G4 自动重加 repo-b | PASS | 00:29:51 自动恢复 a/b/c、Synced，至 00:30:31 的约 40 秒观察窗口内没有持续同步循环；G4 检查点保护文件无字节变化。证据：`G4-readd-b`、`G4-settled`。 |
| V-G5 单独一次 Sync Now | PASS | 自动链完成后仅点击一次，00:30:37 保持 Synced 及相同活动集合；G4/G5 同 digest，未出现 Go 错误，保护文件无字节变化。证据：`G5-sync-now`。 |
| 同候选关闭重开 | PASS | S2 roots 调度条件项已执行：00:32:34 重开显示 Synced、a/b/c、同 digest；关闭保存时的 URL 宏、VCS 及布局文件变化已作语义与保护复核，原用户条目/文件保留。证据：`G6-closed`、`G6-reopened` 及下述复核。 |
| 文档与差异检查 | PASS（当前文档） | `npm run docs:check` 通过（19 indexes、65 files），`git diff --check` 通过；后续报告改动后再核对。 |

真实 GUI 截图从 Computer Use 原输出原样保存到 `/tmp/reqws-v-20260919/gui-evidence/`，`screenshots.json` 记录原路径与场景映射；表中证据名对应该目录的 `.jpeg`。文件、manifest 和模型检查点位于 `/tmp/reqws-v-20260919/gui-checkpoints/<场景>/checkpoint.json`。成员变化全部来自测试辅助层的有效原子 manifest 替换，属于 manifest 边界集成；没有执行或宣称 Desktop 创建/启动 E2E。

G1～G5 的 `protectedByteChanges` 均为空，原 non-owned exclude、order entries、content 与 module entries 均保持；repo-a/b/c 全程保留。独立审查确认 G4/G5/G6 的 manifest 与 owned state 字节一致、generation 保持 3，支持手动重读和项目重开的幂等结果。场景内的目录范围通过真实 Project 树观察，精确 Workspace Model/PFI 断言由本轮完整 adapter 平台测试补充。自动链没有手动同步，G5 是唯一一次 Sync Now，避免手动动作掩盖自动刷新。

G6 的原始检查点保留了两类需要解释的差异，没有把检查工具的原始 `false` 改写为通过：关闭保存把绝对 content/exclude URL 规范化为 `$MODULE_DIR$` 宏，导致字面比较报告原 content/exclude 未保留；同时 `.idea/vcs.xml` 与 `.idea/workspace.xml` 字节改变。派生复核 `/tmp/reqws-v-20260919/gui-configuration-review.json` 展开 URL 宏后确认原 content、user-hidden exclude、order entries 及 module entries 全部保留；11 个明确保护文件（notes、user-hidden 文本、user sentinel、inert ownership JSON/lock 和三仓库 README/marker）均逐字节相同。

`workspace.xml` 为 IDE 布局/会话状态，不是本 fixture 的用户保护 sentinel。VCS 最终保留原 a/b/c，新增 3 个指向 fixture-baseline 的外部 roots；最终 6 条 mapping 集合与初次错误父目录 fixture 中的原生探测集合相同，没有删除原 mapping。独立审查进一步找到 IDE 原生 `VcsRootErrorsHandler` 的直接日志：00:26:14.594 和有效 G1 的 00:28:20.631 均记录自动注册这 3 个 roots，后一条发生于 ReqWS 模型更新的 00:28:24 之前。证据为 `/tmp/reqws-v-20260919/native-vcs-attribution.log`；结论是原生自动探测注册并在项目关闭时落盘，不能宣称 VCS 字节始终未变化，也不能仅将其描述为缓存 flush。原始检查点、派生语义结果和直接归因日志均保留。

Desktop/shared 本轮未修改，完整 Desktop 业务、真实用户 workspace、语言 SDK/编译/run/debug、原生 Git 全功能及额外布局/无障碍矩阵为 OUT OF SCOPE。没有以此删减 V 的必要 GUI 链；是否存在特定版本/资源风险，应由实际运行结果决定后续有界验证。

## 6. 最终审计与交接

独立审查核对了完整测试集合与实际计数、双版本 Verifier、源码/工件/安装身份、G1～G6 原始截图及检查点、11 个保护文件、原用户模型与 VCS mapping 的保留，以及原生 VCS 变化归因；未发现本候选的验收失败或必需证据缺口。

| V 要求 | 最终结果 |
|---|---|
| 候选及输入 | PASS：`70e566d` 的运行代码/构建输入未改；本轮仅有文档差异，最终再次核对已安装工件与已验证 JAR 相同。 |
| 完整自动化及配置/结构/符号门禁 | PASS：未过滤的 392 项零失败/错误/跳过，原门禁及自检真实执行。 |
| 原双版本兼容及显式打包 | PASS：2026.1.3、2026.2 均 Compatible；`package:goland` 成功且绑定同一 ZIP。 |
| 精确安装与真实 GUI | PASS：用户确认后动态安装；原子 manifest 添加/移除/重加先观察自动结果，再执行唯一一次 Sync Now。 |
| roots 调度条件项 | PASS：同候选执行一次项目 `Close Project` /重开，集合、范围和恢复正确；没有因阶段数量额外重启或重新安装 IDE。 |
| 文档、历史和生成物 | PASS：本报告及最近索引/当前说明更新，阶段和旧工件历史保留，文档/差异检查通过；生成物不提交。 |
| 额外版本 GUI、布局/可访问性或规模扩展 | OUT OF SCOPE：没有对应代码变更或本轮具体风险；原双版本 Verifier 及必要主版本 GUI 已完成。 |

已解决的执行问题包括初次 Wrapper 写权限、Computer Use 安装/信任导航和首次 fixture content root 构造错误；保留原始失败准备记录，修正 fixture 后重做了完整有效 GUI 链，源码和工件未发生改变。没有候选代码修复，也没有沿用无效 fixture 的通过结论。

本轮必需项无剩余 BLOCKED 或 NOT RUN。真实 Desktop 创建/启动、用户 workspace 和语言工具链测试保持 OUT OF SCOPE。验收通过不等于已发布：没有提交本轮文档、推送、合并、tag 或发布；已安装的验证插件与隔离 fixture 保留在本机供复核。
