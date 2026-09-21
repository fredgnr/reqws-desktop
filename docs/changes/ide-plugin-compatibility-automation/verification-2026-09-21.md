---
title: IDE 兼容自动化验证记录
type: test-report
status: active
updated: 2026-09-22
---

# IDE 兼容自动化验证记录

本记录补充 2026-09-21 至 09-22 用户授权执行的测试与回归，不改写此前两轮仅开发的记录。本机自动检查最终通过；完整 IDE 因专用授权准备缺失而阻塞，远端 CI/Release 工作流本轮没有触发，不作完整 V 验收结论。

## 范围与环境

验证对象为本次提交中的兼容策略、Gradle 配置、本机 Starter/Driver 宿主、工作流和候选报告工具。最低系列仍为 262，编译 SDK 为 GO 2026.2 / 262.8665.270，完整 IDE 代表版本保持 GO 2026.2.1.1 / 262.9437.286。没有增加多版本 GUI 回归。

本机使用 Node 24.20.0、npm 11.19.0、Python 3.9、Gradle Wrapper 9.3.0 和已有 Java 25 运行时；签名测试使用 OpenSSL 3、缓存的 ZIP Signer 0.1.43 和临时证书。未读取生产签名私钥、未修改系统信任、未安装或重启日常 IDE、未操作真实工作区。

## 已执行检查与修复

| 检查 | 结果 |
|---|---|
| `npm ci --no-audit --no-fund` | 按现有 lockfile 安装项目测试依赖，没有更改锁文件。 |
| `npm run check` 的 typecheck、lint、i18n、docs 阶段 | 通过；文案资源保持 337 keys 一致。 |
| `npm test` | 45 个文件通过；459 项通过，1 项仅托管 macOS CI 执行的信任清理用例跳过。沙箱首轮 macOS 临时证书测试受限，允许系统测试操作后重跑通过。 |
| `python3 -m unittest discover -s tests/workflows -p 'test_*.py' -v` | 最终 110 项全部通过；早期为 108 项，随后新增缓存伪报告和跨目标替换 ZIP 反例。显式使用 OpenSSL 3 和缓存 ZIP Signer；覆盖发布字节、签名反例、目标冻结/汇总及 profile 隔离。 |
| `actionlint` 1.7.12 | 全部工作流静态检查通过。 |
| 官方正式目录 `resolve --mode full` | 冻结 7 个必需目标；解析成功本身不代表 API 检查通过。 |
| `compileKotlin compileTestKotlin compileIntegrationTestKotlin` | 全部通过；最低编译 SDK 与本机 Starter/Driver 宿主分别编译，未启动完整 IDE。 |
| `test verifyBaselineTestReports exportPluginArchivePath --no-configuration-cache` | 首次 37 个测试类、364 项执行；信任兼容修复后的完整重跑为 366 项执行，均零跳过、零失败。包括 `ManagedRootsAdapterTest` 的 25 项 Heavy 平台测试、`ReqwsProjectServiceTest` 的 82 项，以及安全、所有权、并发和恢复覆盖。 |
| 产物门禁 | `verifyForbiddenProductionSymbols`、`verifyPluginProjectConfiguration`、`verifyPluginStructure`、`verifyCompatibilityDescriptor` 通过；构建并导出未签名 `reqws-goland-0.1.5.zip`。 |
| 本机 API SDK 反例（真实 Gradle `help` 配置） | 错误产品、错误 build、重复同版本 SDK 三种输入均按预期拒绝。 |
| 本机入口授权阻塞探针 | 对实际候选 ZIP 和全新临时 profile 调用 `run_local_ide.py run`，退出 2，报告 `AUTHORIZATION_PREPARATION_REQUIRED`；没有启动 IDE，没有误记通过。 |
| 最终 `npm run check:goland` | 串行执行成功，退出 0；包括宿主编译、366 项测试、全部产物门禁、保留的两版直接检查、7 个冻结目标及同一候选 ZIP 汇总。 |

实际回归修复了新增 Release 目标冻结 job 缺少发布上下文日志，以及 Gradle Kotlin DSL 的 `java.time` 名称遮蔽。打包审阅还关闭了默认 `buildSearchableOptions`：插件没有 Settings configurable，无需启动 IDE 索引选项，CI 打包不得成为隐式完整 IDE 启动入口。

最终审阅将每周两组 API 矩阵改为分阶段执行，避免单轮出现四个 worker；main 失败仍允许已发布候选独立检查。修改后定向重跑兼容/矩阵契约测试及 actionlint，通过。CI 与本机完整检查同时编译 Starter/Driver 宿主，只有完整 IDE 的执行留在本机。

