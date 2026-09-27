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

目前 G3 仍未通过：远端独立CI、真实宿主输入/动态重载、系统VoiceOver及独立Reviewer尚未完成。真实系统验收需要本轮已准备候选的前台时段，不能沿用旧会话的短时授权。旧Swing源码和测试仍保留；只有G3成立后才执行S4清理。

## 旧断言接续与 S4 删除条件

以 [S2迁移表](s2-verification-2026-09-27.md#4-旧断言迁移)为基础，本轮补齐其中缺少的格式化错误保留提示、Unicode换行、完整诊断/无障碍与窄宽RTL行为。`ReqwsProjectServiceTest` 中12处旧 ViewModel 调用仍需在S4改为新Mapper，保留所有错误、投影、取消和恢复断言。旧Panel/ViewModel/LatestOnlyEdtDispatcher及其三个专属测试文件是待删除清单，尚未删除；publisher、Factory/availability、项目服务与全部安全回归继续保留。
