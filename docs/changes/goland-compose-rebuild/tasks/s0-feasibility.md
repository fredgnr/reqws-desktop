---
title: S0 Compose 宿主与自动化技术验证
type: technical-design
status: draft
updated: 2026-09-26
---

# S0：先验证宿主与自动化路径

本阶段用最小真实内容验证依赖、输入和资源管理，避免先写完整界面再发现无法集成或回归。

## 输入和边界

输入为固定实施 head、当前工具链/SDK 政策、现有 Panel 与测试、[技术方案](../technical-design.md)及[测试方案](../test-plan.md)。本阶段只能在后续实施获准时执行；当前文档 PR 不构建或启动 IDE。

Main 独占 Gradle、settings、descriptor、SDK 策略和最小宿主入口；Explorer 仅读资料和测试，Reviewer 仅读最终验证结果。测试使用隔离 fixture/profile；不得写用户工作区或安装日常 IDE。

## 子任务

| ID | 内容 | 输出与直接验证 |
|---|---|---|
| S0.1 | 固定源码与 SDK/JBR/Kotlin，确认 bundled 模块及公开 API；列出旧测试保护意图。 | 精确版本记录、源码/API 证据、旧用例迁移表、Swing 性能基线方法。 |
| S0.2 | 接入 compiler 与宿主依赖，完成最小 Jewel Tool Window；隔离生产和测试 runtime。 | 编译、Verifier、ZIP 检查与依赖/产物负例；对应 V01/V02。 |
| S0.3 | 打通一次真实 Compose 点击及焦点路径、内容重建、一个生产组件的独立语义测试。 | 本机 exact ZIP 输入证据、listener/disposal 观察、匹配 JBR/图形环境说明；V03。 |
| S0.4 | 对比未改变业务前提下的基线，冻结后续测量预算并由 Reviewer 审查继续条件。 | G0 结论、未解决项、依赖/SDK 最终选择、旧覆盖迁移清单；V11 基线。 |

S0.1 的类型调查和测试清单可由两个 Explorer 独立只读；生产写入串行。最小屏幕仅验证技术，不建立第二套长期产品 UI 或可发布回退开关。

## G0 通过条件

V01/V02 检查有效且负例会失败；V03 在真实 IDE 中操作到实际 Compose 控件并观察副作用；独立组件测试运行非零用例；明确内容生命周期与产物隔离；性能基线与预算可复现。一次静态截图、服务直调或 `buildPlugin` 成功均不足以通过。

若所需接口触犯现有生产 API 约束，优先采用公开接口或调整被支持的 SDK，而非加 ignore/suppression。若依赖、真实输入、平台 API 或必需环境仍阻塞，标记 G0 blocked，停止下一阶段生产迁移，保留现有 UI 和检查；允许继续只读调查。

## 交接

交接固定 head、工具链/依赖解析、真实命令与用例数、ZIP/环境日志、V01–V03 与 V11 状态、旧用例意图映射和推荐精确接口。不得把上游 master 上的接口当作当前 SDK 实测结果。S1 仅在 G0 完成后进入。
