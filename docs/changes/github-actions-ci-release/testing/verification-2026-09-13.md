---
title: v0.1.1 Release 发布验证
type: test-report
status: active
updated: 2026-09-13
---

# v0.1.1 Release 发布验证

ReqWS v0.1.1 已正式发布，两种架构和校验清单齐全。本文记录当次发布，不将未执行的失败路径计为通过；具体校验值由 Release 资产保存，不在文档重复粘贴。

## 版本与来源

- Release：[ReqWS v0.1.1](https://github.com/fredgnr/reqws-desktop/releases/tag/v0.1.1)，非 draft、非 prerelease，发布时间 2026-09-13 10:07:28 UTC。
- Tag：`v0.1.1`，指向默认分支提交 `fc6122f5e5f0bfa4eeddff36f16317f630baf64f`。
- 版本提交基于已合并的 GoLand 支持 `14ef6b6e70fa41784168b990be8e821cdc2294a8`；仅修改 package.json 版本、package-lock.json 顶层与根包版本为 0.1.1，依赖和运行逻辑不变。
- [Release 工作流 34750891583](https://github.com/fredgnr/reqws-desktop/actions/runs/34750891583) 由真实 tag push 触发并成功完成。
- 相对 v0.1.0 的主要变更是 Cursor workspace 启动修复和 GoLand 支持；完整内容见 Release 自动生成的变更记录。

## 检查结果

以下沿用当次实际结果，本次清理文档未重演发布：

| 检查 | 结果 |
|---|---|
| 隔离发布工作区 npm ci 与 npm run check | 成功；32 个测试文件、339 项测试通过，类型、lint、i18n 和文档检查通过。 |
| 远端 validate | 57 秒通过；三个版本一致、tag 来源符合默认分支 ancestry，并从锁文件重新安装和运行完整检查。 |
| arm64 package | 45 秒通过；构建、归档、解压、bundle ID/版本/架构和 codesign 结构校验均通过。 |
| x64 package | 1 分 57 秒通过；同样完成 bundle 与架构校验。 |
| publish | 17 秒通过；校验两个 ZIP、生成清单、创建 draft、上传三份资产并复验后公开。 |
| 发布后复验 | 读取公开 Release API，核对非草稿/非预发布、精确三份资产且非空；下载 SHA256SUMS，与 GitHub 记录的资产摘要逐项匹配。 |

## 远端资产

| 资产 | 大小（bytes） |
|---|---|
| ReqWS-0.1.1-macos-arm64.zip | 120099569 |
| ReqWS-0.1.1-macos-x64.zip | 124048276 |
| SHA256SUMS | 186 |

校验值使用上方 Release 的 `SHA256SUMS` 与资产元数据，不在 Git 文档维护另一份副本。原逐值记录可从本文件的 Git 历史查看，既有发布资产不修改。

当次本机发布后检查使用公开资产摘要与下载清单交叉验证；两个 ZIP 的解压、bundle identity、CPU 架构及签名结构检查由 GitHub macOS runner 实际执行，未冒称本机再次解压两份下载包。

## 分发边界

Release 页面保留 ad-hoc 签名、未使用 Developer ID、未公证及非 Gatekeeper-ready 的说明。此版本不改变签名、公证、自动更新或用户数据迁移方案；不安装应用、不替换用户现有副本。

按当次发布契约，三份资产仅包含 Desktop；GoLand 插件按其[使用指南](../../../guides/goland-plugin-guide.md)本地构建安装，当次未上传插件 ZIP 或 Marketplace。成功发布不代表非法 tag、上传失败、清理失败等故障路径已重演。后续发布范围以当前 Release 方案和实际工作流为准，不用此历史结果覆盖新契约。
