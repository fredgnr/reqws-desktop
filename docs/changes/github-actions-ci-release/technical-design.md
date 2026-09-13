---
title: GitHub Actions CI 与 Release 技术方案
type: technical-design
status: active
updated: 2026-09-13
---

# GitHub Actions CI 与 Release 技术方案

两个最小权限工作流复用现有检查和 package 脚手架。优化只调整依赖缓存和 Release DAG，不减少检查内容；draft、资产复验和失败清理继续防止半成品公开。

## 工作流划分

CI 保留 `checks`（Checks and macOS package smoke）与 `goland-plugin`（GoLand plugin checks）两个独立 macOS job。branch push、PR 和 dispatch 触发器、原有 concurrency 与取消策略保持不变，不通过路径过滤或忽略失败提速。

Desktop 保留 Node 24、`npm ci`、完整 `npm run check` 和 arm64 package smoke，另执行 Python 标准库发布脚本回归测试。GoLand 保留 JDK 21、Gradle wrapper validation 和以下任务：

```bash
./gradlew test verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin buildPlugin --no-daemon -PreleaseVersion="$PLUGIN_VERSION"
```

`buildPlugin` 仍依赖 `verifyForbiddenProductionSymbols`；GoLand 2026.1.3 与 2026.2 均参与 Verifier。CI 从 `package.json` 读取候选版本，构建后运行与 Release 相同的 ZIP 校验脚本，但不上传或发布资产。根 `npm run check`、Electron package 和 Gradle 仍相互隔离。

`.github/actions/setup-goland/action.yml` 在 CI/Release 中共享 Temurin 21、wrapper validation、Gradle setup 和 IDE 缓存配置，避免两个入口漂移。所有第三方 Actions 继续固定到完整 commit SHA。

## 缓存策略

| 缓存 | 所有者与路径 | 隔离和失效 |
|---|---|---|
| npm 下载 | `actions/setup-node`，npm 默认缓存 | `package-lock.json`；每个 job 仍独立 `npm ci`，不共享 `node_modules`。 |
| Electron 下载 | `actions/cache`，`~/Library/Caches/electron` | OS、runner 架构、完整锁文件 hash；在安装依赖前恢复。 |
| Gradle 依赖和任务缓存 | `gradle/actions/setup-gradle` | 保留 action 默认的缓存清理和默认分支写入策略，不再叠加同路径缓存。 |
| 解压后的 GoLand IDE | `actions/cache`，`integrations/goland/.intellijPlatform/ides` | OS、runner 架构、Gradle build/settings/properties/wrapper 配置 hash；精确 key，不用宽泛 fallback。 |

IntelliJ Platform Gradle Plugin 2.18.1 的 `caching.ides.enabled = true` 启用稳定 IDE 缓存位置。只保存 IDE 依赖，不保存整个 `.intellijPlatform`、测试 sandbox、构建报告或发布 ZIP。源码变化不必重新创建 IDE 缓存；工具链变化会失效。

现有 `org.gradle.caching=true` 和 configuration-cache 设置保留，但不为持久化配置缓存新增 secret，也不把可能含敏感环境值的 configuration-cache 明文上传。缓存只是加速层：下载失效或缓存未命中不跳过任何验证。首次填充存在下载、解压和上传成本，不能承诺首跑更快。

## Release 校验与构建

```text
validate（tag / 三处版本 / 默认分支 ancestry）
  ├── checks（npm ci / 发布脚本回归 / 完整 npm run check）
  ├── package（arm64 .app / 归档 / 解压复验 / checksum）
  └── goland-plugin（完整插件检查 / ZIP / 内嵌版本复验 / checksum）
        ↓ 三条路径均成功，且 validate 成功
      publish（资产集合 / checksum / draft / 远端复验 / 公开）
```

`validate` 使用 Ubuntu 和 Node 24，只进行原有严格 tag 正则、三处版本一致性和完整 Git 历史 ancestry 校验。其余三个 job 在 `macos-15` 独立运行；插件的 macOS 文件系统测试没有迁移到 Linux。`publish.needs` 显式列出所有四个前置 job，不使用 `always()` 或 `continue-on-error` 绕过失败。

应用固定 `ARCH=arm64`，删除 Intel matrix。现有 package 脚手架仍负责 bundle 校验；Release 继续用 `ditto` 保留 bundle、资源与符号链接，解压后重新验证 codesign、bundle ID、版本和纯 arm64 主可执行文件。

## 插件版本与 ZIP

Gradle 的 `releaseVersion` property 覆盖 project/plugin 版本；没有参数的本地构建仍用 `0.1.0`。Release 将已验证 tag 版本传入完整检查与构建，不能只重命名旧版本 ZIP。

`scripts/prepare-goland-release.py` 使用 Python 标准库读取恰好一个 `build/distributions/*.zip`，验证 ZIP/JAR 完整性、路径和唯一插件描述文件，核对 `com.reqws.workspace` 与预期版本。校验的是将要复制的同一份字节，不解压到工作区；额外/缺失/损坏 ZIP、重复描述文件、错误 ID/版本或已有目标文件均失败。

校验后保存 `ReqWS-<version>-goland-plugin.zip` 和对应 `.zip.sha256`。插件与应用分别上传 `release-goland-plugin`、`release-arm64` 中间 artifact；二者使用零额外压缩和 7 天保留期。插件不嵌入 app，不上传 Marketplace，不自动安装。

## 资产汇总与发布事务

发布 job 只接受两个约定 ZIP 和两个 checksum 片段，拒绝缺失或额外文件，生成并反向校验 `SHA256SUMS`。公开附件恰好为两个 ZIP 和一个清单。

保留既有事务：拒绝覆盖既有 Release；用内置短期 token 创建带 run/attempt 标识的 draft；上传后远端检查精确附件集合、非空大小和 draft 状态；通过后才公开。失败清理同时核对 draft 与本次运行标识；无法确认或删除失败则告警，不删除 tag 或人工创建的 Release。

默认权限仍为 `contents: read`，只有 tag 触发的 `publish` 获得 `contents: write`，不读取自定义 secrets。页面区分 arm64 app 的 ad-hoc/未公证限制与 GoLand 插件的 unsigned/磁盘安装方式。

## 测试与回滚

2026-09-13 的基线 main CI run `34757091480`：Desktop job 约 56 秒，GoLand job 约 272 秒，其中 Gradle 执行约 205 秒，Gradle post-action 约 53 秒。日志有依赖/transform 缓存未命中和缓存写入冲突；因此优先改善 IDE 复用，而不拆散很短的 Desktop 检查。基线见 [Actions run](https://github.com/fredgnr/reqws-desktop/actions/runs/34757091480)。

这些是旧流程测量，不是新流程提速结果。后续分别记录冷缓存和热缓存的命中、下载、验证、cache post 与总耗时，再评估是否需要进一步拆分 Verifier。Release 并行可缩短等待链，但会同时占用更多 runner，不能替代实际测量。

回滚需要共同回退 workflow、共享 action、Gradle 缓存/版本配置和发布脚本；已经发布的历史资产与 tag 不自动修改。测试和交付方式分别见[测试方案](testing/test-plan.md)和[交付说明](delivery.md)。
