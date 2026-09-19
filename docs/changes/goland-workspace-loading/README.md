# GoLand 工作加载集合与独立 IDE 入口

本需求以唯一独立入口替换旧 GoLand 集成，区分需求成员和加载集合，并定义受管 roots、入口排除/树过滤与实施验收；不考虑任何历史兼容。

- 状态：draft（单目标、直接替换的实施提案；不是已上线功能，S0 的目标 API 决策尚未执行）。
- 更新日期：2026-09-19。
- 源码基线：`cb25cbba2b58fc767d48cbbc001004082e7de390`。
- 范围决定：用户已允许历史代码/逻辑直接清除；不保留旧模式、旧协议迁移、回退入口或跨版本矩阵。目标仅为 GO-262.9437.286。
- 已有依据：目标 GoLand 上的候选机制为 **GO FOR IMPLEMENTATION**，不是产品验收 GO。
- 当前提交仅包含文档，不授权安装、重启日常 IDE、修改真实 workspace、合并或发布。

| 文档 | 类型 | 状态 | 说明 |
|---|---|---|---|
| [需求与边界](requirements.md) | requirements | draft | 定义加载语义、用户内容保护及非目标。 |
| [技术方案](technical-design.md) | technical-design | draft | 定义本版数据契约、同步、所有权、平台适配、当前状态恢复与旧逻辑删除。 |
| [任务拆分与 Agent 协作](implementation-plan.md) | technical-design | draft | 定义 S0–S4/V 依赖、文件所有权、subagent 使用和统一清理。 |
| [测试与本地验收](test-plan.md) | test-plan | draft | 分层验证自有逻辑；GUI 只在普通 Project 面板展开目录查看文件。 |
| [验证依据与源码索引](verification-basis.md) | test-report | archived | 摘录用户提供的两轮报告结论，区分源码、报告和未验证事项。 |

实施从 [S0](implementation-plan.md#s0-契约与目标-api-决策) 开始。用户最新决定已撤销本需求的旧模式/旧平台兼容负担；S0 应同步 [插件规范](../../standards/ide-plugin-development-testing.md) 和 AGENTS 中冲突的基线/策略要求。Internal/Experimental 的 API 使用规则需单独处理，不能因无需兼容而默认为全部放行。旧 [GoLand 设计](../goland-plugin-support/technical-design.md) 仅描述替换前实现，不再作为必须保留该运行路径的要求；本 PR 尚未改变产品运行代码。
