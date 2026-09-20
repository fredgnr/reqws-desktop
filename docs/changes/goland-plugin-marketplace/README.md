# GoLand 插件 Marketplace 发布

本需求包定义 ReqWS 插件首次上架 JetBrains Marketplace，以及 GitHub Release 成功后自动提交同一份签名 ZIP 的完整流程。

## 状态与范围

- 状态：`active`；方案已确认，接续实现及分阶段上线中；不代表 Marketplace 已公开。
- 设计日期：2026-09-20。
- 代码基线：`main` 的 `c524a42e74de35622ef03c207daa04b2230f3e48`。
- 交付 PR：#16（`feat/goland_plugin_market`）；完成代码、工作流、测试、文档和生产配置，通过门禁后合并。首版 v0.1.5，后续 v0.1.6 验证自动更新。
- 目标实现包含首次人工上架、插件签名、稳定渠道自动提交、按 tag 独立重试、操作配置与最小验收；代码完成不等于市场已公开。

| 文档 | 状态 | 说明 |
|---|---|---|
| [需求说明](requirements.md) | active | 定义发布范围、生命周期、身份、安全边界与可验收要求。 |
| [技术方案](technical-design.md) | active | 定义同一产物发布链、工作流、失败恢复及可并行实施任务。 |
| [首次上架与配置方案](bootstrap-and-operations.md) | active | 定义账号资料、reqws-secret 唯一备份、临时材料清理、Environment 配置与维护流程。 |
| [测试与验收方案](test-plan.md) | active | 限定发布链测试、市场首次安装及两版本更新验收的证据范围。 |
| [实施与验收记录](implementation-2026-09-20.md) | active | 记录当前工程检查、生产配置及尚待完成的真实发布和市场验收。 |

## 关联与权威性

本需求新增 Marketplace 分发能力，不改写[现有 CI/Release 需求包](../github-actions-ci-release/README.md)及[GoLand 插件需求包](../goland-plugin-support/README.md)的历史结论。实施时只更新受影响的当前操作说明；历史“未上架”记录不追改为“已上架”。

插件保持[语言解耦契约](../ide-plugin-language-decoupling/README.md)与[IDE 插件开发测试规范](../../standards/ide-plugin-development-testing.md)的边界。现有 [macOS 自更新](../macos-self-update/README.md)签名身份、审批和资产验证不被替换。

## 凭据保存约定

全部密钥、公钥、证书链及关联密码、发布 Token 统一在私有仓库 [fredgnr/reqws-secret](https://github.com/fredgnr/reqws-secret) 持久备份，不做本地备份。GitHub Environment Secrets 是受保护的运行时副本；源码中的公开证书仅是验签消费副本，其原件也保存在私库。本地仅允许操作期间的受限临时材料，完成或失败退出时清理，不保留私库 clone 或固定凭据目录。具体备份、读回恢复验证及清理顺序见[配置方案](bootstrap-and-operations.md#33-远端备份读回验证与本地清理)。

## 后续实施入口

先读需求，再按技术方案的 S0→S1/S2/S3→S4→V 执行。首次 Marketplace 上传、真实证书/Secrets 操作、安装/重启 IDE、打 tag 和发布均有外部副作用，必须具备各自授权；本次实施已有上述阶段授权；法律声明、macOS 审批和安装时确认仍由对应步骤处理。
