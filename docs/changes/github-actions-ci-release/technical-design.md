---
title: GitHub Actions CI 与 Release 技术方案
type: technical-design
status: active
updated: 2026-08-17
---

# GitHub Actions CI 与 Release 技术方案

本方案以两个最小权限工作流复用现有检查和 package 脚手架，并用 draft、资产复验和失败清理保证 Release 不会半成品公开。

## 现状与约束

- 项目要求 Node.js 24，锁定依赖入口为 `npm ci`，完整质量基线为 `npm run check`。
- `npm run package:macos` 会执行 clean install、完整检查、Forge package 和 bundle 校验；CI 已完成前两步时，可通过脚手架的 skip 参数避免重复执行。
- Forge 当前只配置本机 ad-hoc 签名并关闭 Hardened Runtime。它可以验证 bundle 内部一致性，但不构成 Developer ID、公证或 Gatekeeper 公共分发能力。
- GitHub-hosted macOS 的 arm64 与 x64 架构使用不同 runner，构建结果不能依赖跨 job 的 `node_modules` 或 `out/`。
- GoLand plugin 位于独立 Gradle project；根 `npm run check` 和 Electron package 不下载 IDE SDK，也不复用 plugin Gradle cache 或 build output。

## 工作流划分

| 工作流 | 触发 | Runner | 职责 |
|---|---|---|---|
| CI / `checks` | 所有 branch push、`pull_request`、`workflow_dispatch` | `macos-15` | Node 24 下安装依赖、运行 Desktop 完整检查并执行 arm64 package smoke。 |
| CI / `goland-plugin` | 同上 | `macos-15` | JDK 21 下校验 Gradle wrapper，运行 plugin tests、project/structure checks、261/262 Plugin Verifier 和 ZIP build。 |
| Release | `v*` tag push | validate 使用 `macos-15`；package 使用 `macos-15` 与 `macos-15-intel` | 验证 tag 来源和版本、重跑检查、构建双架构资产并事务化发布。 |

两个 CI job 都只授予 `contents: read`，且彼此不传递依赖或 artifact。同一 ref 的后续 CI 运行可取消未完成的旧运行，避免陈旧结果占用 runner；pull request 与 branch push 仍分别保留可见检查。

### GoLand plugin job

plugin job 使用固定完整 SHA 的 `actions/setup-java` 配置 Temurin 21，并用固定 SHA 的 `gradle/actions` 完成 wrapper validation 和 Gradle setup。随后在 `integrations/goland/` 执行：

```bash
./gradlew test verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin buildPlugin --no-daemon
```

构建基线为 IntelliJ Platform Gradle Plugin 2.18.1、Gradle 9.3.0、Kotlin 2.3.20、GoLand 2026.1.3 和 `since-build: 261`。`verifyPlugin` 下载并校验 GoLand 2026.1.3 与 2026.2；configuration/structure check 不能代替该矩阵。120 分钟 timeout 只限制单个 job，不允许 `continue-on-error`。

`buildPlugin` 产物只用于当前 CI job 的构建证明，不上传为长期 artifact。Release workflow 没有 plugin dependency，资产集合、签名限制、draft 事务和失败清理逻辑全部保持不变。

## Release 校验与构建

1. checkout 必须取得完整历史，以便把 tag 解引用为 commit，并用 Git ancestry 判断其是否属于事件载荷声明的默认分支。
2. validate 使用严格正则解析 `vMAJOR.MINOR.PATCH`，去除 `v` 后与 `package.json`、`package-lock.json` 根项目版本比较。缺失字段、前导零、预发布后缀或任一不一致均 fail closed。
3. validate 在 Node 24 上执行 `npm ci` 和 `npm run check`。package jobs 仅在它成功后启动。
4. 每个 package job 都从锁文件独立执行 `npm ci`，再以 skip-ci、skip-check 模式调用现有 `package:macos` 脚手架并传入 runner 对应架构。脚手架继续负责 `.app` 的版本、bundle ID、Mach-O 架构和签名结构校验。
5. macOS 使用保留 bundle 目录、符号链接与资源属性的归档方式，将 `.app` 包装为 `ReqWS-<version>-macos-<arch>.zip`；架构 job 只上传供本次 workflow 使用的中间 artifact。

## 资产汇总与发布事务

发布 job 依赖 validate 和两个 package jobs，下载资产到全新目录后执行以下步骤：

1. 按精确文件名和数量检查 arm64、x64 ZIP，拒绝额外或缺失文件。
2. 计算两份 ZIP 的 SHA-256，生成排序稳定、使用相对文件名的 `SHA256SUMS`，随后本地反向验证清单。
3. 确认目标 tag 尚无 Release，再通过 `gh` 创建带隐藏 workflow run 标识的 draft Release，并在调用前记录创建尝试，以覆盖服务端创建成功但客户端未收到响应的情况。
4. 上传三份资产，从 GitHub Release API 重新查询 draft 的资产名称与大小，确认集合完整且非空。
5. 只有复验通过才把 draft 转为公开 Release。失败处理只在“本次运行已创建且仍为 draft”时删除该 Release；既有 Release 一律不覆盖、不清理。

失败清理属于安全的 best effort：工作流必须同时核对 draft 状态和隐藏运行标识，不能因同 tag 出现人工创建的 Release 而误删。GitHub API 无法确认状态或删除失败时保留 draft 并输出告警，由维护者核对运行日志后人工处理。

Git tag 是版本事实源，Release 标题使用同一 tag。package job 与发布 job 之间只传递 ZIP 资产及其校验片段，不传递 token；发布 job 是唯一配置 `contents: write` 的 job，并使用内置 `github.token`，不读取自定义 secrets。

## 供应链与并发控制

- `actions/checkout`、`actions/setup-node`、artifact 上传/下载等第三方 Action 全部固定到完整 commit SHA；版本升级应作为可审查的代码变更。
- CI plugin job 的 `actions/setup-java`、Gradle wrapper validation 和 setup action 同样固定到完整 commit SHA。
- Release 按 tag 设置并发组且不自动取消正在发布的运行，避免两次运行互相删除 draft。创建前检查既有 Release，使重跑在人工确认前 fail closed。
- shell 使用严格错误处理，所有路径和 tag 参数都引用。pull request 来源代码会执行项目检查，但对应 job 始终只有读权限；拥有写权限的发布 job 只由 tag 事件触发。
- npm 缓存只用于下载加速，依赖安装仍以锁文件和 `npm ci` 为准，缓存命中不能跳过校验。

## 测试与回滚

静态和本地验证覆盖 YAML、现有检查、package 与归档；真实 GitHub 事件覆盖权限、runner 架构、artifact 和 Release API。详细场景见[测试方案](testing/test-plan.md)。工作流回滚是回退对应 YAML；已发布错误版本不移动 tag，应删除错误 Release、按项目版本规则修复后创建新 tag。用户资产回滚见[交付说明](delivery.md)。
