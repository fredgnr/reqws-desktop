---
title: 本机 IDE 自动集成验证记录
type: test-report
status: active
updated: 2026-09-22
---

# 本机 IDE 自动集成验证记录

本记录承接用户授权继续 IDE 回归后的实际执行；此前[验证记录](verification-2026-09-21.md)中的授权准备阻塞仍是当时的结论。最终宿主的三组自动场景、九个独立进程全部通过，零失败、零跳过；这不等于完整 V 验收或正式签名产物通过。

## 候选与范围

消费提交 `01da8b2` 对应的未签名 `reqws-goland-0.1.5.zip`，位于 `integrations/goland/build/distributions/`。本轮仅修改测试宿主、fixture、启动器和文档，没有重建生产插件；运行前后与此前报告比较同一 ZIP，摘要只保存在私有运行报告中。

最低系列仍为 `262`，无 `until-build` 或 `strict-until-build`；最低编译 SDK、固定 UI 代表 GO 2026.2.1.1 / 262.9437.286、七个 API 目标相互独立。本轮未重跑已完成的生产平台/API 检查，也没有多版本 GUI、远端 CI、正式签名或发布操作。

实际运行命令如下；`JAVA_HOME` 指向专用缓存中官方固定 IDE 的 Java 25 运行时，测试宿主另外隔离自己的 `user.home`：

```bash
env 'ORG_GRADLE_PROJECT_org.jetbrains.intellij.platform.useCacheRedirector=false' \
  JAVA_HOME="$HOME/.reqws-ide-tests/goland-2026.2.1.1/dependencies/builds/GO-262.9437.286/GoLand.app/Contents/jbr/Contents/Home" \
  npm run check:goland:integration -- \
  --profile "$HOME/.reqws-ide-tests/goland-2026.2.1.1" \
  --archive /Users/fred/.codex/worktrees/340d/reqws-desktop/integrations/goland/build/distributions/reqws-goland-0.1.5.zip \
  --version 0.1.5
```

## 环境与修复

使用 Starter/Driver 262.9437.185、官方固定 GoLand 分发及其 Java 25。专用 profile 为 `~/.reqws-ide-tests/goland-2026.2.1.1`；未配置 License Server，测试 IDE 实际显示现有试用剩余 8 天。未代用户登录 JetBrains Account，未导入个人 IDE 设置或复制许可证/令牌。试用可支撑当次运行，不能证明账号登录或长期授权复用。

实际启动修复了以下测试层问题：

- Starter DI 日志对较短调用栈越界；拆出初始化调用层，保留固定依赖版本。
- Starter 关闭启动脚本时反向要求非空命令列表；使用禁止序列化的占位对象满足校验，授权准备仍不创建或执行 playback 脚本。
- 清除 Starter 默认代填的协议接受标志；无关 AI 试用和反馈窗口被关闭，未提交反馈或接受 AI 协议。
- GoLand 自动生成的专用 `config/projects/GoLandWorkspace` 随其他项目状态移入新轮次的私有目录，保留授权 config。
- fixture 的 `MODULE_DIR/..` 在该 IDE 指向 shell 父目录；改为实际 shell URL。Project 树匹配仅去除模块名、绝对路径显示后缀，完整保留仓库/文件名匹配，并检查顶层入口隐藏。
- 普通项目等待服务从 `READING` 完成检测到 `INACTIVE`，再验证无 ReqWS 模块；同样等待真实文件树出现，不直接调用刷新。
- 排除 `ide-starter-junit5` 的全局进程清理扩展，仍用 JUnit 5 执行测试，并保留 Starter 错误上报、本轮进程句柄清理和报告门禁。
- Starter 宿主 JVM 使用本轮 `host-home`，运行前校验实际路径，隔离其 macOS saved-state 清理行为。

