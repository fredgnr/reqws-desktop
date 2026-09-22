---
title: GitHub Actions CI 与 Release 技术方案
type: technical-design
status: active
updated: 2026-09-22
---

# GitHub Actions CI 与 Release 技术方案

两个最小权限工作流复用现有检查和 package 脚手架。优化只调整依赖缓存和 Release DAG，不减少检查内容；draft、资产复验和失败清理继续防止半成品公开。

## 工作流划分

CI 保留 `checks`（Checks and macOS package smoke）与 `goland-plugin`（GoLand plugin checks）两个必需汇总名称。`impact` 分类新增/删除/重命名两端路径，docs-only 仅运行 `docs:check`；未知改动保守进入完整门禁。汇总使用 `always()` 检查所有适用子任务的实际结果，失败、取消和意外跳过不能变绿。push/PR/dispatch、concurrency 与取消策略保持不变。

Desktop 保留 Node 24、`npm ci`、完整 `npm run check` 和 arm64 package smoke，另执行 Python 标准库发布脚本回归测试。GoLand 保留 JDK 25、Gradle wrapper validation 和以下任务：

```bash
./gradlew test verifyBaselineTestReports verifyForbiddenProductionSymbols verifyPluginProjectConfiguration verifyPluginStructure verifyPlugin exportPluginArchivePath --no-daemon -PreleaseVersion="$PLUGIN_VERSION"
```

`buildPlugin` 依赖生产禁用 API 和 descriptor 策略检查。基线 SDK 为 GO 2026.2，descriptor 只有 `since-build="262"`。CI 从 `package.json` 读取版本，保留编译和全部单元/Light/Heavy 平台覆盖，构建后经 Release ZIP 校验路径导出 `plugin.zip` 与 `version.txt`。独立 job 冻结官方 API 集合，`goland-verification.yml` 对同一 ZIP 执行最多两并发的 API 矩阵。完整 GoLand Starter/Driver 已移到本机，任何 CI 工作流都不启动它或请求 IDE 授权。影响分类仅提示本机集成建议，不触发用户电脑。首次迁移未验收前 PR 保守使用完整正式集合，不能提前退为抽样。

`.github/actions/setup-goland/action.yml` 共享 Temurin 25、Wrapper validation、Gradle setup 和独立 installer 缓存；版本从 `compatibility.properties` 读取。所有第三方 Actions 继续固定到完整 commit SHA。开发状态与分轮执行结果分别见[开发记录](../ide-plugin-compatibility-automation/implementation-2026-09-21.md)和[验证记录](../ide-plugin-compatibility-automation/verification-2026-09-21.md)，不能把实现描述或日志心跳当作验收通过。

## API 分片长时间无输出的诊断

`goland-verification.yml` 以无缓冲 Python 启动 Verifier 包装器；Gradle 使用 `--console=plain --info --stacktrace`，原始输出实时送到 Actions 并完整保留在 `api-results/<target>/gradle.log`。目标开始、Gradle 退出和兼容终态分别标记；每 30 秒的心跳给出 PID、运行/静默时长、日志字节数及最后一个 Gradle task，不能把心跳当成验证通过。

连续 5 分钟无输出时，在同目录的 `diagnostics/` 保存仅属于本次进程组的进程状态及 Java 线程栈；静默采集最多三轮，90 分钟超时前额外采集一次。每次 Java attach 有 15 秒上限，最多四个 JVM；诊断失败不吞掉原始失败，也不重启任务、放宽超时或跳过目标。`progress.json` 保留当前阶段，`result.json` 记录最终终态、起止时间和耗时。现有 `always()` artifact 步骤收集这些文件，不上传 SDK/依赖缓存，不启用 debug 或环境变量转储。

若运行中的 job 日志下载返回 404，应先看 Actions 实时步骤输出；日志归档尚不可用本身不是卡死证据。下载/解压和 Verifier 活跃计算从 info 输出、日志增长及进程/线程样本判断，缺终态仍不能通过。本改动不改变最低 262、固定 UI 代表版本、冻结目标清单、最多两个 API 重任务并发或候选 ZIP 一致性约束，不启动完整 IDE。

## 缓存策略

| 缓存 | 所有者与路径 | 隔离和失效 |
|---|---|---|
| npm 下载 | `cache-dependency`，`~/.npm/_cacache` | Node 主版本与锁定下载集合；每个 job 独立 `npm ci`，不共享 `node_modules`。 |
| Electron 下载 | `cache-dependency`，`~/Library/Caches/electron` | OS、runner 架构、锁定 Electron 版本；在安装依赖前恢复。 |
| Gradle 依赖和任务缓存 | `gradle/actions/setup-gradle` | 保留 action 默认的缓存清理和默认分支写入策略，不再叠加同路径缓存。 |
| GoLand installer | `cache-dependency`，Gradle 的 `go/goland` 或 `com.jetbrains.intellij.goland/goland` 指定版本目录 | 产品、发行版本、OS/架构；精确 key，不缓存解压后的 IDE 或测试 sandbox。 |

IntelliJ Platform Gradle Plugin 2.18.1 的 `caching.ides.enabled = true` 供单次工作目录复用解压 SDK；共享 cache 只保存依赖与指定 installer。仅默认分支 opt-in push/manual 写共享缓存，PR/Release/API/定期任务只读。完整集成使用本机专用授权 profile 与每轮新业务目录，profile/config 不进 CI cache/artifact；高版本下载不累积到共享 installer cache。源码和 API 集合变化不改变编译 installer key。

