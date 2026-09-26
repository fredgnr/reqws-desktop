---
title: S1 展示状态与宿主生命周期重构
type: technical-design
status: draft
updated: 2026-09-26
---

# S1：固定状态契约和宿主接入

本阶段先建立可测试的展示边界，再处理订阅和内容生命周期，不重写领域同步引擎。

## 输入和所有权

G0 已通过，使用其固定工具链、真实输入路线及覆盖迁移表。Main 独占 UiState/Action 签名、Mapper 入口、Factory/availability、Gradle 和资源文件；派工前将新增文件展开成精确路径。

共享契约提交后，Worker A 独占 `ui/presentation/` 的具体文件及其测试；Worker B 独占 `ui/platform/` 的具体操作/内容生命周期文件及其测试。双方不直接修改彼此路径，Factory 装配交由 Main。

## 子任务

| ID | 内容 | 输出与直接验证 |
|---|---|---|
| S1.1 | 从当前 ViewModel 固定状态/操作契约；加入稳定仓库 ID；保留纯 Mapper 的投影与诊断优先级。 | 共享签名 commit、全状态 fixtures；V04，包含未确认投影不能变绿。 |
| S1.2 | 接入 publisher 的有序 listener，构建只读 UI flow 和短暂反馈；幂等关闭订阅。 | Presenter 单测；V05 的订阅竞争、终态、过期结果及复制反馈。 |
| S1.3 | 实现 IDE 动作适配与 Content Disposable，保持自动刷新/未创建内容可用性。 | 平台测试及最小宿主重建检查；V06，动作执行前再次校验。 |
| S1.4 | Main 合并两侧并装配最小屏幕，Reviewer 查线程/取消/信任边界。 | 集成编译、直接测试和 G1 记录；共享签名/调用方原子一致。 |

S1.2 与 S1.3 可并行，前提是 S1.1 已完成。不得为了并行让两个 Worker 同时改 ProjectService 或 shared UiState；确需 service 窄接口由 Main 实现并补直接测试。

## G1 通过条件

V04/V05 本阶段覆盖通过；V06 的内容创建/销毁与项目 scope 分离已有实际平台/最小宿主证据。完整卸载/动态启用等端到端扩充仍在 S3，明确记录，不提前宣称全场景完成。

新 UiState 不含 Project/VirtualFile/控件引用；复制反馈不冲掉错误；状态通知不因异步映射倒退；dispose 之后无 UI 写入；未打开面板的同步不受影响。禁止以 StateFlow 取代领域 coordinator 的取消与提交保护。

## 交接

给 S2 提供共享契约 commit、状态 fixtures、操作能力和可复用的生产 Screen 入口，附 V04–V06 已执行/待扩充项。旧 UI 的最终删除留给 S4，不新增旧新界面长期互调层。
