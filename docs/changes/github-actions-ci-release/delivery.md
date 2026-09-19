---
title: GitHub Release 交付说明
type: delivery
status: active
updated: 2026-09-19
---

# GitHub Release 交付说明

本文说明后续有效版本 tag 的 Release 资产契约，不代表新流程已经完成真实 tag 发布。历史 v0.1.1 双架构资产和证据不被本次变更改写。

## 发布渠道与资产

只有位于默认分支、匹配 `vMAJOR.MINOR.PATCH` 且与项目及锁文件版本一致的 tag 才能发布。每个成功 Release 恰好包含：

| 资产 | 用途 |
|---|---|
| `ReqWS-<version>-macos-arm64.zip` | Apple silicon Mac 的 `ReqWS.app`；后续不再提供 Intel/x64 app。 |
| `ReqWS-<version>-goland-plugin.zip` | 独立的 ReqWS GoLand 插件，使用 Install Plugin from Disk 安装。 |
| `latest-mac.yml` | 只引用 Desktop ZIP 的版本、SHA-512/Base64 与字节数。 |
| `SHA256SUMS` | 两份 ZIP 及更新元数据的 SHA-256 完整性清单。 |

完整 Desktop 检查、应用打包和插件检查/打包全部成功后，工作流先创建 draft，远端复验资产集合并重新下载验证实际字节后才公开。插件不是 Electron app 的内嵌资源，ZIP 内插件版本与发布版本一致。按次结果见[验证记录索引](testing/README.md)。

## 下载与校验

下载同一 Release 中两份 ZIP、`latest-mac.yml` 和 `SHA256SUMS`，在包含这四份文件的目录执行：

```bash
shasum -a 256 -c SHA256SUMS
```

三项均成功后，应用 ZIP 解压顶层应为 `ReqWS.app`；它仅用于 Apple silicon Mac。插件 ZIP 不按 Desktop CPU 架构命名，但这不增加插件原有的操作系统或 IDE 兼容性承诺；当前支持边界仍以[插件使用指南](../../guides/goland-plugin-guide.md)和对应验证记录为准。

在 GoLand 的 Settings → Plugins → 齿轮菜单 → Install Plugin from Disk 中选择原始插件 ZIP，按提示重启。插件未签名，不发布到 JetBrains Marketplace。该渠道不自动安装应用/插件，不迁移或删除用户数据；ZIP 构建与 Plugin Verifier 通过不等于真实 GUI 验收通过。

## 升级与回滚

首个固定个人签名的更新版需要手动安装；核对证书身份，正常退出 ReqWS，保留旧应用副本，再用新资产替换。后续通过设置页手动检查、下载并确认安装，支持位置及首次信任验收见[使用说明](../../guides/user-guide.md#个人签名版本的应用内更新)。应用状态与工作目录不属于 ZIP，不应随替换被删除。

插件升级或回退使用对应版本 ZIP 的磁盘安装流程，按 GoLand 提示重启。需要回退应用时，从此前 Release 下载匹配本机架构的资产并重新校验；既有 Intel 资产不由新流程清理，但后续 Release 不提供 Intel app 更新。

不要移动或复用已发布 tag 来覆盖错误版本。发布内容有误时停止使用，修复版本后发布新的递增版本。

## 已知限制

- 后续 `.app` 使用固定个人自签名与 Hardened Runtime，没有 Apple Developer ID 或 Apple 公证，不是 Gatekeeper-ready 公共发行包。
- `codesign` 结构校验不代表其他 Mac 会信任应用；不应通过关闭 Gatekeeper 或移除安全属性绕过保护。
- 不提供 DMG、后台自动更新、自动插件安装或跨架构 universal binary。
- 当前插件的 IDE 编译基线与 261/262 验证矩阵没有扩大；真实 GUI 验收缺口仍独立记录。

正式公开证书、Environment 配置和实际 GitHub 两版本/干净用户验收尚待完成，见[自更新实施记录](../macos-self-update/implementation-2026-09-19.md)。历史 ad-hoc Release 保持原状。

本文是常青契约。是否存在可用版本以 GitHub Releases 和日期验证报告为准；工作流配置、本地脚本测试或 PR CI 不能代替真实 tag、上传及远端复验的端到端证据。
