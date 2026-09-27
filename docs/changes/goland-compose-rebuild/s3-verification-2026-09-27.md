---
title: Compose S3 自动化集成记录（2026-09-27）
type: test-report
status: active
updated: 2026-09-27
---

# Compose S3 自动化集成

本记录跟踪 rebase 后 S3 的测试接线、覆盖补齐与真实宿主验收；当前 S3 in-progress、G3 尚未通过，S4 尚未开始。

## 候选与边界

交接为 `48d9fe0`，基于主线 `86dc485`，继续使用 `feat/plugin_rebuild_compose` 与 Draft PR #29。S0/S1/S2 的历史结果不作为本次候选的通过依据。最低版本保持整个 262 系列，不设置兼容上限；保留唯一已授权初始 JPS API 例外。没有文案/catalog 变化，没有性能门禁。

Main 独占实现 checkout、构建输出、专用 IDE profile 与远端写入。只读 Explorer `/root/s3_coverage_explorer` 使用 `astra / gpt-6-astra / xhigh`，通过当前宿主工具目录与显式参数核验为 verified，不继承；只读源码与缓存公共 API，没有修改或运行测试。此探索不代替阶段独立 Reviewer。

## 实现和覆盖

- [独立组件入口](../../../scripts/check_compose_ui.py)使用编译 SDK 自带的 JBR 和平台匹配 Skiko 测试依赖；每次生成独立报告。实际检查 JBR/显示设备、生产组件渲染，以及刻意断言失败和空选择器的非零退出。任意基础设施错误不能替代指定负例，正向报告不允许跳过。
- [独立 CI job](../../../.github/workflows/goland-compose.yml)由 PR、Release 和 weekly 调用。PR 的既有 `GoLand plugin checks` 汇总和 Release 发布依赖此任务；CI 环境标志保留，不启动完整 IDE、不需要授权凭据。平台/API/签名门禁继续存在。
- [组件测试](../../../integrations/goland/src/composeUiTest/kotlin/com/reqws/goland/ui/ReqwsScreenTest.kt)补齐完整错误与保留快照提示、诊断全文 tooltip/ContentDescription、复制反馈 live region、grapheme 换行边界与全文末尾滚动、160/240/480宽度及双布局方向、禁用项 Tab 跳过与逆向焦点、主题焦点保持、长列表清空后选择不复活。主题使用不同的窗口/卡片 token 验证生产卡片。
- 字体放大测试暴露 Jewel 固定按钮内容高度截断。生产操作区保留主题样式，并按字体缩放调整按钮高度；文字保持正常换行，没有新增文案或另一套按钮实现。
- [Content 场景](../../../integrations/goland/src/integrationTest/kotlin/com/reqws/goland/ComposeContentLifecycleTest.kt)扩展为20轮隐藏/重建、无面板 `2 → 1 → 0 → 2`、模型/PFI/Project树、销毁后旧 Presenter 不再更新和冷进程恢复；对应平台测试逐轮检查 listener 归零、内容 job 完成、项目 job 保留。
- [输入场景](../../../integrations/goland/src/integrationTest/kotlin/com/reqws/goland/ComposeHostInputTest.kt)通过实际键鼠验证同步 trace、打开确切 manifest、复制反馈/剪贴板、明暗主题及100%/125%缩放。动态禁用/启用走专用 IDE 设置界面，不调用 Internal 的 DynamicPlugins/PluginEnabler。此段描述代码覆盖目标，尚不是实跑通过。

## 已运行与未完成

初次正向22组件用例通过，刻意失败用例1项准确失败，空选择器正确失败；原始报告在 `build/reports/compose-ui-runs/run-wub2coaz/evidence`。后续主题与字体断言扩充先发现两项失败：选中行背景采样点错误、真实按钮字体高度截断；分别修正断言取样与生产高度后，组件22项和受影响 Mapper/Content平台25项通过，均0跳过。生产/测试/集成编译、禁用符号与确切ZIP隔离检查通过（348个自身class）。上述是工作候选检查，提交固定后继续补相应证据。

