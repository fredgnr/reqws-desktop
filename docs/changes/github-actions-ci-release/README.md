# GitHub Actions CI 与 Release

本目录定义 ReqWS 的 GitHub 质量门禁和版本 tag 发布渠道，并明确当前 macOS 资产的分发边界。

- 状态：active
- 更新日期：2026-08-13

## 文档

| 文档 | 类型 | 状态 | 说明 |
|---|---|---|---|
| [需求说明](requirements.md) | requirements | active | 定义 push、pull request 与版本 tag 的触发规则、产物契约和验收条件。 |
| [技术方案](technical-design.md) | technical-design | active | 说明 CI、双架构打包和事务化 GitHub Release 的工作流设计。 |
| [测试方案与证据](testing/README.md) | testing | active | 规定本地检查、GitHub 事件验证和首次真实 tag 的证据要求。 |
| [Release 交付说明](delivery.md) | delivery | active | 说明版本资产的获取、校验、回滚方式及 macOS 分发限制。 |

本需求不改写 [MVP 交付快照](../mvp/README.md)。在首次真实版本 tag 完成验证前，本目录只定义有效流程和验证计划，不声称已有可下载 Release。
