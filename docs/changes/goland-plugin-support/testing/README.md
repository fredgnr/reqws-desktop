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

当前候选 ZIP SHA-256 为 `72940c8a35828e178d1412d8d72f8cd859ca4802480cc5784b695f99eac486ad`。JDK 21 下 274 项插件测试、项目配置/结构检查、ZIP 完整性检查均通过，GO-261.25134.147 / GO-262.8665.270 fresh Plugin Verifier 均为 Compatible；Node.js 24 Desktop 全量检查为 31 个文件/335 项测试通过。证据覆盖 VCS v3 workspace/session fencing、跨 service 同 generation 文件锁竞争、foreign-session 降权、旧 atomic v2/legacy PSC v1 门禁迁移和 project-service 同步注册/dispose 时序；它不证明两个真实 JVM 的文件锁集成，也不关闭平台 whole-list writer 的不可观察覆盖窗口。后者可确定发生于“pooled mutation 在 ReqWS final read 后、payload-less event 在 ReqWS set 覆盖后”，此时未知 mapping 已丢失，自动化、Verifier 或 GUI 抽样都不能把该架构缺口升级为 `PASS`。新 ZIP 尚未安装到真实 GoLand，因此也不能继承旧候选的冷启动、截图或 GUI 结论。[按原型收口后的同步态实现截图](../ui/tool-window-implementation-2026-08-17.png)仍只用于界面参考。后续只有在需求取舍或稳定原子 API 关闭该阻塞、且同一 exact candidate 完成 GUI 矩阵后，才能创建新的 `GO` 验证报告。
