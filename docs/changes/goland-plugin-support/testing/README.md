# GoLand 插件支持测试与验证

本目录保存 GoLand 插件支持的测试范围和后续按次验证证据。

- 状态：active
- 阶段：验证中
- 更新日期：2026-08-18

## 文档

| 文档 | 类型 | 状态 | 说明 |
|---|---|---|---|
| [测试方案](test-plan.md) | test-plan | active | 定义 Desktop、插件、JetBrains Platform、Plugin Verifier、真实 macOS GUI、规模和安全测试。 |
| [2026-08-17 GUI 验收报告](verification-2026-08-17.md) | test-report | active | 记录日常 GoLand 2026.1.3 的真实 GUI 结果、project-dispose 阻塞异常、未完成矩阵和 `NO-GO` 结论。 |

[2026-08-17 报告](verification-2026-08-17.md)绑定旧候选 ZIP（SHA-256 `c1cd6966869bae86d72b998e401d3a9a470e19f6811f51248b3cb763988d9609`）及当时的 164 项测试证据，并因可复现的 project-dispose 异常、Project View 刷新缺口、矩阵未完成和缺少 exact commit 判定为 `NO-GO`。

2026-08-18 工作树候选 ZIP（SHA-256 `fcd7b8b7213c091dadaf33ba601188c6fc73543481b4e14f77a70c020d8bb5b9`）已通过 250 项插件测试、项目配置/结构检查、ZIP 完整性检查，并对 GO-261.25134.147 / GO-262.8665.270 fresh Plugin Verifier 均得到 Compatible；Node.js 24 Desktop 全量检查也以 31 个文件/335 项测试通过。这些结果绑定当前代码候选，但尚未把该 ZIP 安装到真实 GoLand，因此不能继承旧候选的冷启动、截图或 GUI 结论。[按原型收口后的同步态实现截图](../ui/tool-window-implementation-2026-08-17.png)仍只用于界面参考。后续必须在同一 exact candidate 与该 ZIP 哈希上创建新的 dated verification，覆盖 Project Model atomic managed/recovery、VCS v2 pending/tombstone、manual reconcile、Settings/pooled writer merge-retry 和完整 GUI 矩阵，不能把自动化或旧截图中的局部通过项升级为 `GO`。
