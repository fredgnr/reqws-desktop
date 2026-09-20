---
title: GoLand 插件 Marketplace 发布测试与验收方案
type: test-plan
status: draft
updated: 2026-09-20
---

# GoLand 插件 Marketplace 发布测试与验收方案

本计划用少量聚焦发布链的测试证明签名、产物一致性、自动提交和故障恢复，不扩大为 GoLand 原生能力测试。

## 1. 基本规则

本文件是未执行的计划，不是测试报告。检查必须绑定候选 exact commit、真实选择器与非零执行数量；构建、mock、磁盘安装、市场安装是不同证据，不能互相替代。

本轮纯文档交付检查 `npm run docs:check` 与 diff/链接；不构建插件、不安装 IDE、不调用签名或市场上传。无法运行完整仓库检查时明确说明，不把自定义局部检查冒充该命令。

后续实现按[开发测试规范](../../standards/ide-plugin-development-testing.md)保留必要插件测试和兼容验证；中间阶段只跑受影响检查，最终集成候选才运行完整保留门禁。测试只用隔离临时 Git 仓库和测试证书，不读取真实 workspace/userData，不请求生产 Token。

## 2. 工程用例与需求追踪

| 用例 | 范围与预期 | 需求 |
|---|---|---|
| T01 元数据 | ID/name/version、有限 since/until、Vendor、图标及本版本 changelog 正确；缺失/占位或版本不符失败 | M01/M02 |
| T02 显式选包 | 同时存在 unsigned/signed 时按 Provider/参数选中正确输入；不得猜测或重新打包；CI 调用同步有效 | M03/M04 |
| T03 签名关闭策略 | 无密钥、错误密码、不匹配/过期证书、签名 skipped、缺输出、篡改 ZIP 均阻断正式发布 | M03/M11 |
| T04 无秘密 CI | PR/普通 CI 可构建和验证候选；只用临时测试证书完成签名负例，不访问生产环境 | M03/M11 |
| T05 ZIP 安全 | 保留损坏/重复条目、symlink、路径逃逸、多个 descriptor、异常 XML、ID/version 不符负例 | M02/M04 |
| T06 插件子集验证 | 仅下载插件及 SHA256SUMS 能成功；不要求未下载 Desktop；目标记录重复、名称替换、摘要不符失败 | M04/M08 |
| T07 发布身份 | 非正式 tag、非目标仓库、不属于默认分支、Draft/prerelease、重复/零长资产、ref/asset 变化被拒绝 | M05/M08/M11 |
| T08 工作流门禁 | publish 失败不提交；bootstrap/paused 明确非提交；非法模式失败；automatic 后置提交 | M05/M06/M07 |
| T09 协议 | 对 automatic 与 dispatch 重试的实际 multipart 请求断言固定官方 URL、`xmlId=com.reqws.workspace`、`family=intellij`、Stable/非 Hidden、Authorization；缺失/空白/错误 family 必须使协议断言失败；无 Token 泄露/跨域转发 | M07/M11 |
| T10 响应分类 | 真实协议 fixture 的成功、401/403、明确拒绝、超时、HTML/畸形 JSON、版本冲突分别产生正确结论 | M09/M10 |
| T11 独立重试 | 同一 Release 产物再次校验；不构建、不签名、不重新发布；可靠收据才跳过 POST | M08/M09 |
| T12 不确定状态 | 收据缺失、过期、artifact 来源不可信、公开版本列表为空均不自动推断成功/不存在 | M09/M10 |
| T13 故障隔离 | 市场失败保留公开 Release；不触发旧 Draft 清理；不改变 macOS 审批及四资产集合 | M05/M10 |
| T14 凭据及日志 | 步骤最小注入、并发入口一致、脱敏收据；只备份到 reqws-secret、明确 commit 读回恢复、消费副本一致、失败/取消清理且无本地备份；无生产密钥进入缓存/artifact | M11 |

签名正向测试使用真实 ZIP Signer 和临时自签名证书；仅 mock Gradle 成功或检查 ZIP 文件名不能证明签名存在。API 协议 fixture 应由当前官方实现或合法获取的脱敏样本确认；不把自拟 JSON 当平台事实。

T14 的工程回归使用临时测试身份及模拟私库接口，覆盖目标仓库错误/非 private、备份未推送、读回缺项/错密码/不匹配证书、Secret 同步失败和清理异常；失败不得激活新身份或生成固定本地备份，也不得让普通发布 job clone 私库。测试中的模拟秘密不上传真实 reqws-secret。真实初始化另记录远端 commit、恢复签名/验签和临时路径清理结果；只查 Secret 名称存在或上传返回成功，不能代替恢复验收。

