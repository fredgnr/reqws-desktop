# GoLand 工作加载集合与独立 IDE 入口

本需求以唯一独立入口替换旧 GoLand 集成，区分需求成员和加载集合，并定义受管 roots、入口排除/树过滤与实施验收；不考虑任何历史兼容。

以下 build 和通过状态描述本需求原候选；后续最低 262、无上限及固定环境自动化开发见[兼容需求包](../ide-plugin-compatibility-automation/README.md)，未验证的新候选不能继承本包的通过结论。

2026-09-26 的 [Playwright S4 实跑](../playwright-regression-automation/implementation-s4-2026-09-26.md)发现初始 JPS 加载与原生落盘竞态；当前技术方案补充了用户单独批准的 JPS 等待接口例外。原候选验收结论保留为历史记录，不代表该后续修复候选通过。

- 状态：active（S0–S4 原候选开发、自动化、独立复核及 G1–G7 隔离 GUI 验收完成；后续详情展示改版另经浏览器设计与交互验证，尚未发布）。
- 更新日期：2026-09-26。
- 设计源码基线：`cb25cbba2b58fc767d48cbbc001004082e7de390`；本批实现以 `589b26a` 为起点，源码身份以本需求提交的 Git diff 为准。Desktop 与插件版本统一为 0.1.4。
- 范围决定：用户已允许历史代码/逻辑直接清除；不保留旧模式、旧协议迁移、回退入口或跨版本矩阵。目标仅为 GO-262.9437.286。
- 前期依据：目标 GoLand 上的候选机制为 **GO FOR IMPLEMENTATION**；最终产品验收依据见下方实施记录，未将前期结论当作最终候选证据。
- 当前工作树包含已通过自动化验证的代码和测试，不授权安装、重启日常 IDE、修改真实 workspace、合并或发布。

| 文档 | 类型 | 状态 | 说明 |
|---|---|---|---|
| [需求与边界](requirements.md) | requirements | active | 定义加载语义、用户内容保护及非目标。 |
| [技术方案](technical-design.md) | technical-design | active | 定义本版数据契约、同步、所有权、平台适配、当前状态恢复与旧逻辑删除。 |
| [任务拆分与 Agent 协作](implementation-plan.md) | technical-design | active | 定义 S0–S4/V 依赖、文件所有权、subagent 使用和统一清理。 |
| [测试与本地验收](test-plan.md) | test-plan | active | 分层验证自有逻辑；GUI 只在普通 Project 面板展开目录查看文件。 |
| [实施与验收记录](implementation-2026-09-19.md) | test-report | active | 保留原候选独立复核与 G1–G7 GUI 验收；另记录 0.1.4 提交及 PR 修复后的 449 项 Desktop / 364 项插件测试和单目标兼容性。 |
| [工作区详情设计验证](../../../design-qa.md) | test-report | active | 记录后续方案一界面改版、同图视觉对照、独立文案审校与模拟 bridge 的浏览器交互验证。 |
| [验证依据与源码索引](verification-basis.md) | test-report | archived | 摘录用户提供的两轮报告结论，区分源码、报告和未验证事项。 |

实施依照 [S0–S4](implementation-plan.md#s0-契约与目标-api-决策) 完成，已同步 [插件规范](../../standards/ide-plugin-development-testing.md) 和 AGENTS 中的目标基线；未放宽 Internal/Experimental API 禁令。最终结果见 [实施与验收记录](implementation-2026-09-19.md)。旧 [GoLand 设计](../goland-plugin-support/technical-design.md) 仅描述替换前实现，不再作为必须保留该运行路径的要求。GUI 验收包含用户手动协助，结论限定于验收时的 0.1.0 开发候选及唯一目标 build；不转记为后续 0.1.4 工件的 GUI 验收。
