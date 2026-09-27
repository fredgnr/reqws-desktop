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

生产与测试固定提交为 `3c305f6`。`npm run check:goland` 已完整通过：39类、364项平台测试，0失败/错误/跳过；24组件及指定失败/空发现探针通过（干净提交报告 `run-z0fopdsy/evidence`），编译、禁用符号、结构/descriptor、326类ZIP隔离检查、最低/代表版2目标及新冻结完整7目标通过。完整日志在 `/private/tmp/reqws-compose-s4-final-check.log`，API原始报告在临时目录 `reqws-api-sl51nyrd`，候选身份保存在 `/private/tmp/reqws-compose-s4-candidate.json`；确切ZIP在全程保持不变。

[CI 36294817036](https://github.com/fredgnr/reqws-desktop/actions/runs/36294817036) 属于 `3c305f6`，全部必需任务成功。独立Reviewer `/root/s3_review` 使用已核验的 `astra/gpt-6-astra/xhigh` 只读复核固定提交、364平台/24组件原始报告、2+7 API与候选一致性及远端结果，未发现必须修复的代码问题。直接删除检查另有130项回归通过、0跳过；文档检查通过28个索引、127个文件。

当前组件方法 `everyProductionRepositoryStatusRetainsCompleteSemanticsAtNarrowWidth` 接续S2历史表中的 `everyProductionRepositoryStatusRetainsVisibleTextAtNarrowWidth`。改名反映恢复旧版紧凑省略布局后的真实断言：可见状态与完整语义/tooltip保持，不声称全部长文字同时在窄行完整绘制。S2历史记录不改写。

清理后的新ZIP尚待本轮最终原生验收与报告审查。S3证据不借给删除旧类后的新ZIP，G4仍未通过。之前前台时段已结束且测试进程均退出；最终时段已向用户请求，未获确认前不启动IDE。文档提交不改变 `3c305f6` 的运行时输入或候选ZIP。

## 后续 Product Design 迭代

用户随后要求先完成可见原型再改造UI，并允许适度美观性/可用性调整、不追求一比一复刻。新范围与当前证据见[Product Design记录](product-design-2026-09-27.md)。上面的 `3c305f6` 仅代表清理候选；新UI改变后的ZIP需重新绑定自动检查和本机证据，G4继续未通过。

## 当前冻结候选与剩余原生验收

当前生产与测试候选为 `b86134b`。本轮真实IDE发现的大字号行高不增长、长tooltip无法移入滚动及Escape关闭问题已实施修复，阶段失败、实际修复范围和证据边界见[Product Design后续记录](product-design-2026-09-27.md#后台键盘修复与本轮恢复点)。

完整本地门禁已通过：364平台测试、30组件/环境测试及指定失败/空发现探针，332类ZIP隔离、编译/禁用符号和2+7 API。独立Reviewer已核对固定提交、原始报告和同一ZIP，未发现该范围内的阻塞；[代码CI](https://github.com/fredgnr/reqws-desktop/actions/runs/36302813633)成功。候选是 `/private/tmp/reqws-native-final-b86134b.zip`，私有身份文件为 `/private/tmp/reqws-native-final-candidate.json`。

新ZIP已尝试原生Compose验收，但在 `reqws-compose-host-vi3pndej` 发生测试外点定位失败和Marketplace TLS错误，整体未通过；完整tooltip尾段、长列表/空提示以及legacy、Desktop继续待验收，G4未通过。定位修正已编译，未重跑；测试进程全部退出、测试设置已恢复。当前需要用户检查Marketplace网络/代理连接，详细证据见[环境阻断记录](product-design-2026-09-27.md#继续验收时的环境阻断)。恢复入口是现有专用profile与显式候选ZIP，不能重新构建后沿用旧宿主报告。确认、登录、权限或环境事项需要用户协助时，按本会话规则说明恢复点并暂停goal；不自动等待或继续绕过。


最新恢复进度见[测试设施修正记录](product-design-2026-09-27.md#网络恢复后的测试设施修正)：同一 ZIP 的 legacy 已通过3项/9进程，Compose最新3项/1失败且重载和20周期通过；Desktop四项因旧Swing采图不支持Compose纹理失败。仅测试侧滚动观测和原生区域采图已修正并编译，25项报告检查通过，尚待真实复测。此前Marketplace阻断没有在本轮重载中再次发生；G4仍未通过。
