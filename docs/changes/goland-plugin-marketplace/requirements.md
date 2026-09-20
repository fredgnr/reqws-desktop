---
title: GoLand 插件 Marketplace 发布需求
type: requirements
status: draft
updated: 2026-09-20
---

# GoLand 插件 Marketplace 发布需求

本需求规定首次上架后，如何在不增加日常人工上传步骤的前提下，把 ReqWS 的正式 GitHub Release 插件提交至 JetBrains Marketplace。

## 1. 背景与目标

当前插件由 `integrations/goland/` 独立构建，正式 tag 流水线已执行测试、Plugin Verifier、ZIP 身份校验和 GitHub Release 资产回读校验；尚未签名或提交 Marketplace。现状依据见[技术方案](technical-design.md)。

目标日常操作保持为：更新统一版本、合入受信任代码、创建正式 tag；已有 macOS 发布审批通过并发布 GitHub Release 后，系统自动提交插件审核。首次上架由发布者手动建立市场条目，后续无需重复人工上传。

JetBrains 要求首次手动上传；后续可自动提交。新插件和每个更新均经过平台审核，上传成功不代表立即公开，也没有保证审核完成时间。[首次发布规则](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html)、[审核规则](https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html)。

## 2. 范围与非目标

纳入范围：发布者及市场资料、许可证与必要隐私说明、插件图标/描述/change notes、有限兼容范围、独立插件签名、最终 ZIP 验签、GitHub Release 后置提交、Stable 渠道、首版引导、按 tag 重试、凭据隔离、结果记录、首次安装及市场两版本升级。

不纳入范围：付费插件、广告、遥测、登录系统、beta/EAP 渠道、自建插件仓库、插件内自更新器、第三方同步 SaaS、跨 IDE 支持扩张、Desktop 更新机制重写、工作区加载方案重做。

不要求历史 ReqWS 版本、历史配置或历史发布脚本兼容；可直接删除被替换逻辑，不保留双实现、迁移或旧路径回退。保留用户数据与既有安全门禁不是历史兼容。已公开 Release 不得被自动替换。

## 3. 固定产品身份与渠道

| 项目 | 约束 |
|---|---|
| 仓库 | `fredgnr/reqws-desktop` |
| 插件显示名 | `ReqWS` |
| Plugin XML ID | `com.reqws.workspace`，不得为重新上架而变化 |
| 首版分发 | 免费、按仓库实际许可证提供源码入口；不新增商业授权 |
| 版本 | Desktop/package/lock/插件 descriptor 共用 `MAJOR.MINOR.PATCH` |
| tag | `vMAJOR.MINOR.PATCH`，不接受预发布、前导零或任意字符串 |
| 渠道 | 默认 Stable；提交参数为空 channel，`isHidden=false` |
| IDE | 当前只承诺经过验收的 GoLand 构建系列 |