早期运行尚未隔离宿主 `user.home`；库可能访问或清理日常 IDE 的 macOS 窗口恢复状态。没有该目录的文件级前后证据，不能宣称这部分状态未被触达。发现后停止受影响运行并修复；日常 GoLand 进程未被关闭/重启，没有对真实工作区执行业务操作。准备窗口还出现 GoLand/JBR 无障碍接口异常，发生在未装 ReqWS 候选的实例，不能归作插件通过证据。

## 执行记录

所有目录均为命令打印的本机私有 `reqws-local-ide-*` 运行目录，原始失败与中断记录保留，不改写为通过。

| 运行目录后缀 | 实际结果 |
|---|---|
| `ntlstbi7`、`_4nn72_q` | 授权宿主分别因调用栈、启动脚本参数失败；未启动完整 IDE。 |
| `aq61_zik` | 授权准备实例正常退出；仅 `preparation-closed`，不是 UI 通过。 |
| `zzodw85k` | 三组均在 Project 树断言失败，三个进程退出；修复 fixture/标签后再运行。 |
| `k7stg446` | 三组、九个进程通过；后续发现的宿主隔离缺口仍需修复后重验。 |
| `llo8qzeh` | 依赖解析阶段主动中止，没有启动 IDE，不计通过。 |
| `866t6x14` | 同 profile 并发调用退出 2 / `PROFILE_BUSY`，未启动额外 IDE。 |
| `q63_lzbj` | 发现宿主 home 隔离缺口后中止，不计通过；确认专用进程退出后保留并移出旧会话标记。 |
| `k6q1ztkq` | 自动刷新、冷启动通过；普通项目仍处于 `READING` 时立即断言而失败，整轮失败。 |
| `8_s7rmrt` | 隔离后的三组、九进程通过；随后补齐普通项目检测等待。 |
| `c8n7yal7` | 最终测试宿主三组、九进程全部通过，零 skipped/failed，进程均按 `started → passed → exited` 收束。 |
| `rgj3ghyb` | 修复后的授权准备入口再次实际启动并正常退出；独立宿主 home 校验通过，仅记 `preparation-closed`，未登录账号。 |

另执行本机启动器测试 8 项、兼容/矩阵/报告契约测试 16 项，均通过。使用真实成功报告的临时副本验证 7 个反例：缺 XML、skipped、选择器缺失、进程缺失、强制清理事件、同版本不同 ZIP、CI API 范围冒充 UI；全部按预期拒绝。原报告和候选未改动，诊断日志为 `/tmp/reqws-local-evidence-negatives.log`。

最终选择器为 `WorkspaceIntegrationTest.loadingAndProjectTree`、`atomicSelectionAutomaticallyRefreshes`、`emptyAndNonemptySurviveColdProcesses`。覆盖真实 Project 树和普通项目、`2 → 1 → 0 → 2` 文件事件自动消费、空/非空选择两轮三进程冷启动，以及模型/PFI、用户 root、磁盘保留和 UI 数量一致性。没有调用直接刷新入口代替文件事件。

文档检查通过（23 个索引、90 个文件），`git diff --check` 通过。生产源码和候选 ZIP 不变，本轮没有把此前的平台/API 结果记为重新执行。

最终报告为私有运行目录 `reqws-local-ide-c8n7yal7/report.json`，实际 IDE 为 GO 2026.2.1.1 / 262.9437.286。`verify-report` 对原候选核对通过；XML、原始树路径及进程记录在同目录，未出现全局进程清理日志。

## 验收边界

本机正向集成与 CI 结果分开；正式签名 ZIP 必须独立绑定证据。未执行 JetBrains Account 交互登录/授权过期恢复，以及 I1–I5 的全部真实故障注入；上述报告反例不冒充完整真实进程反例验收。未触发远端 CI/Release，也没有正式签名候选。保留[实施与验收](implementation-plan.md)的剩余 V 要求和未被验证替代的覆盖，不宣称整个迁移 V 已完成。
