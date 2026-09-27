---
title: GoLand 插件 Product Design 原型与实施记录
type: technical-design
status: active
updated: 2026-09-27
---

# Product Design 原型与实施

本轮以旧 Swing 信息层级为基线，在可见交互原型上评审紧凑布局、诊断可读性与字号适配，再落到唯一正式 Compose 内容层。

## 范围与视觉目标

用户先要求重构前后保持一致，随后明确允许适当改善美观性与可用性，不追求一比一复刻。保留右上状态标签、工作区主标题、分支与加载数、横向仓库行以及底部主按钮与两个居中链接。原型是本机 React/Vite 展示样例，不是新的生产 WebView 或运行时分支。

Product Design 的 context preflight 未发现保存上下文。基线来自 S0 的真实 Swing 插件截图与 Git 中已删除的 Panel；只裁出插件区域，完整桌面截图不进入仓库或远端。对照原型位于本次聊天的本地可视化产物，源码身份继续由 Git 记录，不增加文件校验账本。

本轮选择保守优化：

- 工作区标题采用宿主文字样式的 Medium 字重，保留既有字号层级、文字和完整 tooltip/语义。
- 错误、警告或 VCS 诊断采用宿主语义色的细侧边与浅背景，文字最多显示三行；普通摘要仍为单行。全文继续通过 tooltip 和复制诊断取得；提示增加高度不能遮挡底部操作。
- 仓库行以 40dp 为 100% 字号基准，随放大字号增长；长列表仍最多六行后滚动。空列表提示允许换行，避免窄栏或大字号裁字。选中仍只影响本地高亮。
- 状态标签与卡片边框保留宿主主题；没有新增用户文案、状态、业务操作或错误优先级。

这些是用户允许的局部调整，替代本需求包中“诊断始终单行、放大字号仍固定40dp”的视觉约束，不改写 S3 历史验收结论。最低版本影响：保持整个262系列、无上限；继续使用宿主 Compose/Jewel，不增加依赖或平台 API 例外。

## 原型验证

本地原型包含旧图并排对照、明暗主题、455/320/240/160px 宽度、100%/125%/150% 字号、长名称和全部仓库状态。同步、选择、示例 Manifest 和复制诊断是可操作样例；没有连接真实项目或修改真实配置。

初次窄栏检查发现长名称挤占状态宽度和诊断 clamp 的内边距漏字，已在原型修正并重新截图。完整视觉 QA 与原始截图保留在本机原型目录；原型结果不作为生产 Kotlin 或真实 IDE 通过证据。

## 实施与验证状态

Compose 三个生产文件已实现标题 Medium 字重、最多三行的语义色诊断面板、按字号增长的行高和可换行空提示。没有 catalog delta，不启动翻译或修改 i18n baseline。

使用现有测试 JBR 25 执行 `compileKotlin compileTestKotlin composeUiTest --tests com.reqws.goland.ui.ReqwsScreenTest`：26项组件用例，0失败/错误/跳过，报告在本机 `/private/tmp/reqws-product-design-direct-fixed`。新增三个用例检查诊断行数与全文语义、普通摘要紧凑性、200%字号下仓库行/空提示的真实文字边界。文档检查通过28个索引、128个文件。

首次终端默认Java21导致Gradle尝试获取Java25，已停止该次构建并切换到现有JBR25；没有启动IDE。首次直接回归26项中1项新断言失败：桌面paragraph的 `isLineEllipsized` 为false，但截图已显示省略号，`didExceedMaxLines` 为true且末行offset小于原文长度。断言改为验证后两项、三行真实边界和完整语义；原始失败报告保留在 `/private/tmp/reqws-product-design-direct` 与 `reqws-product-design-probe`。

最终 `npm run check:goland`、新候选与固定提交的独立审查正在进行，尚不声称完成。

本轮原生 IDE 最终验收尚未授权启动，Compose、受影响 legacy 及 Desktop→GoLand 临时工作区联动继续待运行；旧ZIP结果不能借给本轮新候选。VoiceOver 未运行，按用户要求豁免。S4/G4不因此变为通过。
