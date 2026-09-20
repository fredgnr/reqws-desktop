---
title: GoLand 插件首次上架与凭据配置方案
type: technical-design
status: draft
updated: 2026-09-20
---

# GoLand 插件首次上架与凭据配置方案

本方案为发布者和获授权的本地 Agent 定义首次建档、独立签名凭据、GitHub 配置、自动发布激活及故障处理的操作顺序。

## 1. 执行前提与授权

本文是待实施流程，不表示工作流、签名身份或 Marketplace 条目已经创建。只有对应实现完成并通过[工程验收](test-plan.md)后，才执行真实发布步骤。

当前文档 PR 不授权生成/上传生产密钥、写 GitHub Secrets/Environment、接受法律条款、上传市场、安装或重启 GoLand、创建 tag/Release。首次账号和法律身份资料由所有者填写；证书生成和 Secrets 上传交给获明确授权且具备工具的本地 Agent，不在聊天中索取秘密。

## 2. 发布者和页面资料

用个人 JetBrains Account 登录 Marketplace，创建或选择真实 Vendor；个人与组织都可拥有 Vendor，Vendor ID 不可后改。[Vendor 文档](https://plugins.jetbrains.com/docs/marketplace/organizations.html)。

准备显示名 ReqWS、真实联系邮箱、项目网站、源码/Issue/文档入口、与仓库一致的许可证文本，核对第三方依赖授权。按平台表单填写 trader/non-trader，不推断用户法律身份。英文主描述解释 Desktop 前置条件、插件的只读投影职责及手动 Git Roots 设置边界；截图只展示候选确实实现的行为。

图标采用自有 40×40 SVG。发布前确认本版本 change notes 与兼容范围一致；不要在名字中加入 JetBrains 产品名或 Plugin。收集个人数据才需要相应隐私政策和同意机制，本需求不新增采集；最终页面说明必须与实际代码核对。[上架资料与规则](https://plugins.jetbrains.com/docs/marketplace/uploading-a-new-plugin.html)。

## 3. 插件独立签名身份

### 3.1 存放与职责

全部密钥、公钥、证书链及其恢复所需密码、发布 Token 均以私有仓库 [fredgnr/reqws-secret](https://github.com/fredgnr/reqws-secret) 为唯一持久备份位置，不做本地备份。该位置已由所有者指定，不再要求选择其他目录、设备或凭据仓库。

| 内容 | 私库备份 | 运行时消费副本 |
|---|---|---|
| 加密插件私钥 PEM | reqws-secret 中的完整加密私钥 | `jetbrains-plugin-signing` 的 `JETBRAINS_PLUGIN_PRIVATE_KEY` |
| 私钥密码 | reqws-secret 中与对应身份关联的密码材料 | 同 Environment 的 `JETBRAINS_PLUGIN_PRIVATE_KEY_PASSWORD` |
| 公钥及公开证书链 PEM | reqws-secret 中的公钥、证书及完整证书链 | `build/jetbrains/reqws-plugin-chain.crt`，仅公开验签输入 |
| Marketplace Token | reqws-secret 中的 Token 及权限/有效期说明 | `jetbrains-marketplace` 的 `JETBRAINS_MARKETPLACE_TOKEN` |
| 既有 macOS 密钥/证书 | 同一 reqws-secret 内已有材料，保持原路径及身份 | 既有 macos-release 配置和公开验签证书，不在本需求重建 |

Environment Secrets 是 CI 的受保护运行时副本，不是可替代私库的备份。公开证书链经 PR 评审进入源码供构建和只读验签，其权威原件也必须存入私库；不再新增 CERTIFICATE_CHAIN Secret。只允许公开验签所需的公钥/证书进入公开仓库，私钥、密码、Token 及私库完整目录均不得进入源码、Release、日志或 artifact。

不在本机 home、桌面、下载目录、固定凭据目录或长期私库 clone 中留存备份，也不依赖本地钥匙串、shell history、编辑器恢复文件或同步盘保存材料。本地只允许操作期间的受限临时目录；正常结束、失败、取消后的清理按 §3.3 执行。现有 macOS Bundle ID、签名身份和 Environment 保持原样。

私钥与其密码同存私库时，私库访问权限就是这组材料的安全边界，不把加密 PEM 误写成对私库读者的第二重保护。限制私库访问并保持 private；不在公开 PR 中展示秘密或私库文件内容。本次只更新方案，不读取、写入或迁移真实凭据。

### 3.2 本地生成示例（获得授权后）

先确认 reqws-secret 可访问且保持 private，再使用现有 OpenSSL 3 在仓库外的受限临时目录操作。以下仅为完整授权会话内的生成片段，必须接续 §3.3 的远端保存与清理；生成目录不是本地备份，不允许执行后长期保留。模板不包含真实口令，本轮未执行：

```bash
set -euo pipefail
umask 077
workdir="$(mktemp -d "${TMPDIR:-/tmp}/reqws-plugin-signing.XXXXXX")"
cd "$workdir"

# 由 OpenSSL 交互读取口令；不用明文 -passout 参数。
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 \
  -aes-256-cbc -out private-encrypted.pem
openssl rsa -in private-encrypted.pem -traditional -aes256 -out private.pem
openssl req -new -x509 -sha256 -days 365 \
  -key private.pem -out chain.crt -subj '/CN=ReqWS Plugin Signing'

openssl pkey -in private.pem -check -noout
openssl x509 -in chain.crt -noout -subject -issuer -dates
openssl pkey -in private.pem -pubout -out key-public.pem
openssl x509 -in chain.crt -pubkey -noout > certificate-public.pem
cmp key-public.pem certificate-public.pem
```

采用独立 RSA 4096 加密 PEM；365 天是本项目初始有效期选择，不是市场强制时长。加密格式必须先用固定构建工具完成一次隔离签名/验签，格式不支持时显式处理，不把解密私钥长期存放。官方支持作者自签名和 ZIP Signer，无需购买 Apple Developer 证书。[插件签名](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)。

私库已有有效插件身份时，从其明确 commit 临时恢复并检查，不无条件重新生成或覆盖。新生成材料尚未完成远端读回验证前不得激活；处理结束必须执行 §3.3 的清理，不能将这段命令的输出目录当作备份。普通删除不等于 SSD 上的安全擦除，因此避免不必要的落盘。

官方签名文档仍将个人资料的“上传公钥”步骤标记为 not available yet；不要把不存在的后台按钮写成执行前置条件。以实际上传/验签和市场后台为准。

### 3.3 远端备份、读回验证与本地清理

本流程由获授权的配置/维护 Agent 执行，不由普通发布 workflow 执行。检查目标确为 `fredgnr/reqws-secret` 且 `private=true`，只处理本次身份所需文件；优先用已授权 API 按路径上传/读取，确需 clone 时也只能放入会话临时目录并在结束时删除。不得改动私库中无关的既有 macOS 材料。

备份集至少包含加密私钥、公钥、证书/完整证书链、对应密码，以及本次涉及的 Marketplace Token；附带用途、身份标识、格式、有效期和对应 Secret 名称等非秘密元数据。沿用私库现有结构；新增插件材料时放入独立命名空间，先检查路径是否存在，不覆盖未知材料。密码或 Token 的收集/保存必须走不回显的受控输入，不放在公开 tool 参数、shell 字面量、提交说明或截图中。

操作顺序固定为：

1. 核对授权和远端保存能力，建立权限受限的会话临时目录及清理机制；已有身份从明确 commit 恢复，新身份才临时生成。不得修改日常钥匙串或建立永久私库 clone。
2. 把本次完整备份集提交并推送到 reqws-secret，记录实际远端 commit。仅在本地 commit 或收到上传成功提示，不能作为备份可恢复的结论。
3. 从该远端 commit 的指定路径读回到另一个空临时目录，核对字节、私钥可解密、公钥/证书匹配、证书有效期和链完整性，并用恢复后的身份完成隔离测试包签名/验签。Token/密码只在内存或临时文件中比较，不输出值；Token 字节读回一致不等于平台权限已验证，真实使用权限按后续授权流程确认。
4. 恢复验证通过后，从同一已验证备份集同步 §4 的 Environment Secrets；导出公开验签证书到源码中的约定路径并按 PR 评审，不复制其他私库文件。若配置同步失败，私库备份已经存在，后续从同一 commit 重试，不依赖残留本地材料。
5. 成功或失败退出均清理本次生成/恢复目录、临时 clone、密码输入文件和派生材料；清除本次设置的秘密环境变量。实现采用 `finally`/退出与信号处理，CI 另有 `always()` 清理步骤，不把清理失败记作成功。系统强制终止等无法执行清理时，在下次操作前核对并只清理记录在案的残留路径，不宣称所有中断都能自动清零。

远端不可用或恢复验证失败时，不激活新身份、不覆盖现有可用 Secrets；新候选未被任何发布使用，可清理后重做，不为保住未激活候选而另建本地备份。Token 若已创建但未能安全保存，应撤销未启用的候选。清理异常需明确报告残留范围，但不得把用户的其他文件、已有工作区或既有凭据目录一并删除。

后续轮换/续签重复“私库保存→明确 commit 读回验证→同步消费副本→清理”，不得只更新 Secret 而遗漏备份。因为不做本地或其他异地备份，恢复依赖 reqws-secret 及其账号访问；Secrets 写入成功本身不能证明私库备份可恢复。

## 4. GitHub 配置

在仓库 Settings → Environments 新建以下环境；先查存在性，重复执行只核对，不覆盖未知配置。Environment 可按 tag 规则限制部署来源。[GitHub 文档](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments)。

| 环境 | 秘密 | 允许的 ref |
|---|---|---|
| `jetbrains-plugin-signing` | `JETBRAINS_PLUGIN_PRIVATE_KEY`、`JETBRAINS_PLUGIN_PRIVATE_KEY_PASSWORD` | Selected tags：`v*` |
| `jetbrains-marketplace` | `JETBRAINS_MARKETPLACE_TOKEN` | Selected tags：`v*` |

两个新环境不默认增加 required reviewer；既有 macos-release 的人工批准继续保留。不能为了无人值守而放开任意 branch/tag，也不能允许缺秘密时降级。

在 Marketplace 的 My Tokens 为具有目标插件上传权限的账号生成 Token；先按 §3.3 保存到 reqws-secret 并完成读回验证，再同步到 GitHub Secret，全程不在聊天/日志展示。仓库变量 `REQWS_MARKETPLACE_MODE` 首先设为 `bootstrap`，最终激活时改为 `automatic`；紧急暂停时设 `paused`。变量是非秘密状态，但更改仍需授权。

示例只在获授权、完成 §3.3 远端读回验证并核对已有配置后执行；私钥经标准输入上传，密码/Token 的交互输入取自同一已验证备份集，不加 `--body '真实秘密'`，不重新生成一套值：

```bash
gh secret set JETBRAINS_PLUGIN_PRIVATE_KEY \
  --repo fredgnr/reqws-desktop --env jetbrains-plugin-signing < private.pem
gh secret set JETBRAINS_PLUGIN_PRIVATE_KEY_PASSWORD \
  --repo fredgnr/reqws-desktop --env jetbrains-plugin-signing
gh secret set JETBRAINS_MARKETPLACE_TOKEN \
  --repo fredgnr/reqws-desktop --env jetbrains-marketplace
gh variable set REQWS_MARKETPLACE_MODE \
  --repo fredgnr/reqws-desktop --body bootstrap
```

命令参数依据 [gh secret set](https://cli.github.com/manual/gh_secret_set)；重试的 ref 选择依据 [gh workflow run](https://cli.github.com/manual/gh_workflow_run)。Secret 名称/更新时间可检查，值不可回读。生成 Token、配置 Environment 和上传 Secrets 不是创建本文 PR 的一部分。公开证书消费副本单独进入实现 PR；密码、私钥及 Token 只提交到指定私库，不进入公开实现 PR 或文档，实际摘要清单也不进入公开源码。

## 5. 首次手动上架与启用自动化

1. 完成工程验收、真实页面资料、生产签名配置，检查模式为 bootstrap；按现有授权发布一个正式版本。GitHub 产物必须已签名且校验完成，summary 显示 manual-initial-upload-required。
2. 从该 Release 下载插件 ZIP 和 SHA256SUMS，检查目标记录、ID/version 及签名。不要临时再构建一份同版本包。
3. 在 Marketplace 的首次上传入口提交该 ZIP，关联 Vendor、资料和许可证，选择默认 Stable，不开启 Hidden。记录市场条目 URL、数字 ID（如平台返回）、XML ID、版本和原 CI run。
4. 根据审核反馈处理。若需修改产物，遵循更高统一版本重新发布，不替换现有 Release ZIP；未审核期间不得宣布可安装。Hidden 会阻止审核后公开，需要明确解除。[首次上传](https://plugins.jetbrains.com/docs/marketplace/uploading-a-new-plugin.html)。
5. 首版获批并可安装后，完成一次市场安装验收，确认 Token 属于有权限的账号；再将模式设 automatic。不要重新自动提交已手动上传的首版来“测试”Token。
6. 发布下一个更高正式版本，验证 GitHub 发布后自动提交；审批公开后用 GoLand 市场更新完成两版本验收。

审核不是 CI 可控制的动作，不承诺立即上架。后台待审核、拒绝、公开状态按实际记录，不能用上传响应代替。

## 6. 独立重试与恢复

未来工作流落地后，重试使用已包含该工作流且已发布的 tag：

```bash
gh workflow run marketplace-publish.yml \
  --repo fredgnr/reqws-desktop --ref vX.Y.Z
```

命令中的 vX.Y.Z 替换为真实已发布 tag。此命令只能调用只读校验/上传流程，不构建或重新签名。当前文档提交没有新增这个工作流。

| 情况 | 处理 |
|---|---|
| 校验失败，未发送上传 | 修复资产来源/权限/配置；不修改已公开 ZIP |
| Token 失效或无权限 | 授权 owner 修复对应 Secret 后重试；无需换签名密钥 |
| 连接中断、响应异常、版本已存在 | 先对照 Marketplace 后台和原 run；无法证明同一产物就保持 unknown |
| 明确审核拒绝 | 阅读反馈；需改包则发更高版本；不删除版本绕过去重 |
| 已提交并有可靠匹配收据 | 不重发，等待审核 |
| 收据过期/缺失 | 人工对账，不凭公开列表为空判定未提交 |
| 紧急暂停 | 授权设 paused；必要时撤销 Token；不自动删除已公开版本 |

签名密钥泄露与 Token 泄露分开处理。轮换/续签的新材料仍须先保存到 reqws-secret 并完成读回验证，再更新运行时副本；泄露时优先停止受影响发布或撤销失效凭据，私库历史中的旧凭据也按泄露处理。Token 轮换不改变插件签名身份；证书到期前至少 30 天提醒维护者，可用同一私钥续签证书并按评审更新公开链，但不假设磁盘安装的自签名信任自动迁移。密钥泄露时停止正式签名并安排明确身份迁移，不默默再生成。

## 7. 记录与剩余条件

每次真实操作记录操作者授权范围、tag/commit、CI run、Release/市场条目链接、脱敏结果与剩余问题。私钥、Token、密码和计算出的摘要不写进本文；产物摘要随 Release/CI 证据保存。

完成标准见[需求 M01–M12](requirements.md)与[测试方案](test-plan.md)。仅当真实首版安装和更高版本更新均通过，才把“工程已实现”推进为“市场发布闭环已验证”。
