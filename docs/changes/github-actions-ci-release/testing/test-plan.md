---
title: GitHub Actions CI 与 Release 测试方案
type: test-plan
status: active
updated: 2026-09-19
---

# GitHub Actions CI 与 Release 测试方案

验证原有 CI 质量门禁完整保留，后续 tag 仅发布 arm64 app 与独立 GoLand plugin ZIP，且任何检查或资产失败都不能产生公开 Release。本方案不新增 IDE/语言功能测试，也不改变既有插件测试矩阵。

## 环境与边界

Desktop 与插件构建仍使用 `macos-15`，分别要求 Node 24 和 JDK 21；轻量 tag 校验与 publish 使用 Ubuntu。Python 3 标准库用于插件附件校验和回归测试，不新增 pip 依赖。Verifier 保留 GoLand 2026.1.3 与 2026.2。

不验证未实现的 Developer ID、公证、DMG、后台自动更新或 Marketplace 发布。个人签名、Gatekeeper 和手动应用内更新按[自更新 U1–U9](../../macos-self-update/technical-design.md#9-最小验证计划与发布门禁)验收；本地回归不读取正式 Secrets，不在未经明确授权时创建、移动或删除远端 tag，也不安装/重启真实 GoLand。

## 自动与本地场景

| 场景 | 方法与通过标准 |
|---|---|
| 完整项目基线 | `npm run check` 仍运行类型、lint、i18n、docs 和全部 Vitest；文档单独可用 `npm run docs:check`。 |
| Workflow 静态检查 | YAML、shell 语法、完整 SHA 引用、触发器、job 名称和最小权限正确；`publish.needs` 包含 validate/checks/package/goland-plugin。 |
| 应用 smoke | 保留 `npm ci` 和 arm64 package smoke，不安装、不发布；Release 归档后再次检查解压 bundle 的 ID、版本、arm64 架构和 codesign。 |
| 完整插件检查 | 保留 tests、project/structure、261/262 verifier、buildPlugin 及 forbidden-symbol 依赖；CI 与 Release 使用相同 releaseVersion 覆盖和附件校验路径。 |
| 插件附件回归 | `python3 -m unittest discover -s tests/workflows -p 'test_*.py' -v`；生成临时 ZIP/JAR，覆盖正确字节和 SHA-256、缺失/多余/损坏 ZIP、错误版本/ID、缺失/重复 descriptor、不安全路径、symlink、非法版本和覆盖保护。 |
| 版本和来源 | 覆盖合法版本、前导零、缺段、预发布后缀、三处版本不一致、默认分支不可达 commit；只允许完整一致且默认分支可达的 tag。 |
| 资产集合 | 中间文件恰好为 app/plugin ZIP、latest-mac.yml 及三份 checksum 片段；公开附件恰好四项。缺失、额外、篡改、摘要/size 不匹配、metadata 引用插件等均失败；draft 重新下载验证后才公开。 |
| 缓存隔离 | npm/Gradle 原有缓存保留；Electron key 绑定 OS/arch/lock；IDE key 绑定 OS/arch/toolchain。不得恢复 sandbox、发布产物或明文 configuration-cache。 |

插件只检查自己的交付契约；本变更不将 `go test`、Go module 下载或 IDE 原生 Git 能力新增为发布测试。

## GitHub 事件与失败路径

branch push、PR 和 dispatch 都应保留两个原有命名的 CI 检查。合法 tag 的 validate 通过后，Desktop checks、arm64 app package 和 plugin checks/package 并行启动；任何一条失败或取消，publish 都不执行。版本不一致或默认分支不可达时，在这些并行任务和 draft 创建前失败。

发布仍拒绝既有 Release；上传或远端复验失败时，只尽力清理带本次 run/attempt 标识且仍为 draft 的 Release。无法确认归属或删除失败必须告警，不能影响既有 Release/tag。

## 真实 Release 复验

首次受控 tag 发布后，从 GitHub 下载四份公开资产，而非复用 runner 工作目录，并记录：

- 两份 ZIP 和 latest-mac.yml 的 SHA-256、大小、精确名称，并验证 metadata 的 SHA-512/Base64、size 只对应 Desktop ZIP；确认不存在 x64 app 附件。
- app ZIP 解压为完整 `ReqWS.app`，bundle ID 为 `com.reqws.desktop`、版本等于 tag、主可执行文件纯 arm64，codesign 结构检查通过。
- plugin ZIP 保留磁盘安装格式，唯一 ReqWS descriptor 的 ID 为 `com.reqws.workspace`、版本等于 tag；单独记录实际 GoLand 安装/GUI 验收是否执行。
- Release 已从 draft 公开，资产集合完整且非空，页面保留个人自签名/未公证和 unsigned 插件限制。

## 性能验证与退出标准

使用相同工具链比较冷/热缓存运行，分别记录 IDE/Electron cache-hit、下载/解压、Gradle 执行、cache post-action、排队和总耗时；不能把 runner 差异或减少检查当作提速。首次填充缓存可能更慢，应明确区分。

合入前需要本地可执行检查及 branch/PR CI 通过。真实 tag 发布、远端复验和 draft 失败清理演练未执行时必须保留缺口，不宣称端到端发布已验证。按次证据使用 `verification-YYYY-MM-DD.md`，记录 exact commit、Actions/Release、命令、结果和真实缺口；历史 v0.1.1 双架构证据保持原样。
