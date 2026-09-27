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

当前生产候选仍为 `b86134b`；本时段修复仅在测试设施，最新测试提交 `ce729eb`，保留 ZIP `/private/tmp/reqws-native-final-b86134b.zip` 不变。此前生产完整门禁为364平台、30组件/环境、指定失败/空发现探针、332类产物隔离及2+7 API通过，独立Reviewer已核对固定源码、原始报告与同一ZIP。后续测试设施的直接检查为集成编译通过、25项报告校验测试通过；用户最新要求不关注CI结果，本轮不再等待或轮询CI。

| 范围 | 原始结果和适用性 | 当前结论 |
|---|---|---|
| legacy | `reqws-local-ide-2820ykoq`：3项、0失败/跳过，9进程通过并退出；独立审查同一ZIP。 | 本范围通过。 |
| Compose | 最新 `reqws-compose-host-ybr_f_eb`：3项、1失败，4进程退出；实际键盘、重载、20轮生命周期通过。滚动观察在Dimension返回值适配处失败，尚未发滚轮。 | Rectangle/Number代理已修正并编译；完整字号/尾段/长列表/空提示待重跑，不能判完整通过。 |
| Desktop | 最新 `reqws-local-ide-cl9ubc8x`：4项、1失败、0跳过，8进程退出；选择/冷启动、真实信任、损坏输入与恢复三项通过。用户覆盖停在状态文字观察，ordinary未执行。 | `ce729eb`已修正独立Status节点的精确观察；完整四项/九进程待重跑。 |
| 独立审查 | Reviewer对测试修正确认未削减覆盖，逐图核对本轮33张原生区域图和侧车，确认候选与安装副本一致、两端进程退出。 | 不替代未完成的完整套件。 |

图片范围需准确保留：部分IDE通知遮挡底部操作；一张Dock悬停标签只影响状态栏；`98702-invalid-manifest-1-malformed-dee76080-e86b-4042-b409-10286967ddc6.png`显示索引等待占位，不能证明该时刻Compose完整。独立error-ui图已显示正确Error与稳定码，模型保留证据仍成立。不能将33张统一描述为所有UI均完整可见。

修复使用公开Driver/标准Java API：等待窗口注册但不提前创建Content；读取真实Compose属性；滚动由实际Robot输入、只读可访问数值观察；区域采图前后核对active、项目/PID和几何，不调用focus补偿。完整失败历史与原始位置见[Product Design记录](product-design-2026-09-27.md#本时段结束与下一次复测)。无生产、catalog、依赖或兼容下限变化。

前台操作已收束，全部本轮测试进程退出、专用active-session标记为空。剩余原生复测需新的用户前台时段；VoiceOver继续豁免。S4保持in-progress，G4未通过。