现有 `org.gradle.caching=true` 和 configuration-cache 设置保留，但不为持久化配置缓存新增 secret，也不把可能含敏感环境值的 configuration-cache 明文上传。缓存只是加速层：下载失效或缓存未命中不跳过任何验证。首次填充存在下载、解压和上传成本，不能承诺首跑更快。

## Release 校验与构建

```text
validate（tag / 三处版本 / 默认分支 ancestry）
  ├── checks（npm ci / 发布脚本回归 / 完整 npm run check）
  ├── package（arm64 .app / 归档 / 解压复验 / checksum）
  ├── plugin-targets（新鲜完整正式 API 集合）
  └── goland-plugin（基线检查 / 单次 ZIP / 作者签名 / 内嵌版本复验 / checksum）
        └── plugin-verification（最终签名 ZIP 的完整 API 集合，无完整 IDE）
        ↓ 应用、项目检查、插件平台检查与同产物 API 验证均成功
      publish（资产集合 / checksum / draft / 远端复验 / 公开）
```

`validate` 使用 Ubuntu 和 Node 24，保留 tag、版本与 Git ancestry 校验。构建/平台测试和 API job 在 `macos-15`，没有完整 IDE job。`publish.needs` 保留最终 `plugin-verification`，不绕过失败，公开前核对最终签名 ZIP 的 `scope=ci-api` 证据及摘要；证据明确标记本机集成 `not-run`，CI 成功不等于本机 UI 通过。本机报告另经[专用入口](../ide-plugin-compatibility-automation/local-integration.md)产生，必须对应同一最终签名 ZIP。

应用固定 `ARCH=arm64`，删除 Intel matrix。现有 package 脚手架仍负责 bundle 校验；Release 继续用 `ditto` 保留 bundle、资源与符号链接，解压后重新验证 codesign、bundle ID、版本和纯 arm64 主可执行文件。

## 插件版本与 ZIP

Gradle 的 `releaseVersion` property 覆盖 project/plugin 版本；没有参数的本地构建用当前默认 `0.1.5`。Release 将已验证 tag 版本传入检查、单次构建和签名，不能只重命名旧版本 ZIP。

`scripts/prepare-goland-release.py --input` 使用 Gradle 导出的精确 ZIP 路径，验证 ZIP/JAR 完整性、路径、唯一主 descriptor、262 下限/无上限、GoLand 依赖、`com.reqws.workspace` 与预期版本。校验并复制同一份字节，不解压到工作区；缺失/损坏 ZIP、重复描述文件、错误 ID/版本或已有目标文件均失败，不按 glob 猜测候选。

校验后保存 `ReqWS-<version>-goland-plugin.zip` 和对应 `.zip.sha256`。插件与应用分别上传 `release-goland-plugin`、`release-arm64` 中间 artifact；二者使用零额外压缩和 7 天保留期。签名插件另提供给兼容消费者，证据保留 14 天。插件不嵌入 app、不自动安装；Marketplace 后置提交遵循[市场方案](../goland-plugin-marketplace/README.md)。

## 资产汇总与发布事务

发布 job 使用 `verify-release-assets.py` 接受两个约定 ZIP、`latest-mac.yml` 及三份 checksum 片段，拒绝缺失或额外文件，验证 Desktop 元数据与最终 ZIP 后生成 `SHA256SUMS`。公开附件恰好为两个 ZIP、更新元数据和清单。固定身份签名、临时钥匙串及秘密隔离详见[自更新方案](../macos-self-update/technical-design.md)。

保留既有事务：拒绝覆盖既有 Release；用内置短期 token 创建带 run/attempt 标识的 draft；上传后远端检查精确附件集合、非空大小和 draft 状态；重新下载全部附件，与本地清单比较并验证 SHA-256、SHA-512/size/版本后才公开。失败清理同时核对 draft 与本次运行标识；无法确认或删除失败则告警，不删除 tag 或人工创建的 Release。

默认权限仍为 `contents: read`，只有 tag 触发的 `publish` 获得 `contents: write`；只有 Desktop package 的受保护签名步骤读取 P12/密码 Secrets，发布 job 不读取私钥。页面区分 arm64 app 的个人自签名/未公证限制与 GoLand 插件的 unsigned/磁盘安装方式。

## 测试与回滚

2026-09-13 的基线 main CI run `34757091480`：Desktop job 约 56 秒，GoLand job 约 272 秒，其中 Gradle 执行约 205 秒，Gradle post-action 约 53 秒。日志有依赖/transform 缓存未命中和缓存写入冲突；因此优先改善 IDE 复用，而不拆散很短的 Desktop 检查。基线见 [Actions run](https://github.com/fredgnr/reqws-desktop/actions/runs/34757091480)。

这些是旧流程测量，不是新流程提速结果。后续分别记录冷缓存和热缓存的命中、下载、验证、cache post 与总耗时，再评估是否需要进一步拆分 Verifier。Release 并行可缩短等待链，但会同时占用更多 runner，不能替代实际测量。

回滚需要共同回退 workflow、共享 action、Gradle 缓存/版本配置和发布脚本；已经发布的历史资产与 tag 不自动修改。测试和交付方式分别见[测试方案](testing/test-plan.md)和[交付说明](delivery.md)。
