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

当前生产候选仍为 `b86134b`，保留 ZIP `/private/tmp/reqws-native-final-b86134b.zip` 不变。本时段只修正测试设施；最新原生执行为测试提交 `f2361ff`，后续 `79fbb52` 补充实时边界和失败前截图，尚未原生复测。此前生产完整门禁为364平台、30组件/环境、指定失败/空发现探针、332类产物隔离及2+7 API通过，独立Reviewer已核对固定源码、原始报告与同一ZIP。直接检查包括集成编译、25项Desktop报告校验及9项Compose报告测试通过。用户要求不关注CI结果，本轮不等待或轮询CI。

| 范围 | 原始结果和适用性 | 当前结论 |
|---|---|---|
| legacy | `reqws-local-ide-2820ykoq`：3项、0失败/跳过，9进程通过并退出；独立审查同一ZIP。 | 本范围通过。 |
| Compose | `reqws-compose-host-7awg84j_`：3项、2失败、0错误/跳过，4进程全部退出。实际键盘、四组主题/缩放、基准字号交互和20轮生命周期通过。大字号浅色tooltip范围0→286/max286，015图真实显示全文末句。列表完整末行断言和动态重载TLS EOF失败。 | 大字号列表边界、深色和空提示尚未通过，重载环境错误保留；完整套件未通过。 |
| Desktop | `reqws-local-ide-3os5wgqt`：4项、0失败/跳过，9进程通过并退出；v8有32投影、5落盘、4错误UI及ordinary证据，共37张区域图。 | 完整Desktop范围通过。后续只改Compose输入方法，不影响此报告的运行输入。 |
| 独立审查 | Reviewer核对上述原始XML、进程序列、同一ZIP，逐图审阅Desktop全部37张与侧车；另查看Compose012/014/015/016/017及滚动日志，确认实际尾段和失败边界。`79fbb52`静态审查未发现削减门禁。 | Desktop和指定Compose片段成立，待跑修正不计通过，G4尚未满足。 |

图片范围需准确保留：Desktop部分IDE通知遮挡底部操作，少量Dock标签位于底边，但本轮树、加载数和错误码可见；未出现前轮索引占位图缺口，不能扩大为所有操作都无遮挡。旧 `cl9ubc8x` 的33图与占位局限保留在历史记录，不替代新37图结论。Compose的017错误图在finally恢复字号/宽度后采集，不能证明失败前的大字号列表边界；后续测试已前置采图并记录实际视口/首末行几何，严格完整包含、行高与位移断言保持不变。

修复使用公开Driver/标准Java API：等待窗口注册但不提前创建Content；读取真实Compose属性；滚动由实际Robot输入、只读可访问数值观察；区域采图前后核对active、项目/PID和几何，不调用focus补偿。SDK确认可访问数值按实际Float按值返回；每次重新查询语义节点以避免Driver复用脱离布局的缓存。固定输入方法新增可选诊断入口，范围与完整验收隔离，不能用单项探针替代整套结果。完整失败历史见[Product Design记录](product-design-2026-09-27.md#desktop完整通过与剩余列表边界)。没有生产、catalog、依赖、新API例外或兼容下限变化，仍支持整个262系列。

约25分钟前台时段于09:32 UTC收束，全部本轮测试进程退出、专用active-session标记为空。后续先对相同ZIP运行输入诊断，修复确认后执行完整Compose宿主套件；无需重跑输入未变且已通过的legacy/Desktop。新的原生执行需要新的用户前台时段。VoiceOver继续豁免；S4保持in-progress，G4未通过。
