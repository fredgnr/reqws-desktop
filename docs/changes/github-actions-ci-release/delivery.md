---
title: GitHub Release 交付说明
type: delivery
status: active
updated: 2026-08-13
---

# GitHub Release 交付说明

本文说明 ReqWS 版本 tag 对应的 macOS Release 资产契约、使用方式和当前分发限制，不代表某个真实版本已经发布成功。

## 发布渠道与资产

只有位于默认分支、同时匹配 `vMAJOR.MINOR.PATCH` 且与项目及锁文件版本一致的 tag 才能发布。每个成功 Release 应包含：

| 资产 | 用途 |
|---|---|
| `ReqWS-<version>-macos-arm64.zip` | Apple silicon Mac 的 `ReqWS.app`。 |
| `ReqWS-<version>-macos-x64.zip` | Intel Mac 的 `ReqWS.app`。 |
| `SHA256SUMS` | 两份 ZIP 的 SHA-256 完整性清单。 |

Release 由 GitHub Actions 先以 draft 构建，只有双架构 ZIP 和校验清单均完成远端复验后才公开。首次真实 tag 的运行结果应另存为[按次验证记录](testing/README.md)。

## 下载与校验

下载同一 Release 中的两份 ZIP 和 `SHA256SUMS`，在包含这三份文件的目录执行：

```bash
shasum -a 256 -c SHA256SUMS
```

两项均显示成功后，再解压与本机架构匹配的 ZIP；归档顶层应为 `ReqWS.app`。arm64 对应 Apple silicon，x64 对应 Intel。该渠道不自动安装或替换 `/Applications` 中的版本，也不迁移或删除用户数据。

## 升级与回滚

升级前正常退出 ReqWS，保留需要回退的旧应用副本，再用新资产替换应用。应用状态与工作目录不属于 Release ZIP，替换应用不应主动删除它们。

需要回滚时，从此前 Release 下载匹配架构的资产并重新校验后替换应用。不要移动或复用已发布 tag 来覆盖错误版本；发布内容有误时应停止使用该 Release，修复项目版本后发布新的递增版本。

## 已知限制

- 当前 `.app` 为 ad-hoc 签名，Hardened Runtime 在该本机构建 profile 中关闭，未使用 Apple Developer ID，也未公证。
- 资产不是 Gatekeeper-ready 的公共发行包；`codesign` 结构校验成功不代表其他 Mac 会信任或允许运行。
- 不提供 DMG、自动更新、安装器或跨架构 universal binary。
- 不应通过关闭 Gatekeeper 或移除安全属性来绕过系统保护。面向外部分发前，应单独实现统一 Developer ID 签名、Hardened Runtime、公证和目标 macOS 验收。

## 渠道状态

本文是常青交付契约。是否已有可用版本必须以 GitHub Releases 页面和对应的日期验证报告为准；在首次真实 tag 验证完成前，不应把工作流文件或本地 package 结果描述为已发布交付物。
