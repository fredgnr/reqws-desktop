---
title: GitHub Actions CI 与 Release 需求说明
type: requirements
status: active
updated: 2026-08-17
---

# GitHub Actions CI 与 Release 需求说明

本需求为所有分支变更建立一致的自动检查，并将默认分支上的有效版本 tag 转换为可校验的双架构 macOS Release 资产。

## 背景与目标

项目已有可复现的 `npm run check` 和 macOS package 脚手架，但缺少托管在 GitHub 上的持续检查与版本资产发布入口。目标是让 push、pull request 和人工检查使用同一质量基线，并让 tag 发布具备明确的版本、来源、架构和失败语义。

## 范围与非目标

范围包括：

- 所有 branch push、`pull_request` 和 `workflow_dispatch` 的 CI。
- 独立的 GoLand 插件测试、项目/结构检查、261/262 Plugin Verifier 和 ZIP build 质量门禁。
- 默认分支有效语义化版本 tag 的双架构 macOS package 与 GitHub Release。
- Release ZIP 的 SHA-256 清单、最小权限和失败清理。

本需求不包括 Developer ID 签名、Apple 公证、DMG、自动更新、Windows/Linux 构建，也不自动配置 GitHub branch protection。GoLand plugin ZIP 只在 CI 中构建验证，不进入 tag Release，不签名、不上传 Marketplace。必需检查是否阻止合并仍由仓库规则管理。

## 触发与质量规则

1. 所有 branch push、所有 `pull_request` 和人工 `workflow_dispatch` 均运行两个独立 job：Desktop `checks` 与 `goland-plugin`。
2. Desktop `checks` 在 `macos-15`、Node.js 24 上依次执行 `npm ci`、`npm run check` 和 arm64 package smoke；package smoke 复用依赖和检查，不安装应用、不创建 Release，也不上传长期交付资产。
3. `goland-plugin` 在 `macos-15`、JDK 21 上校验 Gradle wrapper，并执行 `test`、`verifyPluginProjectConfiguration`、`verifyPluginStructure`、对 GoLand 2026.1.3/2026.2 的 `verifyPlugin` 和 `buildPlugin`。它不依赖 Desktop `node_modules`，也不上传发布资产。
4. Release 工作流由 `v*` tag push 触发，但只接受无前导零的 `vMAJOR.MINOR.PATCH`；其版本必须同时等于 `package.json` 与 `package-lock.json` 的项目版本。
5. tag 指向的 commit 必须可从仓库默认分支到达。仅创建于功能分支或游离提交上的 tag 必须失败且不得发布。
6. Release 的 validate 阶段重新执行 `npm ci` 与 `npm run check`，成功后分别在 `macos-15` 构建 arm64、在 `macos-15-intel` 构建 x64。GoLand plugin job 不加入 tag Release DAG。
7. 每次发布仍只产出 `ReqWS-<version>-macos-arm64.zip`、`ReqWS-<version>-macos-x64.zip` 和覆盖两份 ZIP 的 `SHA256SUMS`；不发布 GoLand plugin ZIP。
8. 发布阶段先创建带本次运行标识的 draft Release，下载并验证两个架构 job 的资产与校验和后才转为公开；任何一步失败时，工作流尝试清理仅由本次运行创建的 draft，不覆盖既有 Release 或 tag。

## 安全与失败语义

- 工作流不使用仓库自定义 secrets。GitHub 提供的短期 token 只用于读取仓库和最终发布。
- 默认权限为 `contents: read`；只有发布 job 获得 `contents: write`。
- 所有第三方 GitHub Actions 必须固定到完整 commit SHA，不能只引用可移动 tag。
- 检查、版本验证、任一架构打包、资产校验或发布失败，整个运行均失败；不允许以单架构或缺少校验清单的 Release 降级成功。
- 当前 `.app` 使用 ad-hoc 签名、未公证，不保证通过其他 Mac 的 Gatekeeper；Release 页面和交付说明必须保留这一限制。

## 验收条件

- branch push、pull request 和人工触发均可看到同名 CI 检查，且任一命令失败会使检查失败。
- 同一事件可独立看到 GoLand plugin 检查；任一 plugin test、项目/结构校验、261/262 verifier 或 ZIP build 失败时该 job 失败。
- CI 能从干净依赖安装完成 arm64 package smoke，且不会产生 GitHub Release。
- 非法 tag、三个版本不一致或 tag commit 不属于默认分支时，Release 运行在创建 Release 前失败。
- 合法 tag 只有在重跑检查和两种架构 package 均成功后才发布，资产名称、数量和 SHA-256 清单符合契约。
- 解压两份 ZIP 后均保留完整 `ReqWS.app` bundle；其版本、架构和 bundle ID 通过现有 package 校验。
- 发布失败不会删除既有 Release；工作流只能清理可确认属于本次运行且仍为 draft 的 Release，API 不可用或删除失败时必须告警并留待人工处理。

## 关联文档

实现决策见[技术方案](technical-design.md)，验证范围见[测试方案](testing/test-plan.md)，资产使用与限制见[交付说明](delivery.md)。
