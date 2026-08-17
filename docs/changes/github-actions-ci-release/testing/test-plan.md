---
title: GitHub Actions CI 与 Release 测试方案
type: test-plan
status: active
updated: 2026-08-17
---

# GitHub Actions CI 与 Release 测试方案

本方案验证质量门禁在三类 CI 事件上一致执行，并验证 tag 发布不会产生版本错误、缺架构或未校验的公开 Release。

## 范围与非范围

范围包括 workflow 语法与权限、Node 24 Desktop 检查、arm64 CI smoke、独立 JDK 21 GoLand plugin job、双架构 Release package、版本和默认分支校验、artifact 汇总、draft 发布及失败清理。

不验证 Developer ID、Apple 公证、Gatekeeper 跨机器接受、DMG 或自动更新；这些能力当前未实现。

## 环境与前置条件

- 本地目标环境：Node.js 24、npm lockfile、macOS；运行时不使用发布权限。
- GitHub 环境：`macos-15`、`macos-15-intel`，仓库 Actions 可读权限，以及 release job 的内置 `contents: write`。
- GoLand plugin job 使用 `macos-15`、Temurin 21、已验证 wrapper 和独立 Gradle cache；Verifier 目标为 GoLand 2026.1.3 与 2026.2。
- 真实 tag 测试前，`package.json` 与 `package-lock.json` 版本已按发布流程同步，目标 commit 已进入默认分支。
- 不为测试读取或添加仓库自定义 secrets；不要在未批准的情况下创建或移动远端 tag。

## 自动与本地场景

| 场景 | 方法 | 通过标准 |
|---|---|---|
| 文档与项目基线 | `npm run docs:check`、`npm run check` | 索引、类型、lint、i18n 和全部测试通过。 |
| Workflow 静态检查 | 解析两个 YAML，并检查触发器、runner、权限和 action 引用 | branch push、PR、dispatch、`v*` 均存在；第三方 action 只使用完整 SHA；写权限只在发布 job。 |
| CI package smoke | 干净 `npm ci` 后执行 arm64 package smoke | 脚手架验证 bundle ID、版本、架构和签名结构；不安装、不创建 Release。 |
| GoLand plugin job | `./gradlew test verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin buildPlugin --no-daemon` | tests、项目/descriptor、261/262 二进制兼容与本地 ZIP build 全部通过；任一步失败使 job 失败。 |
| Desktop/Gradle 隔离 | 检查根 scripts、Vitest include、Forge/Vite 输入和 CI job 边界 | 根 `npm run check`、`package:macos` 不隐式运行 Gradle 或包含 plugin build；plugin job 不依赖 `node_modules`。 |
| 归档与校验和 | 对测试 `.app` 生成目标 ZIP 和 `SHA256SUMS` 后解压 | bundle 结构与符号链接保留，清单反向校验成功，文件名符合契约。 |
| 版本解析 | 覆盖合法版本、前导零、缺段、预发布后缀和三个版本不一致 | 只接受三个来源完全一致的 `vMAJOR.MINOR.PATCH`。 |
| 默认分支 ancestry | 用临时 Git refs 覆盖已合并与未合并 tag commit | 只有默认分支可达 commit 通过。 |

## GitHub 事件场景

| 事件 | 预期证据 |
|---|---|
| branch push | CI run 链接；Desktop `checks` 与 `goland-plugin` 两个 job 分别通过。 |
| pull request | PR checks 链接；与 push 使用相同两套命令，且 token 没有发布写权限。 |
| `workflow_dispatch` | 人工 run 链接；不创建 artifact Release 或 tag。 |
| 合法版本 tag | Release run 与公开 Release 链接；validate、arm64、x64、publish 全部通过，仍只有三份 Desktop 资产，不含 plugin ZIP。 |
| 非法 `v*` tag | validate 失败；没有新建 Release。验证后按明确授权清理测试 tag。 |
| 版本不一致或非默认分支 commit | validate 在打包前失败；没有 draft 或公开 Release。 |
| 检查或任一架构失败 | publish 不运行；没有公开 Release。 |
| draft 上传或复验失败 | 尝试清理带本次运行标识的 draft，既有 Release 不变；API 无法确认或删除失败时输出人工处理告警。 |

## Release 资产复验

首次受控合法 tag 发布后必须下载 GitHub 上的三份资产，而非复用 runner 工作目录，并记录：

- `SHA256SUMS` 对两个 ZIP 的验证结果及文件大小。
- 两个 ZIP 各自只有一个顶层 `ReqWS.app`，解压后 bundle ID 为 `com.reqws.desktop`、版本等于 tag。
- arm64 资产主可执行文件为 arm64，x64 资产为 x86_64，且 `codesign --verify --deep --strict` 通过。
- Release 不再是 draft，资产集合无缺失或重复，运行日志未使用仓库自定义 secrets。
- 页面明确说明 ad-hoc、未公证和非 Gatekeeper-ready 限制。

## 退出标准与证据记录

实现合入前，本地检查和一次 branch/PR CI 必须通过。发布渠道只有在首次受控 tag 的双架构运行、远端资产复验和失败清理演练形成证据后，才能宣称已完成端到端验证。

按次证据写入本目录的 `verification-YYYY-MM-DD.md`，记录 commit/tag、Actions 与 Release 链接、命令、结果、资产哈希和证据缺口。尚未执行真实 tag 时，应明确保留该缺口，不以本地 package 替代发布成功证据。