名字、Vendor 联系方式、英文主描述及 Logo 必须符合平台规则；发布者账号、Vendor ID、邮箱和 trader/non-trader 声明由实际所有者提供，不从 Git 作者邮箱推断，不虚构。[资料规则](https://plugins.jetbrains.com/docs/marketplace/jetbrains-marketplace-approval-guidelines.html)、[Vendor 规则](https://plugins.jetbrains.com/docs/marketplace/organizations.html)。

## 4. 功能需求

| 编号 | 需求 | 验收结果 |
|---|---|---|
| M01 | 完成首次上架资料和账号关联 | 有真实 Vendor、有效支持/源码/许可证入口和手动提交记录；未审核不得标成公开 |
| M02 | 补齐可交付元数据 | ZIP 中名称、ID、版本、有限兼容范围、图标、描述及本版本 change notes 符合预期 |
| M03 | 使用独立插件签名身份 | 私钥与 macOS `.p12` 分离；正式发布缺凭据或验签失败时不能降级为 unsigned |
| M04 | 构建一次、分发同一产物 | 最终签名 ZIP 经身份、结构、签名校验；GitHub 下载字节与提交市场字节一致 |
| M05 | GitHub Release 后置提交 | 仅在既有正式发布成功后执行，不依赖 `release.published` 再触发 |
| M06 | 明确首版引导状态 | bootstrap 产出签名 Release，提示手动首次上传；首版通过后切 automatic |
| M07 | 默认公开意图提交 | 后续上传指定 Stable、非 Hidden；由 JetBrains 决定审核及公开时间 |
| M08 | 独立安全重试 | 按已发布 tag 重用 Release ZIP；不重新构建、签名、改版本或重跑 Desktop 打包 |
| M09 | 不误报幂等成功 | 重复版本、请求超时、响应不明先核实；“版本存在”本身不能证明同一上传 |
| M10 | 独立结果与故障隔离 | 上传失败不删除/回滚 GitHub Release；报告提交、待审核、公开、失败或未知等准确状态 |
| M11 | 最小凭据权限与统一备份 | PR/普通构建无生产秘密；仅所需步骤注入；全部材料备份到 reqws-secret 并验证可恢复，不做本地备份，日志和 artifact 不泄密 |
| M12 | 完成市场链路验收 | 一次首版市场安装和一次更高正式版本的市场更新，有真实证据 |

签名 ZIP 与 GitHub Release 资产校验和一致性的边界是“上传前”；Marketplace 自己的签名过程可能改变最终分发文件，不能要求市场下载包的整体摘要与上传前相同。[签名机制](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)。

## 5. 生命周期与外部依赖

发布模式必须显式配置为 `bootstrap`、`automatic` 或 `paused`，缺失/非法值在发布预检失败，不能默认为“发布成功”。

| 模式 | 行为 | 对外结论 |
|---|---|---|
| bootstrap | 正常验证、签名和发布 GitHub；不调用自动上传 | `manual-initial-upload-required` |
| automatic | GitHub Release 成功后提交市场审核 | 成功只能表示 `submitted`，不能表示 `public` |
| paused | 保留签名 GitHub 发布，停止市场上传并显式告警 | `submission-paused`，不是 Marketplace 成功 |

首次上架必须使用该正式 Release 的签名 ZIP。平台条目未建立或首版未获批时，不激活 automatic。初始化/暂停是一种明确运营状态，不是历史实现回退。

存在两类完成条件：工程能力完成与市场上线完成。账号条目、Secrets 和真实上架记录缺失时可以交付已实现代码，但不能宣布 M01/M12 或整条发布闭环通过。评审本需求不自动授权真实发布。

## 6. 安全与运行边界

Desktop 继续唯一负责 manifest 写入和 Git 仓库生命周期；插件仍是语言无关的读取/IDE 适配器。本需求不引入 `go.mod` 准入、Go registry 等待、VCS mappings 修改或私有 IDE API。

保留既有 tag 校验、默认分支祖先检查、全部发布门禁、Draft 所有权清理、Release 资产回读校验及 `macos-release` 审批。不能为减少人工步骤删除既有 macOS 审批；不为市场上传新增 GitHub PAT。

正式签名无凭据必须失败关闭，普通无秘密 CI 仍能构建 unsigned 测试候选；两者必须显式区分，测试候选不得被当成正式发布产物。私钥不放源码、Release、日志或缓存。

所有密钥、公钥、证书链、关联密码及发布 Token 的唯一持久备份位置固定为私有仓库 [fredgnr/reqws-secret](https://github.com/fredgnr/reqws-secret)，不再另选备份位置，不建立本地备份。新建或轮换的材料必须先上传私库、从明确 commit 读回并验证可恢复，再激活对应运行时配置；既有 macOS 身份及私库内容不因此重建或改写。

受保护的 Environment Secrets 和经评审的公开验签证书属于运行时消费副本，不替代私库备份。本地及 CI 只能使用权限受限的临时材料，成功、失败或取消后均须清理；不能把生成目录、私库 clone、钥匙串、下载目录、日志或缓存变成长期备份。普通发布 job 不为备份而获取整个私库的读取凭据。细则见[配置方案](bootstrap-and-operations.md#3-插件独立签名身份)。

## 7. 验收与交付边界

按[测试方案](test-plan.md)完成发布脚本、签名、工作流与最小 GUI 测试。GUI 仅在 GoLand 普通 Project 面板展开普通文本仓库确认目录和文件节点，不使用 Find in Files 证明文件存在，不重复验证 IDE 原生 Git、Go SDK/modules、补全或运行/调试。

证据使用 exact Git commit、tag、CI run、Release asset 与 Marketplace update 的可追溯关系；真实摘要留在 Release 校验文件或 CI artifact，文档不粘贴计算出的摘要清单。

本轮交付限于本需求、技术方案、配置方案、测试方案及索引。实现、真实签名、上传、安装和市场审核状态均不得伪装成已完成。