JetBrains 缓存代理出现 TLS 握手失败时，本轮使用固定 Gradle 插件的 `-Porg.jetbrains.intellij.platform.useCacheRedirector=false` 直连官方依赖源；未更换依赖版本、未降低 TLS 或产物校验要求。

最终插件门禁的首次调用在等待重复 IDE 安装包下载时主动中止（退出 130，不计通过）。随后将 API 分发改为官方 Maven SDK，并加入仅影响 API 的显式本机 SDK 复用：本轮提供已核对 ProductInfo 的最低 SDK 与现有固定代表版 SDK，均只读访问；未读取其账号配置、未启动日常 IDE。其他冻结目标仍由官方分发解析。

后续 Verifier 在线启动曾因共享缓存中无关 Marketplace 插件的元数据请求 TLS 失败而退出；该调用没有兼容通过结论。实现将 Verifier home 隔离到项目构建目录下的专用缓存，不访问用户共享 Verifier 插件缓存，保留在线依赖解析与所有 API 失败级别。缓存按工具的插件 update ID 复用，并与每次重新生成的 verdict 目录分开；CI 上传排除缓存，只接受固定报告位置的逐目标终态，新增反例证明解包内容不能伪装验证报告。

一次本轮额外的并行 SDK 预取与 2026.2.2 验证发生缓存初始化竞争：`product-info.json` 尚未就绪，目标报基础设施阻塞，整批退出 1。原结果保留，未改写为通过；预取结束后不再增加并行预取，重新冻结清单并串行执行完整门禁，最终全部通过。本机完整入口增加统一汇总，拒绝各目标单独成功但期间被替换的候选 ZIP。

## 最终 API 结果与证据

以下均属于最后一次串行运行的未签名 `reqws-goland-0.1.5.zip`。逐目标报告验证 ProductInfo、版本及同一候选/快照，七个退出码均为 0，汇总通过；没有借用信任修复前的旧 ZIP 结果。

| GoLand 版本 | 实际 build | API 终态 |
|---|---|---|
| 2026.2 | 262.8665.270 | passed |
| 2026.2.0.1 | 262.8665.336 | passed |
| 2026.2.1 | 262.9437.195 | passed |
| 2026.2.1.1 | 262.9437.286 | passed |
| 2026.2.2 | 262.10315.135 | passed |
| 2026.2.2.1 | 262.10315.160 | passed |
| 2026.2.3 | 262.10968.67 | passed |

本机原始日志为 `/tmp/reqws-goland-serial-final.log` 与 `/tmp/reqws-workflows-serial-final.log`。最终冻结清单和逐目标结果位于临时目录 `reqws-api-y2efkjf_`，缓存竞争那一批位于 `reqws-api-k3tt8ebv`；两者路径均由运行命令打印。JUnit XML 位于 `integrations/goland/build/test-results/test`。源码身份以本次 Git 提交与差异为准，工件摘要只留在运行报告，不复制为文档清单。

最终候选再次执行本机入口授权探针，报告目录为 `reqws-local-ide-o74x4hx1`，仍为 `environment-blocked / AUTHORIZATION_PREPARATION_REQUIRED`，实际 IDE 和执行结果均为空；这只是验证阻塞处理，没有 GUI 通过证据。

## 本机完整 IDE 与验收边界

最低 SDK 的实际 Verifier 首轮识别出 5 处 `TrustedProjectsListener` Experimental 引用并使门禁失败；没有豁免失败级别或提高 floor。修复使用已有公开信任探针分别等待恢复/撤销，保留同步的信任写入与树能力门槛。新增平台回归无需文件事件即可检测撤销、同 digest 恢复和 dispose 取消，并修复进入 Safe Mode 时残留 live proof 的问题。原有测试未删除；这些生产变更生成新候选，旧候选的 API 结果不能移用。

默认专用 profile 不存在，且未配置可选 License Server；没有授权准备证据。未启动完整 GoLand、未代用户登录账号，三组 Starter/Driver 实际 UI 场景及 I1–I8 真实故障演练没有通过结论。后续须按[本机入口](local-integration.md)完成专用环境授权，再对显式候选 ZIP 自动运行。

正式签名候选尚未生成或验证，临时证书测试不能作为正式签名 ZIP 的 API/UI 证据。此前历史验收报告仍仅适用于原候选。只有全部所需证据完成后才能按[实施与验收](implementation-plan.md)完成 V，不能提前撤销未被验证替代的旧覆盖。
