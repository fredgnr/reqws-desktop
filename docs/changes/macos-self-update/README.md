# macOS 个人自用自更新

本需求定义 ReqWS 保留 Electron Forge、使用固定自签名代码签名身份与 GitHub Releases 实现个人自用更新的目标契约，并记录分阶段实施和验收。

| 文档 | 状态 | 说明 |
|---|---|---|
| [自更新技术方案](technical-design.md) | active | 覆盖范围、签名身份初始化与维护、GitHub Secrets、代码与发布改造、迁移边界和最小验收。 |
| [实施与验证记录](implementation-2026-09-19.md) | active | S1/S2、隔离升级、长期身份配置、P12 导入及签后证书抽取修复、Release 分阶段日志，以及正式发布验收的剩余门禁。 |

S1/S2 代码已接入：固定签名构建、typed IPC、Settings 手动更新、业务活动互斥和四资产发布验证。隔离两版本升级、篡改包及同名错误身份原生拒绝已实测，临时信任与钥匙串已清理。长期身份签名探针、GitHub Environment 配置和指定私有仓库备份验证完成，本地私密材料已清理，仅公开 CER 保留在工作树。获授权推送的 `v0.1.3` 先在 P12 导入阶段失败，后续依明确授权重建标签的候选又在签后证书抽取处报错；两处参数修复与回归范围见实施记录。真实 CI 签名及 U6/U9 的发布/干净用户验收仍待完成，不能宣称正式自更新 GO。历史发布行为见 [GitHub Actions CI 与 Release](../github-actions-ci-release/README.md)。
