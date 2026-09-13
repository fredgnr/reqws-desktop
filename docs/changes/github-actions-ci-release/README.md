# GitHub Actions CI 与 Release

本目录定义 ReqWS 的 GitHub 质量门禁和版本 tag 发布渠道，并明确当前 macOS 资产的分发边界。

- 状态：active
- 更新日期：2026-09-13

## 文档

| 文档 | 类型 | 状态 | 说明 |
|---|---|---|---|
| [需求说明](requirements.md) | requirements | active | 定义 push、pull request 的 Desktop/GoLand 独立门禁、版本 tag 触发规则、产物契约和验收条件。 |
| [技术方案](technical-design.md) | technical-design | active | 说明 Desktop 与 GoLand 插件 CI、双架构打包和事务化 GitHub Release 的工作流设计。 |
| [测试方案与证据](testing/README.md) | testing | active | 规定发布验证方法，并记录 v0.1.1 双架构正式发布及资产复验。 |
| [Release 交付说明](delivery.md) | delivery | active | 说明版本资产的获取、校验、回滚方式及 macOS 分发限制。 |

本需求不改写 [MVP 交付快照](../mvp/README.md)。v0.1.1 的正式发布与资产复验已记录于测试索引；可用版本和每次验证范围以对应报告及 GitHub Release 为准。
