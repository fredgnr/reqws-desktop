---
title: v0.1.1 Release 发布验证
type: test-report
status: active
updated: 2026-09-13
---

# v0.1.1 Release 发布验证

ReqWS v0.1.1 已正式发布，两种架构和校验清单齐全。本文仅记录本次成功发布，不将工作流中未执行的失败路径计为通过。

## 版本与来源

- Release：[ReqWS v0.1.1](https://github.com/fredgnr/reqws-desktop/releases/tag/v0.1.1)，非 draft、非 prerelease，发布时间 2026-09-13 10:07:28 UTC。
- Tag：`v0.1.1`，指向默认分支提交 `fc6122f5e5f0bfa4eeddff36f16317f630baf64f`。
- 版本提交基于已合并的 GoLand 支持 `14ef6b6e70fa41784168b990be8e821cdc2294a8`；仅修改 package.json 版本、package-lock.json 顶层与根包版本为 0.1.1，依赖和运行逻辑不变。
- [Release 工作流 34750891583](https://github.com/fredgnr/reqws-desktop/actions/runs/34750891583) 由真实 tag push 触发并成功完成。
- 相对 v0.1.0 的主要变更是 Cursor workspace 启动修复和 GoLand 支持；完整内容见 Release 自动生成的变更记录。

## 检查结果

| 检查 | 结果 |
|---|---|
| 隔离发布工作区 npm ci 与 npm run check | 成功；32 个测试文件、339 项测试通过，类型、lint、i18n 和文档检查通过。 |
| 远端 validate | 57 秒通过；三个版本一致、tag 来源符合默认分支 ancestry，并从锁文件重新安装和运行完整检查。 |
| arm64 package | 45 秒通过；构建、归档、解压、bundle ID/版本/架构和 codesign 结构校验均通过。 |
| x64 package | 1 分 57 秒通过；同样完成 bundle 与架构校验。 |
| publish | 17 秒通过；校验两个 ZIP、生成清单、创建 draft、上传三份资产并复验后公开。 |
| 发布后复验 | 读取公开 Release API，核对非草稿/非预发布、精确三份资产且非空；从 Release 下载 SHA256SUMS，与 GitHub 记录的两份 ZIP digest 及清单自身 digest 逐项匹配。 |

## 远端资产

| 资产 | 大小（bytes） | SHA-256 |
|---|---|---|
| ReqWS-0.1.1-macos-arm64.zip | 120099569 | `d1f475965f8beaa990bd89f55258e6d8a8f918d067794dfca6e2f194104651a3` |
| ReqWS-0.1.1-macos-x64.zip | 124048276 | `09edf07f9e5e9d8612010657c941bc775e07f80546edd22b1efe7b9dc2692c8e` |
| SHA256SUMS | 186 | `0ebcbd538010bce633cce50543b43a4027e747cb9c95bb62dcef8bf685163d78` |

本机发布后检查使用公开资产 digest 与下载清单交叉验证；两个 ZIP 的解压、bundle identity、CPU 架构及签名结构检查由本次 GitHub macOS runner 实际执行，未冒称本机再次解压两份下载包。

## 分发边界

Release 页面保留 ad-hoc 签名、未使用 Developer ID、未公证及非 Gatekeeper-ready 的说明。此版本不改变签名、公证、自动更新或用户数据迁移方案；不安装应用、不替换用户现有副本。

按当前发布契约，三份资产仅包含 Desktop；GoLand 插件继续按其[使用指南](../../../guides/goland-plugin-guide.md)本地构建安装，不上传插件 ZIP 或 Marketplace。成功发布不代表非法 tag、上传失败、清理失败等故障路径已在本次重演。
