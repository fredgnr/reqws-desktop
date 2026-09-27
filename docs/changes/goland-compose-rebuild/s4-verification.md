---
title: Compose S4 清理与最终验收记录（2026-09-27）
type: test-report
status: active
updated: 2026-09-27
---

# Compose S4 清理与最终验收

S3/G3已由 `d56d8f7` 独立审查收口；最终生产候选 `b86134b` 的必需验收及独立审查现已完成，S4 completed、G4 pass。最低版本保持整个262系列、无上限；没有文案/catalog变化，没有性能门禁，VoiceOver按用户要求豁免。

## 清理与覆盖接续

删除旧 `ReqwsToolWindowPanel`、`ReqwsToolWindowViewModel`、`LatestOnlyEdtDispatcher` 及三个专属测试文件。项目服务测试12处调用原子迁移至 `ReqwsUiStateMapper.map`，保留错误、投影证明、取消和恢复断言。Factory/availability、TerminalStatePublisher、Presenter、Content session及领域安全测试不删。S0历史patch继续用于原Git基线重建，不是正式运行时或双UI入口。

旧Panel由[S2迁移表](s2-verification.md#4-旧-panel-意图接续)和[S3补充](s3-verification.md)的24组件接续；恢复旧版结构后增加状态标签、40dp横向行、六行上限、按钮/链接层级与短名宽度回归。旧ViewModel20意图在Mapper同名测试保留；旧dispatcher四项有序/终态/关闭意图由Presenter及平台Content测试接续，业务latest-wins/publisher回归仍保留。

## 清理阶段候选与验证（历史）

生产与测试固定提交为 `3c305f6`。`npm run check:goland` 已完整通过：39类、364项平台测试，0失败/错误/跳过；24组件及指定失败/空发现探针通过（干净提交报告 `run-z0fopdsy/evidence`），编译、禁用符号、结构/descriptor、326类ZIP隔离检查、最低/代表版2目标及新冻结完整7目标通过。完整日志在 `/private/tmp/reqws-compose-s4-final-check.log`，API原始报告在临时目录 `reqws-api-sl51nyrd`，候选身份保存在 `/private/tmp/reqws-compose-s4-candidate.json`；确切ZIP在全程保持不变。

[CI 36294817036](https://github.com/fredgnr/reqws-desktop/actions/runs/36294817036) 属于 `3c305f6`，全部必需任务成功。独立Reviewer `/root/s3_review` 使用已核验的 `astra/gpt-6-astra/xhigh` 只读复核固定提交、364平台/24组件原始报告、2+7 API与候选一致性及远端结果，未发现必须修复的代码问题。直接删除检查另有130项回归通过、0跳过；文档检查通过28个索引、127个文件。

当前组件方法 `everyProductionRepositoryStatusRetainsCompleteSemanticsAtNarrowWidth` 接续S2历史表中的 `everyProductionRepositoryStatusRetainsVisibleTextAtNarrowWidth`。改名反映恢复旧版紧凑省略布局后的真实断言：可见状态与完整语义/tooltip保持，不声称全部长文字同时在窄行完整绘制。S2历史记录不改写。

该清理阶段结束时，新ZIP尚待原生验收与报告审查。S3证据不借给删除旧类后的新ZIP，G4仍未通过。之前前台时段已结束且测试进程均退出；最终时段已向用户请求，未获确认前不启动IDE。文档提交不改变 `3c305f6` 的运行时输入或候选ZIP。

## 后续 Product Design 迭代

用户随后要求先完成可见原型再改造UI，并允许适度美观性/可用性调整、不追求一比一复刻。新范围与当前证据见[Product Design记录](product-design.md)。上面的 `3c305f6` 仅代表清理候选；新UI改变后的ZIP需重新绑定自动检查和本机证据；该阶段当时的G4尚未通过，最终结果见下节。

## 当前冻结候选与原生验收

生产候选固定为 `b86134b`，保留 ZIP `/private/tmp/reqws-native-final-b86134b.zip`。最终集成测试提交为 `2980ed0`；与生产提交相比，生产源码、依赖/构建配置和ZIP未变。后续只修正测试观察与输入设施，并按影响完成编译、报告校验和真实宿主复验，不借旧ZIP或单项诊断拼成整套通过。

| 范围 | 原始证据 | 结论 |
|---|---|---|
| 保留自动门禁 | `/private/tmp/reqws-native-final-check.log`：39类364平台、29组件+1环境共30项，均0失败/错误/跳过；指定断言失败和空发现探针、332类产物隔离、2+7 API目标通过。组件 `run-91ehucmh/evidence`，API `reqws-api-_s5wku_c`。 | 输入未变，先前独立审查仍适用。 |
| 测试设施 | `2980ed0`生产/测试/集成编译通过；Desktop报告校验25项、Compose报告校验9项通过。单项诊断 `reqws-compose-host-rbxqo_ak` 为1项/1进程通过，只用于定位。 | 诊断范围不能代替完整验收。 |
| 完整Compose | `reqws-compose-host-qz9y2hoo`：3项、0失败/错误/跳过，4个进程全部started→passed→exited，20周期通过；报告 `scope=compose-host`、exit0。 | 本范围通过。 |
| legacy | `reqws-local-ide-2820ykoq`：3项、0失败/跳过，9进程通过并退出；Project树、未打开面板刷新与冷恢复。 | 同一ZIP、相关输入未变，本范围通过。 |
| Desktop | `reqws-local-ide-3os5wgqt`：4项、0失败/跳过，9进程通过并退出；v8有32投影、5落盘、4错误UI及ordinary证据，共37张区域图。 | 同一ZIP，后续只改Compose输入方法，本范围通过。 |

三个原生目录均位于本机私有临时根 `/private/var/folders/1n/xfsbr49x3s1g8xqcz8wb0s500000gn/T/`，原始 `report.json`、JUnit、进程/输入日志和图片保留。完整Compose固定环境为macOS arm64、JBR25、GoLand2026.2.1.1（262.9437.286）；命令为 `python3 scripts/run_compose_content.py --profile /Users/fred/.reqws-ide-tests/goland-2026.2.1.1 --archive /private/tmp/reqws-native-final-b86134b.zip --version 0.1.7 --allow-input`，没有 `--input-probe`。精确方法为 `ComposeHostInputTest.productionActionsThemeAndScaleUseRealInput`、`ComposeHostInputTest.settingsDisableAndEnableReleaseAndRecreateProductionContent`、`ComposeContentLifecycleTest.contentLifecycleIsIndependentOfProjectSynchronization`。

最终输入报告验证真实鼠标/键盘操作、四组明暗主题/100%/125%缩放、13→26实际字号和40→54行高。280px窄栏中，明暗两态全文提示真实滚至末尾、Escape/外点关闭及18字段复制通过；八行列表的末行完整高54且包含于实际视口，外层正文保持356/max356；空提示从单行30高变为95高。先前反向探测将外层正文带回、造成末行只剩37高的失败原始证据保留，`2980ed0`仅让内层复用实际已验证方向，不放宽完整包含、行高或首行位移断言。

独立Reviewer `/root/native_acceptance_review` 使用已核验的 `gpt-6-astra/xhigh`、非继承配置，核对固定提交、同一ZIP及安装副本、原始XML/20周期/输入/退出记录，实际审阅最终Compose26张图片和Desktop37张区域图。图片范围保留如下限制：Compose001为IDE索引期占位，不能证明该刻Compose可见；后续主题、字号、滚动、尾段与空提示图支持各自断言。Desktop部分原生通知/Dock标签遮挡底部区域，不用于证明全部操作无遮挡；其树、加载数和错误码仍可见。关键功能有后续实际界面与断言证据，不把截图文件存在当作通过。

CMP-V01/V02/V04/V05/V07/V08/V12由保留平台/组件、失败探针、产物/API及旧实现删除审查覆盖；CMP-V03/V06/V09/V11由本轮完整Compose真实输入、重载和20周期覆盖；CMP-V10由同一ZIP的legacy与Desktop完整集成覆盖。所有未豁免必需项均通过，无剩余阻塞。VoiceOver按用户要求豁免、未验证；性能预算不作为门禁，资源释放、重复监听及退出断言继续保留。

本轮动态重载没有未处理Marketplace错误，未屏蔽TLS或IDE错误门禁；此前间歇性TLS EOF保留为历史失败，不声称网络永久修复。按用户要求不等待或轮询CI，当前结论不宣称最新远端CI通过。保持整个262系列、无上限，无新增生产/catalog/依赖或API例外。前台操作完成后设置已恢复、4个最终进程均退出、专用active-session标记为空；S4/G4通过仅覆盖此候选与验收范围，不包含日常IDE安装、合并、签名或发布。
