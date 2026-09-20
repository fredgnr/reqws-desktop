---
title: GoLand Marketplace 实施与分阶段验收记录
type: test-report
status: active
updated: 2026-09-20
---

# GoLand Marketplace 实施与分阶段验收记录

本记录区分已完成的工程检查、生产配置与仍待完成的正式发布和市场安装更新，不能据工程通过宣称市场已公开。

## 源码与环境

- 工程候选：`f2595cd51ba32c1fcc93b2effe2d156feda66454`，在 PR #16 原 head `1f55654ef4b8f57cb72989a12edcee7037329ac1` 上接续实现。
- 统一版本：0.1.5，包括 package/lock、Gradle descriptor 和插件诊断常量。
- Node 24.20.0、现有 JBR 25.0.3、OpenSSL 3.6.4、固定 Gradle/IntelliJ Platform 插件工具链；GoLand 2026.2.1.1 / GO-262.9437.286。
- 普通 CI 不读取生产秘密；发布签名和市场上传分别使用两个 Environment。市场条目与 token 缺失时保持 bootstrap。

## 已执行检查

| 检查 | 实际结果 |
|---|---|
| `npm ci` | 锁定依赖安装完成；首次沙箱调用因 npm 缓存权限失败，获运行权限后成功，未修改缓存所有权或做依赖升级 |
| `npm run check` | typecheck、lint、337-key i18n 和 docs 检查通过；45 个 Vitest 文件，459 passed、1 skipped，未将跳过项计为通过 |
| `python3 -m unittest discover -s tests/workflows -p 'test_*.py' -v` | 58 项通过，0 skipped；包含真实 ZIP Signer、暂存安全、multipart、状态/收据、重试、工作流和模拟私库故障 |
| 最终源码的 Gradle 正式签名路径 | 在 `f2595cd` 使用一次性身份运行 `signPlugin → verifyPluginSignature → exportPluginArchivePath`，独立 ZIP Signer 验签及最终元数据检查通过，临时身份已清理 |
| 生产/测试编译及 `exportPluginArchivePath` | 实际 Gradle 2.18.1 Provider 接口与 configuration cache 路径通过；unsigned/signed 路径显式区分 |
| `npm run check:goland`，指定精确本地 SDK | 37 类 / 364 项通过，0 failures/errors/skipped；禁用符号、结构和项目配置检查完成 |
| 在线 Plugin Verifier | Marketplace TLS handshake 中断，不能报告在线通过 |
| 同一源码的 offline Plugin Verifier | 保留 API/依赖验证，对 GO-262.9437.286 为 Compatible；远端 CI 继续在线验证 |

签名正向及负向测试使用临时加密私钥和真实 ZIP Signer 0.1.43，覆盖错误密码、密钥/证书不匹配、证书过期、unsigned 和签后篡改。HTTP 成功 fixture 来源是已核对的官方 PluginUpdateBean 字段，不是真实平台接收证据。

保留精确 until-build 的现有工具建议告警，不扩大未经验证的 IDE 范围。GUI G01–G03 尚未执行。

## 生产配置记录

- 独立 RSA 4096 插件身份已保存至指定私有仓库；exact backup commit：`5caab1579e63d7e38e5b169bd78a669cca558de0`。既有 macOS 命名空间未修改。
- 从该远端 commit 读回完整材料，核对密钥解密、公钥/证书及实际恢复签名/验签。初次恢复遇到 GitHub 连接失败，复用同一提交重试；未生成第二套身份。
- 恢复验证后导出公开证书消费副本，配置 `jetbrains-plugin-signing` 的两个签名 Secrets，并建立 `jetbrains-marketplace`。只读回查确认两者仅允许 tag `v*`。
- 模式变量为 `bootstrap`；新增环境不增加 required reviewer，既有 `macos-release` 审批保留。
- 临时生成/恢复目录已清理，无持久本地私库 clone 或凭据目录；公开源码只有验签证书。实际资产摘要仅保留在正式发布/CI 证据中。

## 尚未完成的阶段

- PR #16 远端当前候选 CI、合并及 v0.1.5 正式 Release。
- Marketplace 开发者协议、真实 trader/non-trader 声明、Vendor 创建及 Token 的备份/激活。
- 正式签名 Release ZIP 的 G01 隔离安装、首次人工上传与审核公开后的 G02 市场安装。
- 首版公开后切换 automatic，发布 v0.1.6，记录真实提交结果并完成 G03 市场更新。

工程、GitHub 发布、市场提交和市场公开必须分别给结论。后续执行更新实际 run/Release/update 链接及 GUI 证据；不把当前 mock、局部截图或旧版本报告扩展为完整 Marketplace GO。


## 远端工作流注册复核

首次推送 `95701b1` 的 Marketplace 工作流注册失败：job-level env 不能引用 runner context。修复为在步骤中从 RUNNER_TEMP 写入 GITHUB_ENV；官方 actionlint 1.7.12 对三个受影响工作流检查通过，新增对应回归。复核同时发现 macOS 系统 LibreSSL 3.3.6 不支持 `pkey -check`，因此正式插件签名 job 显式选择 OpenSSL 3；新增顺序断言。7 项配置/工作流专项测试通过。修复后重新触发 CI，旧提交的绿灯不作为修复后候选的最终结论。


## 提交结果持久化失败回归

上传脚本补充结果文件写入失败的保守分类：POST 可能已发生时仍报告 submission-unknown，不能误报为已确认拒绝。未发送 POST 的已记录失败仍为 submission-failed。新增实际 CLI 错误路径回归，15 项上传测试及完整 59 项工作流回归通过；三个工作流再次通过 actionlint。该修复不修改插件二进制输入，但以修复后的远端 CI 作为最终集成门禁。