## 3. 命令与分阶段执行

基线已存在的检查命令：

```bash
npm run docs:check
python3 -m unittest discover -s tests/workflows -p 'test_*.py' -v
npm run check:goland
```

S1/S2 先运行新增/直接受影响方法或类，记录准确选择器；不在每个微小补丁重复全量。S4 运行新脚本测试、工作流回归和完整保留插件门禁；变更了现有 CI 调用不得省略普通无秘密路径。

未来正式签名入口增加 requirePluginSigning 门禁，并对最终签名输入运行验签。若公开证书/私钥尚未配置，工程测试用临时身份验证机制，生产身份验收必须单独标 blocked。

## 4. 真实发布与 GUI：只做三个场景

### G01 最终签名 ZIP 的隔离安装冒烟

前置：用户显式授权安装行为，确认 exact ZIP/版本/目标 IDE，按项目手动安装技能边界执行，不修改日常 IDE。

在受支持 GoLand 的隔离实例安装最终候选。使用不含 go.mod 的普通文本 Git fixtures，打开 Desktop 生成的 workspace；在普通 Project 面板展开应加载仓库目录，确认指定 `.txt` 文件节点可见，未加载仓库不应因本次发布改造被带入。插件面板可用，没有发布改造引入的初始化异常。

文件存在性只能看 Project 面板，不用 Find in Files、全局搜索、代码引用或 Go 工具链状态作证。此场景只证明分发包可安装和基本能力未破坏，不证明 Marketplace 已上线。

### G02 Marketplace 首次公开安装（M01/M12）

前置：bootstrap 首次手动上传已审核公开，所有者授权真实市场安装。

在兼容 GoLand 通过官方 Marketplace 找到 ReqWS，核对 Vendor、XML ID、版本、兼容范围并安装。复做一次最小普通文本 Project 面板检查。留存条目/update 链接与可安装证据，记录市场实际分发版本。不得以 GitHub 下载或磁盘安装代替。

### G03 更高版本的自动提交与市场更新（M05/M07/M12）

前置：首版已安装，模式为 automatic，有发布下一个正式版本的授权。

按既有流水线发布更高统一版本，证明 GitHub 发布完成后自动提交同一插件资产；记录 submitted 状态但不提前写 public。平台公开后，在 IDE 检查更新并更新插件，核对版本/ID 和最小 Project 面板行为。IDE 如要求重启需遵守授权，不能提前关闭真实工作。

不要求比较 Marketplace 重签名下载包与上传前整体摘要；对账应关联原提交证据及市场 update，不绕过平台签名。

## 5. 明确不测什么

不新增原生 Git Commit/Log/分支操作矩阵、Go Modules registry、Go SDK、go test、补全/索引语言语义、运行/调试或跨语言 IDE 功能测试。不重复整个 Desktop/macOS 更新人工矩阵；其现有发布自动门禁仍保留。

不为本需求重做工作区根/Content Roots 产品方案；只检查发布后的插件基本行为未破坏。本需求没有修改的历史行为，不通过加大量 GUI 用例“顺便验收”。

## 6. 证据与完成结论

| 阶段 | 必需证据 | 不允许的替代 |
|---|---|---|
| 文档交付 | 变更文件/索引、实际文档与 diff 检查、未运行项 | 声称工程或市场已完成 |
| 工程完成 | exact-head 测试、签名及验签、mock 提交/重试/失败分支、现有门禁 | 仅 ZIP 构建成功 |
| GitHub 发布完成 | 已公开 Release、四资产验证、最终签名插件证据 | Draft/中间 artifact |
| Marketplace 提交完成 | 可追溯接收证据或人工首版记录 | HTTP 2xx 但响应不明 |
| Marketplace 公开完成 | 实际审核结果和 G02/G03 安装更新证据 | pending-review 或磁盘安装 |

报告可放入后续按次测试目录或 CI artifact；新增目录时补 README 和最近索引。本轮不创建空报告或伪造截图。

记录 Git commit、tag、CI run、Release/asset、市场 update、GoLand/JBR/OS 及用例结果。产物摘要留在正式校验文件和 CI artifact，不粘贴到文档。阻塞项写出外部权限/环境缺口；工程 GO 与 Marketplace GO 分别给结论，未执行不得记通过。
