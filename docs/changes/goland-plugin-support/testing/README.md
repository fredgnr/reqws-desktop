# GoLand 插件支持测试与验证

本目录保存 GoLand 插件支持的测试范围和后续按次验证证据。

- 状态：active
- 阶段：验证中
- 更新日期：2026-08-17

## 文档

| 文档 | 类型 | 状态 | 说明 |
|---|---|---|---|
| [测试方案](test-plan.md) | test-plan | active | 定义 Desktop、插件、JetBrains Platform、Plugin Verifier、真实 macOS GUI、规模和安全测试。 |
| [2026-08-17 GUI 验收报告](verification-2026-08-17.md) | test-report | active | 记录日常 GoLand 2026.1.3 的真实 GUI 结果、project-dispose 阻塞异常、未完成矩阵和 `NO-GO` 结论。 |

[2026-08-17 报告](verification-2026-08-17.md)绑定旧候选 ZIP（SHA-256 `c1cd6966869bae86d72b998e401d3a9a470e19f6811f51248b3cb763988d9609`）及当时的 164 项测试证据，并因可复现的 project-dispose 异常、Project View 刷新缺口、矩阵未完成和缺少 exact commit 判定为 `NO-GO`。

当前工作树的新候选 ZIP（SHA-256 `8c79cafbbc507366654c679a05f24dd15a9aabb6e64b39c64b5a910447ff59c8`）已通过 182 项插件测试和 261/262 Plugin Verifier，并在日常 GoLand 2026.1.3 中冷启动到 `Synced`；目前只归档了[按原型收口后的同步态实现候选截图](../ui/tool-window-implementation-2026-08-17.png)，尚未执行完整 GUI 矩阵。后续必须在同一 exact candidate 与该 ZIP 哈希上创建新的 dated verification，不能把旧报告或本轮截图中的局部通过项升级为 `GO`。
