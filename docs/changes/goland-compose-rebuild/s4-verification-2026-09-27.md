---
title: Compose S4 清理与最终验收记录（2026-09-27）
type: test-report
status: active
updated: 2026-09-27
---

# Compose S4 清理与最终验收

S3/G3已由 `d56d8f7` 独立审查收口；S4 in-progress，G4尚未通过。最低版本保持整个262系列、无上限；没有文案/catalog变化，没有性能门禁，VoiceOver按用户要求豁免。

## 清理与覆盖接续

删除旧 `ReqwsToolWindowPanel`、`ReqwsToolWindowViewModel`、`LatestOnlyEdtDispatcher` 及三个专属测试文件。项目服务测试12处调用原子迁移至 `ReqwsUiStateMapper.map`，保留错误、投影证明、取消和恢复断言。Factory/availability、TerminalStatePublisher、Presenter、Content session及领域安全测试不删。S0历史patch继续用于原Git基线重建，不是正式运行时或双UI入口。

旧Panel由[S2迁移表](s2-verification-2026-09-27.md#4-旧-panel-意图接续)和[S3补充](s3-verification-2026-09-27.md)的24组件接续；恢复旧版结构后增加状态标签、40dp横向行、六行上限、按钮/链接层级与短名宽度回归。旧ViewModel20意图在Mapper同名测试保留；旧dispatcher四项有序/终态/关闭意图由Presenter及平台Content测试接续，业务latest-wins/publisher回归仍保留。

## 最终候选与验证

完整保留门禁、清理后确切ZIP本机验证和独立审查尚待完成。S3证据不借给删除旧类后的新ZIP，当前不作完整重构GO结论。
