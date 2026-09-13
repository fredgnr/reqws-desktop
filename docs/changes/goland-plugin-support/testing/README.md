# GoLand 测试与修复交付

本目录保留原插件验证证据，并将后续验收路由至语言无关测试方案。

| 文档 | 类型 | 状态 | 说明 |
|---|---|---|---|
| [旧测试方案入口](test-plan.md) | test-plan | superseded | 已由语言解耦测试方案替代，保留历史原文链接与导航兼容。 |
| [修复交付与验证报告](verification-2026-09-13.md) | test-report | active | 记录原版本最终 GO、修复与产物绑定、真机资源证据和明确跳过项；不证明解耦完成。 |
| [插件输入校验清单](plugin-inputs.sha256) | evidence | active | 保留原交付的 99 项构建与测试输入绑定，不为文档清理重新生成。 |

新候选使用[语言解耦测试方案](../../ide-plugin-language-decoupling/testing/test-plan.md)。旧报告仍是其绑定版本的结果入口，active 表示记录有效，不表示适用于新代码；sleep/wake 等原报告中的标记不改写。语言解耦的实现与测试目前均未执行。
