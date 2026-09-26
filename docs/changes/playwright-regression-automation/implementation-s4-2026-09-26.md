---
title: S4 Desktop 与 GoLand 本机联动实施记录
type: test-report
status: active
updated: 2026-09-26
---

# S4 Desktop 与 GoLand 本机联动实施记录

本记录区分 S4 联动实现、允许的本机检查和尚待获准运行的完整 IDE 验收。

## 1. 范围与当前结论

基于 `feat/playwright-automation` 的 `4f5ff89` 扩展 S4；原 S0–S3 证据仍见[既有记录](implementation-2026-09-26.md)。本次不改生产 Desktop/插件行为、manifest schema、SDK、发布配置或兼容性下限：仍支持整个 `262` 系列，无上限；完整 GUI 代表仍为 GO 2026.2.1.1 / 262.9437.286。

**S4 代码已实现，完整 IDE 联动尚未运行，不能判定 S4 验收通过。** 尚未启动专用 IDE、安装候选、操作授权或真实用户工作区。V 和旧手工门槛保持原状；不把编译、Electron 协议验证、CI 或旧 ZIP 的本机结果作为本轮联动通过证据。

## 2. 实现与真实性

现有 [run_local_ide.py](../../../scripts/run_local_ide.py) 增加 `run --suite desktop`，对应 `npm run check:goland:desktop`；默认 `legacy` 三组场景及九进程要求保留。profile 独占锁覆盖两端，本轮目录、显式 ZIP、授权准备、固定 SDK、退出检查和报告复用原入口。新增套件要求冻结干净 Desktop commit，前后核对 Git 身份；验证报告同时核对该源码和精确 ZIP。

- [Desktop spec](../../../tests/e2e/local-ide/desktop-link.spec.ts) 使用实际构建的 Electron、preload、renderer、真实 Main 服务和隔离 HTTPS Git。通过 UI 创建四个工作区、GoLand 入口及加载选择；普通用户文件与原生用户模块仅用于保留断言。
- [文件协议](../../../tests/e2e/fixtures/desktop-link.ts)及 [Driver 消费者](../../../integrations/goland/src/integrationTest/kotlin/com/reqws/goland/DesktopLink.kt) 只接受固定场景名和两仓库选择。会话 UUID、序号、原子不可覆盖消息、路径/目录身份及独立文件回读约束跨进程关系；产品无新增控制端点。
- [Driver 场景](../../../integrations/goland/src/integrationTest/kotlin/com/reqws/goland/DesktopWorkspaceIntegrationTest.kt) 要求 `desktopSelectionAndColdProcesses`、`desktopTrustTransitionUsesRealUi`、`desktopInvalidInputsPreserveUserModel` 三项全部执行，合计六个独立 IDE 进程、二十条投影证据。
- 成功路径由 Desktop UI 唯一写入业务文件。Driver 核对 workspace/binding/revision、从真实输入计算的 digest、加载 IDs、模块根、PFI、普通 Project 树和用户内容；不调用 Refresh、Sync Now 或内部同步函数。故障专项仅在本轮固定文件注入坏 JSON/身份冲突，恢复原字节后检查真实 watcher 恢复。
- Trust 专项通过真实 Safe Mode 和 Trust Project 对话框，不预信任该项目，禁用全局 trust bypass，并只读核对 IDE trust 状态。它不代替授权登录。
- [协调器](../../../scripts/desktop_ide.py) 与[报告门禁](../../../scripts/check_ide_test_reports.py) 拒绝失败、取消、超时、空/skip/重复测试、缺步骤、错源码/ZIP、树/PFI不一致或异常进程退出。两端退出无法确认时保留 active-session；Desktop fixture 保留在私有报告目录，避免提前删除 IDE 使用中的文件。

CI 继续只编译宿主并运行平台/API 检查；无工作流完整 IDE、许可 Secret 或生产 Driver 依赖。报告和 profile 不提交、不上传；原始私有诊断分享前需单独审阅。

## 3. 已执行检查

当前实际结果：

| 检查 | 结果与范围 |
|---|---|
| `compileIntegrationTestKotlin` | 通过；Java 25，仅编译，无 IDE 启动。 |
| `npm run check` | 518 通过、1 项原有 hosted-only 系统信任检查跳过，共 50 个测试文件；类型、lint、i18n 和当时文档检查通过。跳过不计作通过。 |
| 新增 TS 协议安全检查 | 6 项，涵盖不可覆盖、会话/序号、symlink/大小、受限命令、abort/超时和私有进程 registry。 |
| Python 本机协议/报告检查 | 7 项，覆盖丢失/错误证据、实时与冷启动步骤、旧 suite、同 ZIP 不同 Desktop 源码、两端清理和 profile 保留。 |
| Desktop 协议单独试跑 | 实际 UI 完成 9 个创建/保存请求及 1 个结束请求；Playwright 1/1，通过，零重试。仅验证 Desktop 端，无 IDE 启动，`scope=desktop-link-protocol-only`。 |
| `npm run test:e2e` | 18/18 通过，零跳过/重试；覆盖本次修改涉及的共享 fixture、进程登记与退出。 |
| `npm run test:e2e:negative` | 两项预定真实启动故障均准确失败并留下完整新鲜证据，严格外层门禁通过；无吞掉或改标 expected-failure。 |
| Python workflows | 184/184 通过；使用已有 OpenSSL 3 与 Java 25。首次受限网络下载失败、第二轮系统 LibreSSL 参数不兼容；修正执行环境后完整重跑，不修改或跳过测试。 |
| 插件 baseline | 366 执行，零跳过、零失败；保留生产/测试/宿主编译、Light/Heavy、禁用 API 与结构门禁。 |
| `npm run docs:check` | 通过，26 个索引、111 个文档。 |

协议首轮试跑因旧 registry 仅允许仓库 `test-results` 路径而失败。修复为本轮 session 绑定的固定私有 registry，并传递给外置测试 Main 后重跑通过；未放宽生产 Git、路径或 sandbox 保护。失败记录与成功 trace/截图、真实磁盘、子进程证据分别保留于本机私有输出。

独立只读审查发现“同 ZIP 可借用不同 Desktop 源码的旧报告”；已增加干净 Git 候选的运行前后校验、verify-report 校验和拒绝测试。审查者只读，未运行 IDE；其结论不代替实际执行。

插件平台/API 检查在本轮交接前单独汇总；未完成结果不预记为通过。

## 4. 待执行与保留范围

完整 S4 入口须对准备好的同一 ZIP 和冻结 Desktop commit，在用户获准的专用 profile 上执行。实际授权窗口、图形权限、Safe Mode/Trust 对话框、树、真实 watcher 和六个进程退出均以该次结果为准；遇需用户协助事项停止等待回复。

原三组 `legacy` 宿主在本次抽取公共 host 后仅完成编译，不能借用 2026-09-22 结果声称新宿主 UI 已通过。V 后续仍需逐项核对[旧步骤登记](manual-inventory.md)，特别是 Excluded Files 两态、late-shell、额外 repo3 用户覆盖、完整负向破坏实验及成本/稳定性口径；本轮不撤销其要求。

执行参数、私有报告与 `verify-report --suite desktop` 的使用见[本机入口](../ide-plugin-compatibility-automation/local-integration.md#desktop-真实-ui-联动)。
