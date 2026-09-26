---
title: 本机完整 IDE 自动集成入口
type: guide
status: active
updated: 2026-09-26
---

# 本机完整 IDE 自动集成入口

本入口在本机固定 GoLand 2026.2.1.1 上自动验证候选 ZIP 的 Project 树、真实自动刷新和冷启动恢复，并将授权准备、CI 结果与本机运行结果分开。

开发后的检查结果见[验证记录](verification-2026-09-21.md)。后续获准执行的固定代表环境正向集成及宿主隔离修复见[本机回归记录](local-verification-2026-09-22.md)；JetBrains Account 登录与长期授权复用仍未验证。技术边界见[改造方案](technical-design.md)，实现状态见[开发记录](implementation-2026-09-21.md)。

## 1. 执行范围

CI 继续运行最低 SDK 的生产/测试编译及 Starter/Driver 宿主编译、单元测试、Light/Heavy 平台测试（包括 `HeavyPlatformTestCase`）、禁用 API、结构/产物策略和跨版本 Verifier。PR、Release、定期以及共享工作流均不启动完整 GoLand，不要求 IDE 授权或 License Server。

仅本机入口调用 Starter/Driver；三个场景组保持自动执行，不改成人工逐步回归。更高版本只增加 API 目标，不增加本机 GUI 矩阵。最低 SDK 是 GO 2026.2，代表环境是 GO 2026.2.1.1，API 集合仍从正式目录冻结，三者相互独立。

## 2. 准备专用授权环境

先将 `JAVA_HOME` 指向可用的 Java 25 运行时目录；读取现有 JBR 作为 Gradle 运行时不会启动日常 IDE。在仓库根目录运行，第一次给出一个空的、持久的专用目录：

```bash
npm run prepare:goland:authorization -- \
  --profile "$HOME/.reqws-ide-tests/goland-2026.2.1.1"
```

入口拒绝 CI、已有非空未标记目录、用户 symlink 和并发 profile 使用。它不传入业务项目，打开固定代表版本的专用测试环境；GoLand 可能在专用 config 下自动生成 `projects/GoLandWorkspace` 欢迎工作区，该目录同样在下轮隔离。用户可在 GoLand 的 Manage Subscriptions / JetBrains Account 界面自行登录，准备所需 macOS 图形自动化权限，然后**正常退出本次测试 IDE**。不操作日常 IDE，不打开真实项目，不导入或同步个人 IDE 设置。Starter 使用专用下载/运行目录；首次依赖下载可能需要网络，不把测试环境下载视作系统应用安装。

