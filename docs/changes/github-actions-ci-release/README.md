# GitHub Actions CI 与 Release

本目录定义 ReqWS 的 GitHub 质量门禁和版本 tag 发布渠道，并明确 macOS 资产的分发边界。2026-09-19 的[自更新方案](../macos-self-update/README.md)扩展固定个人签名、四资产契约和 draft 下载校验；正式发布验收尚待完成。

- 状态：active
- 更新日期：2026-09-22

## 文档

| 文档 | 类型 | 状态 | 说明 |
|---|---|---|---|
| [需求说明](requirements.md) | requirements | active | 定义完整 Desktop/GoLand 门禁、版本 tag、arm64 app 与独立插件 ZIP 的产物契约。 |
| [技术方案](technical-design.md) | technical-design | active | 说明依赖缓存、并行 Release DAG、API 分片实时日志/静默诊断和事务化发布。 |
| [测试方案与证据](testing/README.md) | testing | active | 规定新资产和缓存验证方法，保留 v0.1.1 双架构发布的历史证据。 |
| [Release 交付说明](delivery.md) | delivery | active | 说明 arm64 app、GoLand 插件 ZIP 的获取、校验、安装和分发限制。 |

本需求不改写 [MVP 交付快照](../mvp/README.md)。v0.1.1 的正式发布与资产复验已记录于测试索引；新的资产契约只适用于后续 tag，不删除历史 Intel 资产。可用版本和验证范围以对应报告及 GitHub Release 为准。

当前开发中的 GoLand 使用 JDK 25、最低 262 和无上限声明；CI 保留编译、单元/Light/Heavy 平台、禁用 API、结构/产物策略与动态 API 集合，完整 IDE 自动集成仅本机固定版本运行。CI/Release 不要求 IDE 授权，结果不代表本机 UI 已通过，见[兼容开发记录](../ide-plugin-compatibility-automation/implementation-2026-09-21.md)和[分轮验证与日志诊断记录](../ide-plugin-compatibility-automation/verification-2026-09-21.md)。历史 Release 结论不作为新候选验收。
