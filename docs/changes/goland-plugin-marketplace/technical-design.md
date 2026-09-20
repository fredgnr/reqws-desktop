---
title: GoLand 插件 Marketplace 发布技术方案
type: technical-design
status: active
updated: 2026-09-20
---

# GoLand 插件 Marketplace 发布技术方案

本方案在既有发布门禁之后增加签名产物的 Marketplace 提交能力，并定义可独立重试且不回滚 GitHub Release 的实施方式。

## 1. 基线与差距

初始方案调研基线为 `c524a42e74de35622ef03c207daa04b2230f3e48`，下表保留初始差距背景；实现依据为后文的精确 262 构建及当前代码。

| 位置 | 基线事实 | 实施改动 |
|---|---|---|
| [.github/workflows/release.yml](../../../.github/workflows/release.yml) | tag 驱动；validate/checks/package/goland-plugin/publish；使用 `github.token` 发布；4 个正式资产 | 保留门禁，插件签名后发布；新增后置 Marketplace 调用 |
| [.github/workflows/ci.yml](../../../.github/workflows/ci.yml) | 插件 CI 使用版本覆盖、buildPlugin 及暂存脚本 | 更新显式产物选择，普通 CI 不获取生产秘密 |
| [build.gradle.kts](../../../integrations/goland/build.gradle.kts) | ID `com.reqws.workspace`；sinceBuild 261；Verifier 为 2026.1.3 和 2026.2；无签名 | 元数据、有限兼容范围、签名/验签及显式输出 |
| [settings.gradle.kts](../../../integrations/goland/settings.gradle.kts) | IntelliJ Platform Gradle 插件固定 2.18.1 | 在固定版本验证任务属性；不顺便升级工具链 |
| [plugin.xml](../../../integrations/goland/src/main/resources/META-INF/plugin.xml) | Vendor 无联系方式，描述简短，无 change notes | 补齐市场所需信息，保留语言无关职责 |
| [prepare-goland-release.py](../../../scripts/prepare-goland-release.py) | 扫描 `distributions/*.zip` 并要求唯一；校验 descriptor；复制原字节 | 显式接收单个输入，区分 CI/正式发布用途 |
| [verify-release-assets.py](../../../scripts/verify-release-assets.py) | 全资产集和 SHA256SUMS 验证 | 保留；上传端另做仅插件资产校验，不误用全量校验 |

初始基线 Release 文案为 unsigned、从磁盘安装、未发布市场；当前生成文案已改为作者签名并明确区分发布模式与市场审核。历史报告不改写。

## 2. 决策与架构

采用“Gradle 构建/签名/验证 + 官方 HTTP Upload API 上传既有 Release ZIP”。不建立自更新服务，不在上传步骤执行 `publishPlugin` 或重新打包。

```text
正式 vX.Y.Z tag
  └─ validate（含模式、版本、受信任提交）
      ├─ checks（保留）
      ├─ package（保留 macos-release 审批与签名）
      └─ goland-plugin（测试、Verifier、buildPlugin、签名、验签、暂存）
          └─ publish（汇合全部门禁，Draft→上传→回读→公开）
              └─ publish-marketplace（复用工作流）
                  ├─ bootstrap / paused：明确非提交状态
                  └─ automatic：下载 Release ZIP→校验→上传→保存提交证据
```

现有发布使用 `GITHUB_TOKEN`，不能指望其产生的 Release 事件启动另一条 `release.published` 工作流。选择主工作流 `needs: [validate, publish]` 后置调用，重试复用同一实现，不引入 PAT。[GitHub 触发规则](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow)。

## 3. 元数据与兼容范围

固定显示名/ID/统一版本。增加 40×40 SVG `META-INF/pluginIcon.svg`；Vendor 网站可用项目主页，邮箱由发布者提供。描述以英文为主，准确说明插件依赖 ReqWS Desktop 创建的 workspace，并给出首次使用路径；不得承诺自动 Git 操作或未经实现的工作区展示行为。