`JETBRAINS_LICENSE_SERVER` 可选；已有获授权的 Floating License Server / License Vault 时可在本机环境中配置无内嵌凭据、无 query token 的 HTTPS URL。没有该变量不阻止 JetBrains Account 交互登录。脚本不接收账号密码或 Token，不复制日常 GoLand 的许可证、账号数据或配置，不假定日常登录会被测试环境继承。[GoLand 官方注册说明](https://www.jetbrains.com/help/go/register.html)

专用 `profile/config` 由 IDE 直接读写并持续保留授权状态，不把授权文件复制到各轮 sandbox。准备会话正常退出只记录 `preparation-closed` / 授权状态未验证；即使用户关闭窗口而没有完成登录，也不能据此认定后续集成已具备授权。

## 3. 对指定 ZIP 执行自动集成

准备好候选后，显式传入 ZIP 路径和其内嵌版本：

```bash
npm run check:goland:integration -- \
  --profile "$HOME/.reqws-ide-tests/goland-2026.2.1.1" \
  --archive /absolute/path/to/candidate.zip \
  --version 0.1.5
```

该入口只编译/运行测试宿主，**不调用 `buildPlugin`、不重建或重打包生产候选**。脚本在启动前验证 ZIP 元数据及摘要；IDE 加载后核对实际插件版本，退出后再次比较同一 ZIP。正式签名产物必须使用最终签名 ZIP，签名前字节的报告不能借用。

业务执行仍由自动化完成：

1. 加载两仓库并检查真实 Project 树与 probe 文件、入口隐藏；另开普通项目检查无 ReqWS 写入。
2. 测试宿主原子写入选择，自动完成 `2 → 1 → 0 → 2`；等待本次 revision、live digest、模型/PFI、树和数量，不直接 refresh。
3. 完整退出并以新 PID 冷启动，检查空/非空恢复和用户 root；冷启动组重复两次。完整套件要求九个不同进程正常退出。

### Desktop 真实 UI 联动

S4 增加独立套件，沿用同一 profile 锁、候选校验和退出保护：

```bash
npm run check:goland:desktop -- \
  --profile "$HOME/.reqws-ide-tests/goland-2026.2.1.1" \
  --archive /absolute/path/to/candidate.zip \
  --version 0.1.7
```

等价入口为 `python3 scripts/run_local_ide.py run --suite desktop ...`。先提交并冻结干净的 Desktop Git 候选；入口在运行前后核对同一 commit 与干净状态，再构建该源码的隔离 Electron 入口并运行真实 UI。插件 ZIP 仍由调用方显式提供，不重建。不要同时编辑源码或运行会覆盖 `.vite/e2e` 的另一轮 Electron 测试。该入口已通过同候选完整实跑；历史失败、修复和验证边界见 [S4 记录](../playwright-regression-automation/implementation-s4-2026-09-26.md)。

Driver 通过本轮 UUID 和递增序号请求 Desktop 创建四个普通文本 Git 工作区及保存加载选择。Desktop UI 是成功路径上 manifest/binding 的唯一 writer，Driver 独立回读文件并观察普通 Project 树、模块根、ProjectFileIndex、加载数量和 live digest。三个场景组要求六个独立、正常退出的 IDE 进程和二十条逐步投影证据：

- `desktopSelectionAndColdProcesses`：保持 IDE 打开执行 `2→1→0→2`，再验证空集合及非空集合的完整进程冷启动，保留用户 root 和磁盘文件。
- `desktopTrustTransitionUsesRealUi`：实际选择 Safe Mode，确认 ReqWS 未写保护模型，再通过 IDE 信任对话框恢复；不全局自动信任，不调用信任 setter 或同步函数。固定 IDE 的可选 bundled Go Linter 在未信任启动时报错，故仅该 context 通过公开 Starter 配置和本轮临时 `disabled.plugins.file.path` 禁用 `com.ypwang.plugin.go-linter`，记录在 `trust-scenario-options.json` 并回查未加载；不修改持久 profile 插件设置，全部 IDE 错误仍导致失败。Trust 结果须保留此环境限定。
- `desktopInvalidInputsPreserveUserModel`：在本轮自建 fixture 对 binding 和 manifest 分别注入损坏 JSON/身份不匹配，检查真实 watcher 报错、模型保留和恢复。故障文件写入不计作 Desktop 成功链路。

临时通信不暴露在产品中，也不接收任意路径或命令。任一端失败、取消、超时、skip、缺证据或异常退出都阻止通过。Desktop 项目保留在私有运行目录，不在 IDE 退出状态未确认时删除；两端退出确认不足则保留 active-session 标记。报告的 `suite=desktop` 与原 `suite=legacy` 区分，Desktop 源码身份与插件 ZIP 身份分别记录。只跑 Desktop 协议检查、编译或旧三组套件都不代表联动通过。

退出后还须有四个工作区的原生模型落盘证明。门禁只读核对 `modules.xml`、精确 `.iml` 根与 journal 的 companion marker，拒绝仅 IDE 缓存成功而磁盘未保存的结果。`savedProjectionProofs=4` 是新增报告的必需证据，旧报告不可复用。

Starter 的 ZIP reader 需要可写句柄，解包失败时还可能删除输入。因此两个 suite 都先将显式候选按原字节复制到本轮私有 `candidate/plugin.zip`（0600），只把该副本交给 Starter；调用方原 ZIP 可以保持只读。报告分别记录原件和安装副本，安装前后及通过前检查摘要一致，副本丢失/被改写均失败；不重建、重签或修改原候选。

## 4. 授权复用与业务状态隔离

用户态配置仅复用专用授权 config。授权准备不注入“已接受协议”标志，首次协议由用户在专用环境自行处理。有效试用可作为当次运行的实际授权状态，但不能证明 JetBrains Account 登录或后续授权复用。

profile 下另缓存固定版本的官方 installer/SDK 与测试依赖，使专用 IDE 的二进制路径稳定，便于准备 macOS 权限；该缓存不含业务 fixture 或项目/system 状态，也不引用日常 IDE 安装。每轮创建新的 `reqws-local-ide-*` 私有目录，fixture、`.idea`、system、实际安装的 plugins、日志、XML 全部隔离，不能恢复上轮 sandbox。通过 IDE 运行时属性核对实际 config/system/plugins 位置。

每个 Starter context 优先通过公开安装器接口复用专用缓存中精确的 `GO-262.9437.286`，核对 ProductInfo、build.txt、路径与工厂返回身份，重新创建独立的运行描述。仅缓存路径确实不存在时才下载；现有缓存损坏、链接或身份不符直接失败，不替换缓存。拒绝 JBR override 和工厂可能自动恢复的 JBR backup，避免复用时改写测试运行时。该路径不需要每个场景重复查询在线发布目录，不改变最低 262 或完整 IDE 的执行边界。

Starter 宿主 JVM 的 `user.home` 指向本轮独立 `host-home`，启动时核对路径，防止第三方库访问日常 IDE 的 macOS saved-state。宿主不加载带通用进程扫描/清理逻辑的 `ide-starter-junit5` 扩展；仍使用 JUnit 5 执行用例，由本轮进程句柄负责退出及异常清理。

profile 独占锁覆盖整个准备/集成会话。启动前将专用 config 的 `workspace`、IDE 自动生成的 `projects`、`recentProjects.xml`、`recentProjectDirectories.xml`、`trusted-paths.xml` 移到该轮 `private-previous-project-state`，并设置不自动重开上轮项目；这些都是专用测试配置中的项目记录，不读取、移动或清理授权文件。冷启动只在同一轮保留对应 fixture 的模型状态。

profile 和私有项目状态不上传，不纳入 CI cache 或报告附件。脚本不自动删除用户工作区、不关闭或强杀日常 IDE；异常清理仅限本轮宿主创建的进程。未确认正常清理时保留 `profile/active-session.json` 并阻止下轮修改项目记录。应先检查其中指向的私有运行目录、确认并正常退出该专用实例，再明确清除这一会话标记；不得通过删除 IDE 原生锁或关闭日常 IDE 来重试。

## 5. 报告、阻塞与同产物核对

命令打印本轮 `report.json` 路径。报告记录候选 ZIP 路径/摘要/版本、固定目标和实际 SDK 产品/版本/build、测试结果/选择器、进程数量、退出码及状态；完整 XML、host 日志和 Starter 诊断留在同一私有运行目录。原始本机日志可能包含环境信息，分享前单独审阅，不打包专用 profile。

| 状态 | 含义 |
|---|---|
| `not-run` | 已建立运行记录但尚未执行；被中断的初始记录不能算通过。 |
| `environment-blocked` | 缺授权准备/出现授权窗口、图形会话不可用、权限拒绝、profile 忙或 IDE/Driver 启动未确认。 |
| `failed` | 宿主/断言失败、缺或跳过 XML、进程退出异常、候选变化或证据不一致。 |
| `passed` | 仅该 ZIP、该代表 IDE 的完整自动场景结果；不代表多版本 GUI 或发布授权。 |
| `preparation-closed` | 授权准备会话已正常退出，独立于 UI 集成结果。 |

可只读核对一份本机报告是否属于目标 ZIP，不启动 IDE：

```bash
python3 scripts/run_local_ide.py verify-report \
  --report /absolute/path/to/report.json \
  --archive /absolute/path/to/final-signed-plugin.zip \
  --version 0.1.5
```

核对 S4 联动结果时增加 `--suite desktop`，并处于报告对应的干净 Desktop commit；相同 ZIP 但 Desktop 源码变化也会被拒绝。未指定时只验证原 `legacy` 套件，旧报告不能用于证明新增联动。

CI artifact 标记 `scope=ci-api`，本机报告标记 `scope=local-ide-integration`。CI 的 `localIntegration.status=not-run` 只表示 CI 未运行该层，不读取或覆盖用户的本机结果。Release 继续校验最终签名 ZIP 的 API 证据；CI 绿色、UI job 不存在或旧 skipped 都不能被表述为本机 UI 已通过。
