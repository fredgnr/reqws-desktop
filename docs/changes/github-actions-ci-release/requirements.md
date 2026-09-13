---
title: GitHub Actions CI 与 Release 需求说明
type: requirements
status: active
updated: 2026-09-13
---

# GitHub Actions CI 与 Release 需求说明

本需求为所有分支变更建立一致的自动检查，并将默认分支上的有效版本 tag 转换为可校验的 Apple silicon macOS 应用和独立 GoLand 插件资产。

## 背景与目标

push、pull request 和人工检查使用同一质量基线；tag 发布具有明确的版本、来源、产物和失败语义。优化下载缓存与 Release 并行度，但不得为提速移除现有 CI 检查、测试、Verifier 目标或安全门禁。

## 范围与非目标

范围包括所有 branch push、`pull_request`、`workflow_dispatch` 的 Desktop/GoLand CI，以及默认分支有效版本 tag 的 arm64 macOS app ZIP、GoLand plugin ZIP、SHA-256 清单和事务化 GitHub Release。

后续 Release 不再构建或发布 x64/x86_64 Desktop app；既有版本的 Intel 资产不删除，本地 package 脚手架的架构参数不在本次移除范围内。插件作为单独附件提供，不嵌入 Electron app，不自动安装、不签名、不上传 JetBrains Marketplace。

不包括 Developer ID 签名、Apple 公证、DMG、自动更新、Windows/Linux 应用构建，也不自动配置 GitHub branch protection。必需检查是否阻止合并仍由仓库规则管理。

## 触发与质量规则

1. 所有 branch push、`pull_request` 和人工 `workflow_dispatch` 保留两个独立 job：Desktop `checks` 与 `goland-plugin`，以及原有可见检查名称。
2. Desktop 在 `macos-15`、Node.js 24 上执行 `npm ci`、完整 `npm run check` 和 arm64 package smoke；新增发布脚本回归测试。package smoke 复用依赖和检查，不安装应用或创建 Release。
3. GoLand 在 `macos-15`、JDK 21 上校验 Gradle wrapper，执行 `test`、`verifyPluginProjectConfiguration`、`verifyPluginStructure`、GoLand 2026.1.3/2026.2 的 `verifyPlugin` 和 `buildPlugin`，并保留后者依赖的 `verifyForbiddenProductionSymbols`。CI 还验证候选 ZIP 的真实 ID、版本和校验和生成；不上传发布资产，不依赖 Desktop `node_modules`。
4. Release 仍由 `v*` tag push 触发，只接受无前导零的 `vMAJOR.MINOR.PATCH`。版本必须同时等于 `package.json`、`package-lock.json` 顶层及根 package 版本；tag commit 必须可从默认分支到达。
5. 轻量 `validate` 通过后，完整 Desktop 检查、arm64 app 打包和完整 GoLand 检查/打包并行执行。`publish` 必须等待四个前置 job 全部成功；打包中的 `--skip-check` 只避免重复执行，不豁免独立检查门禁。
6. 发布插件的 Gradle project version、内嵌 `META-INF/plugin.xml` 版本和资产文件名必须与 tag 版本一致，plugin ID 必须为 `com.reqws.workspace`。CI 使用项目版本演练相同的覆盖和校验路径；本地无参数构建仍保留原有插件默认版本。
7. 每次发布恰好包含 `ReqWS-<version>-macos-arm64.zip`、`ReqWS-<version>-goland-plugin.zip` 和覆盖两份 ZIP 的 `SHA256SUMS`。
8. 发布阶段精确核对中间资产与校验和，先创建带本次运行标识的 draft，远端复验附件集合和非空大小后才公开。失败只能尽力清理可确认属于本次运行且仍为 draft 的 Release，不覆盖既有 Release 或 tag。

## 安全与失败语义

- npm、Electron 下载和 Gradle/IDE 缓存仅用于复用依赖；仍执行 `npm ci` 和全部检查命令。缓存未命中时从头构建，不允许将失败变成成功。
- Electron 缓存按 OS、CPU 架构和锁文件隔离；GoLand IDE 缓存按 OS、CPU 架构及 Gradle 工具链/配置隔离，不缓存 sandbox、发布输出或明文 configuration-cache。
- 不新增仓库自定义 secrets；默认 `contents: read`，仅发布 job 拥有 `contents: write`。所有第三方 Actions 固定完整 SHA。
- 任一检查、应用/插件打包、资产校验或发布失败均不得产生降级公开 Release。
- `.app` 仍为 ad-hoc 签名、未经公证，不保证通过其他 Mac 的 Gatekeeper；页面和交付说明必须保留限制。插件 ZIP 不代表真实 IDE GUI 验收已经完成。

## 验收条件

所有原有 CI 任务和两个 Verifier 目标继续执行；合法 tag 仅在完整检查和两种不同产品的打包均成功后发布。应用 ZIP 解压后版本、arm64 架构、bundle ID 和签名结构正确；插件 ZIP 可作为磁盘安装包，其内嵌 ID/版本与发布契约一致。缺失、额外、损坏或版本不一致的附件必须阻止发布。

缓存收益需通过相同工具链的冷/热运行测量，不以配置存在代替命中证据或承诺固定提速。真实 tag 发布和真实 GoLand GUI 验收须分别记录，不能由单元测试或 ZIP 构建代替。

## 关联文档

实现决策见[技术方案](technical-design.md)，验证范围见[测试方案](testing/test-plan.md)，资产使用与限制见[交付说明](delivery.md)。