目前 G3 仍未通过：远端独立CI、真实宿主输入/动态重载、其余系统主题/缩放及独立Reviewer尚未完成。用户已授予本轮约15分钟前台时段，且随后明确要求跳过 VoiceOver；该项未运行、用户豁免，不再阻塞本次G3/G4，不声称已验证。旧Swing源码和测试仍保留；只有G3成立后才执行S4清理。

## 旧断言接续与 S4 删除条件

以 [S2迁移表](s2-verification-2026-09-27.md#4-旧断言迁移)为基础，本轮补齐其中缺少的格式化错误保留提示、Unicode换行、完整诊断/无障碍与窄宽RTL行为。`ReqwsProjectServiceTest` 中12处旧 ViewModel 调用仍需在S4改为新Mapper，保留所有错误、投影、取消和恢复断言。旧Panel/ViewModel/LatestOnlyEdtDispatcher及其三个专属测试文件是待删除清单，尚未删除；publisher、Factory/availability、项目服务与全部安全回归继续保留。

初轮固定 `8161e77` 宿主报告为 failed：Project辅助检查先打开面板，破坏未打开面板断言；同步临时禁用影响焦点假设；动态卸载后的 Content 销毁检查未成立。独立Reviewer另指出复制旧反馈可掩盖键盘失效、weekly组件未绑定冻结source-ref、发布依赖集合测试未更新。Main正在修复并重跑，不能将此轮记为通过。VoiceOver未启用。

用户随后指出重构后的界面与旧版差距较大，要求保持重构前后UI一致。Main已读取保留的旧Panel实现及S0 `swing-v2/screenshots.tsv` 指向的真实截图，新增视觉一致性修复范围。当前候选不作为交付结论，修复后的组件、宿主与候选证据须重新绑定；G3仍未通过。


## 视觉一致性修复与宿主调试进展

按用户最新要求，Compose 已恢复右上状态边框标签、无可见字段前缀的工作区主标题、较小的分支/加载数、横向40dp仓库行及分隔线、六行上限与主题滚动条、底部单行诊断和主按钮/两个居中链接。图标和列表选中色来自宿主 Jewel；普通文本仍保留完整语义和 tooltip。窄行继承旧版的省略及全文 tooltip，不把长状态强制展开为多行，新的回归检查全文语义和实际可见边界。

恢复后组件23项通过、0跳过，指定失败和空发现探针正确失败（`run-7oew0thw` 工作候选）。所有直接UI/publisher平台95项通过，编译、禁用符号、配置/结构/ZIP构建通过；这不替代新视觉候选的真实宿主及最终S4检查。字体测试改为检查实际文字行边界和末字符：Jewel Link 的 paragraph 保留可用宽度，但可见节点按文字宽度收缩，不能仅用 paragraph 宽度判断截断。

旧视觉ZIP上的 `t2_33rwc` 与 `h0u65vwc` 均实际完成20轮Content及两个冷进程；后者动态禁用/启用也通过。卸载会清空平台普通Disposable历史，故测试改用公开 `CheckedDisposable` 子标记观察Content/session，未因此修改生产处置链。新增跨测试类共享启动计数和临时禁用清单，避免污染专用授权profile。真实Shift+Tab在旧视觉候选仍失败，新候选加入公开AWT焦点和Compose语义的只读诊断，未用强制聚焦伪造键盘通过。上述报告是调试范围证据，不借给视觉修复后的候选。前台时段已结束，全部测试进程退出，VoiceOver始终未启用。

独立Reviewer `/root/s3_review`（`astra/gpt-6-astra/xhigh`，宿主显式参数核验verified）对固定 `8161e77` 报告4项问题：未打开面板helper矛盾、发布依赖断言未更新、键盘复制借旧反馈、weekly source-ref未冻结。Main已分别修复；Reviewer后续指出禁用状态恢复不能用loaded代替enabled，已将设置隔离到run-root并无条件恢复复选框。修复后的固定提交复核仍待执行。全部248项Python工作流回归在允许读取隔离子进程信息的环境中通过；此前无该权限的3项失败与真实发布断言失败分别保留。
