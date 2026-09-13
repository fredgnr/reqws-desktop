---
title: GoLand 插件支持测试方案（已替代）
type: test-plan
status: superseded
superseded-by: ../../ide-plugin-language-decoupling/testing/test-plan.md
updated: 2026-09-13
---

# GoLand 插件支持测试方案（已替代）

后续插件验收使用[IDE 插件语言解耦测试方案](../../ide-plugin-language-decoupling/testing/test-plan.md)，不再执行旧计划中的 Go registry、Go package 配置和语言工具链门禁。

## 替代范围

[开发与测试规范](../../../standards/ide-plugin-development-testing.md)与[清理技术方案](../../ide-plugin-language-decoupling/technical-design.md)明确新的职责。保留 ReqWS 自有 manifest、Git 仓库视图、项目范围、配置保护、安全和同步恢复测试；取消重复 IDE/语言原生能力验收。旧 Kotlin/平台自动化中覆盖通用缺陷的回归仍有效，不因本文件被替代而自动删除。

本次只调整文档。`ReqwsGoModulesSynchronizer` 及其关联代码尚待后续实施清理，新计划中的功能用例未运行。

## 历史原文与结果

需要复核原验收时，阅读固定基线的[完整旧测试方案](https://github.com/fredgnr/reqws-desktop/blob/409b30e573d47348618620bbc0a52c0dd0710954/docs/changes/goland-plugin-support/testing/test-plan.md)和[原验证报告](verification-2026-09-13.md)。旧报告、截图、输入 digest 均保留为对应工件的证据，不改写历史 GO，也不继承它作为新版本 GO。

## 10. 资源与同步追踪

### 10.1 同步追踪测量方法

此标题保留旧文档入链。按次历史测量方法见上方固定基线的旧测试方案；后续按[新测试方案](../../ide-plugin-language-decoupling/testing/test-plan.md)仅在具体事件/性能风险需要时扩展追踪，不重复执行 Go registry 计数或把全进程扫描作为常规前置条件。