新增 `integrations/goland/CHANGELOG.md` 作为插件专属变更来源。S0 冻结简单的按 `## X.Y.Z` 分节约定，发布提取且转义该版本内容，缺失/空白/占位内容失败；不直接把包含 Desktop 内容的自动 GitHub notes 写入插件。Markdown 说明不自动触发 UI catalog 翻译工作流。

实现基线已更新为 PR #16 的 `1f55654ef4b8f57cb72989a12edcee7037329ac1`，沿用已合入的唯一 GoLand 2026.2.1.1 / GO-262.9437.286：sinceBuild 与 untilBuild 均为 `262.9437.286`，Verifier 仅使用这一目标。不恢复 261 适配，也不扩展到未经验证的 262 构建。

## 4. 签名、产物选择与校验

### 4.1 身份与构建模式

插件使用独立 RSA 私钥和自签名证书，不复用 macOS `.p12`。全部密钥、公钥、证书链、密码及发布 Token 的唯一持久备份是私有仓库 [fredgnr/reqws-secret](https://github.com/fredgnr/reqws-secret)，不建立本地备份。生产私钥/密码仅向签名 Environment 提供运行时副本，Token 仅向上传 Environment 提供运行时副本。

公开 PEM 证书链仍以 `build/jetbrains/reqws-plugin-chain.crt` 为发布候选内唯一受评审的验签输入，但该文件是从私库明确 commit 导出的公开消费副本，不是凭据备份源。更新时核对它与私库原件一致；不把私钥、密码或 Token 随证书提交到公开源码。此文件是公开验签消费副本，其初始备份来源记录在同目录 README。

初始化或轮换按“临时生成/恢复→提交 reqws-secret→从确切 commit 读回验证→配置 Secrets/公开验签副本→清理临时材料”执行。若远端保存或恢复验证失败，不激活新身份；临时材料不转为固定目录或离线备份。权限限制、故障及取消的清理规则见[配置方案](bootstrap-and-operations.md#33-远端备份读回验证与本地清理)。已有 macOS 材料保持身份和原有路径，不因本需求迁移。

正式发布增加 `-PrequirePluginSigning=true`。预检在任务运行前要求有效证书、匹配私钥、所需密码；最终还需确认签名任务产生输出且验签成功，不能把 Gradle 的 skipped/no-source 当成成功。普通 CI 可生成 unsigned 候选；生产发布不得存在“缺秘密就用 unsigned”的降级分支。

配置 Gradle signing 的 Provider，不在配置/日志中打印秘密。signPlugin 指向 buildPlugin 的 `archiveFile`；验签显式使用 signPlugin 的 `signedArchiveFile`；对这些属性在固定 2.18.1 的实际任务上编译及执行验证，不按不断变化的最新版文档盲写。官方任务区分了 unsigned 输入与 signed 输出。[任务参考](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-tasks.html#signPlugin)。

### 4.2 明确每个消费者使用的文件

增加受测 Gradle 任务导出候选 ZIP 路径，或由自定义暂存任务直接消费对应 Provider；不得靠 `ls *.zip | head -1`、最新修改时间或猜测文件名选包。

`prepare-goland-release.py` 改为必填 `--input <exact-zip>`、`--version X.Y.Z`；删除旧目录扫描入口，更新 CI 和 Release 两个调用点及测试。脚本继续保护非普通文件、符号链接、越界路径、重复条目、损坏 ZIP/JAR、异常 XML、唯一插件 ID/version；签名格式相关元数据只能在真实签名 fixture 验证后精确识别，不能全局放宽路径/多根校验。

流水线为“构建校验→签名→对最终签名包验签/结构与身份校验→原字节暂存→计算摘要”。签名后不再 patch descriptor、不改 ZIP 内文件、不再次压缩；改发布文件名不改内容。

正式资产集合仍为：Desktop ZIP、`ReqWS-X.Y.Z-goland-plugin.zip`、`latest-mac.yml`、`SHA256SUMS`。日志和市场响应作为 CI artifact，不往 Release 增加第五个资产来破坏已有严格集合校验。

### 4.3 提交端的只读验签

下载端不用带签名私钥的 Gradle 任务图。实施一个无构建依赖的验签入口，使用项目固定版本的 Marketplace ZIP Signer，仅需公开证书和既有签名 ZIP。其依赖版本/来源纳入评审，不运行 release tag 指向的任意未验证脚本或下载不固定的 latest JAR。

最终 ZIP 必须相对已评审公开证书验签成功，不能仅依赖 ZIP 内自带证书“自证可信”。签名证书更新需要新 tag 及明确维护；禁止悄悄生成新的私钥。

Marketplace 还会执行自身签名，市场下载包不要求与上传前全文件摘要相同。[签名与验签](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)。

## 5. 工作流接口、权限及并发

新增 `.github/workflows/marketplace-publish.yml`，同时提供 `workflow_call` 与无 tag 文本输入的 `workflow_dispatch`。发布对象来自执行该工作流的 `github.ref`，必须为严格正式 tag；手动重试通过 `gh workflow run marketplace-publish.yml --ref vX.Y.Z` 选择已发布 tag，避免“在 main 执行却声明上传任意 tag”的混淆。

| 接口 | 约定 |
|---|---|
| 调用来源 | release.yml 在 publish 成功后调用；或显式 workflow_dispatch |
| 必需上下文 | `refs/tags/vX.Y.Z`、固定 repository、解析后的 commit |
| 模式 | Repository Variable `REQWS_MARKETPLACE_MODE`：bootstrap/automatic/paused |
| 正式目的地 | 固定官方域名、固定 XML ID、`family=intellij`、Stable、非 Hidden，不允许调用方任意指定 |
| 上传 Environment | `jetbrains-marketplace`，仅允许 `v*` tags，实际脚本再做严格正则与祖先检查 |
| 签名 Environment | `jetbrains-plugin-signing`，仅允许 `v*` tags，生产密钥只给签名步骤 |
| 权限 | 上传 `contents: read`；读取既有 CI 收据确有需要时加 `actions: read`；无 contents:write |
| 并发 | 两个入口共享 `jetbrains-marketplace-<repository>-stable`，不取消正在上传的任务 |

GitHub Environment 是 job 级保护，签名和上传 job 分开；复用工作流的 Environment 声明放在被调用的实际 job。仅给步骤 env 传秘密，不用 `secrets: inherit` 扩散全部秘密。Actions 继续固定完整 commit SHA，checkout 禁止持久化凭据，PR 事件不进入生产工作流。[Environment 行为](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments)。

普通 CI、签名和上传 job 不直接 clone `reqws-secret`，也不获得私库访问 Token；由获授权的配置/维护操作从明确私库 commit 同步必要消费副本。这样备份位置统一不会扩大发布 job 的跨仓库权限。

bootstrap/paused 的状态 job 不需要上传 Token；automatic 的上传 job 使用 Environment。模式既在主发布预检验证，也在独立入口复核，防止绕过主流程。任何模式下正式 GitHub ZIP 均必须已签名。

共享并发组只防止重叠上传，不假设它是可靠 FIFO 队列。运营上不同时发多个正式版本；重试发现已有更高的已提交/公开版本时，不自动补发旧版，进入人工核对，避免把重试当降级。

已有 macos-release 的 required reviewer/禁绕过等保护维持原样。两个新增 Environment 默认不再加人工审批，以免 GitHub Release 公开后多一次点击；依赖受信任 tag、祖先检查与最小秘密权限。所有者可以加严，但文档必须说明会暂停无人值守提交。

## 6. 上传端协议

使用 `scripts/publish_jetbrains_marketplace.py`，使用标准库做输入/状态/JSON 检查，并以参数数组调用现有 `gh`/HTTP 客户端；固定远端，不使用 shell 拼接或把不受信任 Release 文案执行成脚本。

一次 automatic/重试执行必须：

1. 严格校验仓库、tag、默认分支祖先关系，记录 peeled commit；checkout 和脚本输入只能来自该受信任候选。复核 Release 对应 tag，非 Draft、非 prerelease。
2. 读取唯一名称的插件 asset 及 SHA256SUMS，记录 Release/asset ID。下载到空临时目录，拒绝重复、零长度、过大或越界的输入。
3. 只解析 SHA256SUMS 中目标插件的唯一标准记录，流式计算比对；拒绝重复目标条目或路径替换。不要对未下载的 Desktop/YAML 执行全文件 `sha256sum -c`，也不要把下载插件子集送给要求完整四资产的验证函数。
4. 检查最终 ZIP 的 ID/version、兼容范围、结构及公开证书签名；上传前再次确认 tag/asset 身份未变化。受信任维护者仍是边界，摘要本身不能防御能同时替换资产和摘要的管理员。
5. 检查已有提交证据；能确认相同提交则返回 already-submitted；不能确认的情况按下一节处理。
6. 向固定 `https://plugins.jetbrains.com/api/updates/upload` 发送 multipart：`xmlId=com.reqws.workspace`、`family=intellij`、file、空 channel、`isHidden=false`、`containsAds=false`。Token 只放 Authorization header。
7. 检查 HTTP 与响应体，保存脱敏收据及 CI summary。HTTP 2xx 但非预期响应/解析失败视为 unknown，而不是简单 green。

XML-ID 上传必须同时传入 `xmlId` 与 `family=intellij`，automatic 提交和 `workflow_dispatch` 重试共用这一固定协议；不得省略 family、传空值，或用 `goland` / `GO` 替代。官方客户端的 [uploadByStringIdAndFamily](https://github.com/JetBrains/plugin-repository-rest-client/blob/d1f4f738092d27eefaa34d011ceb4519189291ee/rest/src/main/kotlin/org/jetbrains/intellij/pluginRepository/internal/api/PluginRepositoryService.kt) 将 family 声明为非空 multipart 字段，[ProductFamily.INTELLIJ](https://github.com/JetBrains/plugin-repository-rest-client/blob/d1f4f738092d27eefaa34d011ceb4519189291ee/rest/src/main/kotlin/org/jetbrains/intellij/pluginRepository/model/ProductFamily.kt) 的协议值是 `intellij`。T09 必须覆盖该字段及缺失/错误值负例，不把省略该字段的简化 HTTP 示例当成完整 XML-ID 协议。

API 表单字段是 `xmlId`，不是说明文字中的 `pluginXmlId`。官方当前大小上限为 400 MB；客户端采用保守的 400,000,000 字节上限。连接/总超时有界；上传 POST 不配置盲目自动重试。只读下载可有限重试。[上传 API](https://plugins.jetbrains.com/docs/marketplace/plugin-upload.html)。

日志禁用 `set -x`、curl verbose、Authorization 输出及未脱敏完整响应。拒绝把认证 header 带到任意重定向主机；日志中的服务端文本需截断、去控制字符，防止工作流命令注入。

## 7. 提交状态、证据和重试

### 7.1 状态与收据

| 状态 | 含义 | 后续 |
|---|---|---|
| submitted | 已获得可核实的服务端接收结果 | 等平台审核，不重发 |
| already-submitted | 当前候选与已有可靠接收收据一致 | 不再 POST |
| submission-failed | 已确认拒绝/本地校验失败 | 修复原因，必要时新版本 |
| submission-unknown | 连接中断、响应不明或冲突但归属不明 | 停止，人工对账 |
| manual-initial-upload-required | bootstrap | 走首次上传 |
| submission-paused | paused | 由所有者处理恢复 |

`pending-review`、`public`、`rejected` 为平台后续状态，仅在实际后台/官方结果确认后记录，不由上传客户端推测。不添加无限等待审核的 CI job，也不添加未经要求的定时轮询。

收据 schemaVersion=1，包含 repository、tag、commit、Release/asset ID、artifact 文件名与摘要、XML ID、version、channel、提交时间、run ID/attempt、HTTP 状态、已返回且经过验证的 update ID（可能为空）和本次 outcome。摘要仅在 CI artifact/Release 保存，不提交到 docs。

成功响应字段契约已按固定官方 PluginUpdateBean 核对；当前测试使用按该类型构造的 fixture，不冒充真实服务端接收记录。仅有“2xx”而无法核实接收时记 unknown。收据只保存白名单字段，禁止保存 Token、私钥或任意服务端原文。

使用具备可追溯 run 来源的 CI artifact 保存收据；不能从用户提交的任意 JSON 或不受信任 PR artifact 推导成功。收据缺失、过期或写入失败时不保证自动幂等，保留原 run 线索并转人工对账；不为此建立新数据库。

### 7.2 不把公开查询当作全部状态

同版本不同包不得上传；首次 POST 无响应可能已经被服务端接收。市场公开版本列表查不到记录，不能证明没有 pending/Hidden 版本；不得据此自动再次提交。下载市场重签名包的 SHA256 也不能作为上传原字节一致性的证明。

重试规则：本地失败且未发送 POST 可在修复后重试；已确认接收且收据匹配则跳过；明确拒绝根据错误修复；超时/重复版本/无可靠证据则 owner 在 Marketplace 后台结合原 CI run 对账。无法证明同一候选就保持 unknown，必要时修复后发布更高版本，不删除市场版本“腾位置”。

独立重试只读取该 Release ZIP，不构建、不签名、不更改 changelog/tag/资产，也不重跑 publish job。初次发布的 tag 必须已包含新的重试工作流；更早的历史 tag 不增加兼容入口。

## 8. 失败隔离与发布说明

签名失败发生在 GitHub publish 之前，阻断正式 Release。Marketplace 提交失败发生在 publish 之后，只让市场步骤失败并说明 GitHub 已成功；禁止触发既有 Draft 清理去删除已公开 Release。

Release 正文使用准确且稳定的措辞：“插件 ZIP 已签名；Marketplace 是否可用以市场审核结果为准”，并在 bootstrap/paused 情况显示对应状态。后续提交结论写 CI summary，不为更改正文扩大上传 Token 权限；真正公开后再按授权更新当前安装指南。

禁止将上传 HTTP 成功、GitHub 资产可下载或磁盘安装截图等同于 Marketplace 上线。两版本市场升级须独立完成。

## 9. 实施拆分与 subagent

不为拆分任务强制新增 PR；后续实现可以在本分支接续。主 Agent 负责合同冻结、集成、安全复核和最终验收；这些阶段定义交付边界，并不要求同时启动代理。

| 阶段 | 文件所有权与交付 | 依赖/最小验证 |
|---|---|---|
| S0 主 Agent | 固定 tag/模式/脚本参数、2.18.1 任务属性、签名格式、响应与证据协议；确认现有测试选择器及官方资料 | 不用生产秘密；缺真实 API 响应时以 unknown 策略兜底，不编造测试通过 |
| S1 构建 Agent | `integrations/goland/` 元数据/changelog/Gradle，`prepare-goland-release.py` 及直接测试；公开证书路径合同 | S0 后；临时测试证书签名/验签、双 ZIP 选择、ID/version/元数据负例 |
| S2 发布 Agent | 新上传脚本/插件子集校验/收据逻辑及其单元测试，不改共享 workflow | S0 后，可与 S1 并行；HTTP mock、缺资产、重复版本、未知响应、日志脱敏 |
| S3 文档/运营 Agent | 本需求包的配置与验收细化、reqws-secret 备份/恢复/清理合同、市场页面文字草案；不获取或写入真实秘密 | S0 后，可并行；资料与授权检查、docs:check |
| S4 主 Agent 集成 | `.github/workflows/release.yml`、`ci.yml`、新复用/dispatch 工作流、共享测试与受影响指南 | S1/S2/S3 后；端到端 mock、无秘密 CI、保留全部既有门禁 |
| V 独立验收 | exact-head 静态/自动化、真实签名发布、市场首版/更新证据 | 工程验收可先行；有授权和外部环境才执行真实发布，不把阻塞项标为通过 |

S1/S2 不并行修改同一测试文件或共享入口；必要时先由主 Agent 拆出专属测试文件。每阶段交付实际 diff、命令/选择器/非零执行数量、结论与未验证项；最终版本变更后重新绑定证据。

subagent 优先继承主 Agent 的实际运行时模型，reasoning 至少 high；如任务另有更严格模型门禁，以用户要求为准，不猜 API model ID。工具无法确认模型/权限时不启动受约束代理，由主 Agent 完成可独立安全部分。真实 UI catalog 翻译才按仓库 i18n skill 启用只读翻译代理；普通 Markdown 不适用。参考[Agent 协作指南](../../guides/agent-workflow.md)。

## 10. 交付、风险与关联材料

代码交付必须同步相关调用方与当前开发/安装说明；本设计不修改现有数据契约、历史证据或 macOS 身份。本次接续实现已获开发、配置、合并及两版本发布授权；法律声明、既有 macOS 审批及安装时的 exact-artifact 确认仍在对应步骤处理。

风险主要为首版人工审核、Token 权限/撤销、证书有效期、服务端响应变化及结果不确定。对应处理是显式引导、最小权限、验签/有效期预检、固定协议测试和 unknown→人工对账，而不是静默放行。

实际配置按[首次上架与配置方案](bootstrap-and-operations.md)，验证按[测试与验收方案](test-plan.md)，需求追踪按[M01–M12](requirements.md)。文档/索引遵循[文档规范](../../standards/documentation-standard.md)。


## 11. 当前实现接口

- `exportPluginArchivePath` 从 Gradle 的 `archiveFile` / `signedArchiveFile` Provider 导出 `build/release/plugin-archive.txt`。普通构建输出 unsigned；正式签名由 `plugin_signing.py sign --version X.Y.Z` 调用 `-PrequirePluginSigning=true --no-configuration-cache --no-build-cache`。签名使用固定 ZIP Signer 0.1.43。
- `prepare-goland-release.py --input <exact-zip> --version X.Y.Z` 共享 `plugin_release.py` 的身份、元数据、有限兼容范围和 ZIP 安全校验，不再提供目录扫描接口。
- 上传分为 `prepare` 与 `submit`：先验证资产和历史，上传不可变 intent artifact；`submit` 读回该 artifact，并在唯一 POST 前再次核对 tag、Release/asset 和签名。结果 artifact 保留 90 天，过期或缺失转人工对账。
- 收据 JSON `schemaVersion=1`，记录 repository/tag/commit、Release/asset/checksum asset ID、filename/sha256、XML ID/version/channel、Marketplace plugin/update ID、run ID/attempt、HTTP status、postAttempted/outcome 和提交时间；不保留服务端原文。
- 响应字段依据固定官方 `PluginUpdateBean`：id、pluginId、version、channel、hidden。HTTP 2xx 只有字段与目标一致才为 submitted。401/403/404/413/422 为明确失败，其余不明响应、超时、冲突为 unknown；公开查询为空不证明未提交。
- 保留上传 intent 与最终结果用于恢复。可信最终成功结果覆盖同次 intent；可信未 POST 失败或明确拒绝可重试，其余未决尝试阻断。所有既往调用的 run attempt 必须有可追溯证据。
