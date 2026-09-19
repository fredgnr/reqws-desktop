# macOS 个人自用自更新

本需求定义 ReqWS 保留 Electron Forge、使用固定自签名代码签名身份与 GitHub Releases 实现个人自用更新的待实施方案。

| 文档 | 状态 | 说明 |
|---|---|---|
| [自更新技术方案](technical-design.md) | draft | 覆盖范围、密钥证书生成、GitHub Secrets、代码与发布改造、首次迁移、安全边界和最小验收。 |

当前只有设计文档，不代表已实现或验证自更新；未生成私钥、配置 Secrets、修改代码、创建 tag 或发布版本。现有发布行为见 [GitHub Actions CI 与 Release](../github-actions-ci-release/README.md)。
