---
title: macOS 自更新实施与验证记录
type: test-report
status: active
updated: 2026-09-20
---

# macOS 自更新实施与验证记录

本文记录[技术方案](technical-design.md)的 S1/S2 实现、隔离签名升级、长期身份配置及检查。代码和正式公开证书已接入，GitHub Environment 已配置；真实 CI 签名和归档校验已通过，但清理、完整发布及干净用户验收尚未完成，不能宣称正式自更新 GO。

## 1. 候选与实现

开发起点为 `e06d400`（`docs: document macOS self-update design`），本记录随自更新实现、公开证书及维护技能一并提交，源码身份以 Git 提交和 diff 为准。实现与本地打包验证时应用版本为 0.1.2；随后按维护者要求将源码版本及 lockfile 根版本同步为 0.1.3，未重建的既有测试工件仍属于 0.1.2，不作为 0.1.3 包的验收证据。初次交付时未创建 tag/Release；后续获授权创建的 `v0.1.3` 及发布失败见 §5。没有替换真实 ReqWS 或访问其业务数据。单独获授权的私有凭据仓库备份提交见 §4。

- 精确锁定 `electron-updater@6.8.9`；保持 Electron 43.4.0、Forge/Vite、生产入口、Bundle ID 和 userData 路径。
- 实现 `local` / `personal-release` profile、固定签名资源、Hardened Runtime、DER/RSA 4096/Code Signing EKU/有效期/证书 pin 校验。缺正式 CER 或凭据立即失败，没有一次性证书占位或 ad-hoc 回退。
- 实现 GitHub-hosted tag 专用签名 wrapper：私密临时目录、P12、明确钥匙串、Code Signing trust、精确身份、子进程环境 allowlist，以及 finally 和 `always()` 清理日志。系统命令分支有自动化测试；真实 GitHub runner 上的管理员信任及清理仍待验收。
- 实现生产状态机、可信主窗口无参 IPC、Zod 请求/状态校验、typed preload 和可释放订阅。只有包内固定公开 feed 可启用更新，开发/local/不支持平台及损坏配置禁用；不使用客户端 token 或可覆盖 URL。
- 实现手动检查/下载/确认安装、单飞与超时、revision 快照、纯文本 release notes 和有限脱敏错误码。下载完成不宣称签名通过；原生安装失败释放业务冻结、移除本轮安装监听器，要求重启后再试。
- 共享 activity gate 覆盖业务 IPC 全程、活动/排队 workspace 操作、Git 子进程及状态写入。安装先原子关闭准入，再验证可写 Applications 位置并 flush。Git 报错但进程仍存活时继续持有 lease，直到 close；不强杀业务 Git。安装前准入失败保留已下载实例供重试。
- 设置页加入更新区，38 个新增中英文 key 经同模型继承、high reasoning 的只读翻译子代理复核；校验 JSON、key/placeholder 后写回英文，i18n 基线为 308 key。
- 发布流程保留原有 Desktop/插件检查与 Action pins，新增受保护签名步骤、ZIP 解压身份复验、四资产精确 allowlist、元数据校验，以及 draft 下载字节复核后公开。默认 local 安装拒绝覆盖携带更新配置的目标，包括损坏配置。

## 2. S1 真实隔离签名与升级

环境为 macOS 15.7.3（24G419）、arm64、Node 24.20.0、npm 11.19.0。维护者明确授权一次性身份、仅 Code Signing 信任、隔离测试及清理；动作时补充确认后执行。没有扩大 TLS 或其他证书用途。

隔离 App 使用 `com.reqws.update-poc`、实际版本 0.0.1/0.0.2、独立 userData 和 loopback feed。两套同名 `ReqWS Disposable Update Test` 身份均为临时 RSA 4096、Code Signing EKU、2 天有效期；不参与正式发布。

| 场景 | 实际结果 |
|---|---|
| ASAR 离线依赖加载 | `node scripts/macos-update-poc.mts --smoke` 成功实例化真实 MacUpdater，无仓库 node_modules 依赖、无更新请求。 |
| 签名及 DR | `--signed TEST_CERT TEST_KEYCHAIN` 生成两个真实版本；R2 满足 R1 的实际 DR，要求包含固定叶证书身份。 |
| 普通退出 | R1 下载后选择普通退出，重新启动仍是 0.0.1，没有自动安装。 |
| 正向更新 | 明确确认安装，经 Squirrel 原生安装并自动重启到真实 0.0.2；GUI 与事件均确认，隔离业务 sentinel 保留。 |
| 签名后篡改 | 修改 R2 密封资源后重算正确 ZIP SHA-512/size，下载成功，安装原生拒绝“一个密封资源已丢失或无效”；旧版与数据保留。 |
| 同名错误身份 | 用第二张已受信任、同名但不同证书重签 R2，自身签名有效但不满足旧版 DR；重算正确 ZIP 元数据，原生拒绝“代码未能满足指定的代码要求”；旧版与数据保留。 |
| 清理 | 删除本轮新增 Code Signing 信任及临时钥匙串，恢复原搜索列表并比对一致；删除 P12/私钥源文件/恢复口令材料，停止测试进程和 loopback server，移除本轮 ShipIt cache。 |

原始测试根为 `/private/tmp/reqws-update-poc-7xfgax`。脱敏事件、业务 sentinel 和清理结果复制到忽略目录 `dist/macos-update-poc-2026-09-19/`，不提交测试产物或永久维护源文件摘要清单。

### 实测后修正的签名发现策略

所锁定 `@electron/osx-sign@1.3.3` 默认使用 `security find-identity -v` 的 X.509 basic policy，在只信任 Code Signing 的条件下无法发现测试身份；显式 `-p codesigning` 可以发现。ReqWS 因此先自行严格验证 CER、pin、明确钥匙串中唯一 identity 及 Code Signing trust，再设置 `identityValidation: false` 跳过上游错误策略的重复发现。保持 `continueOnError: false`，避免 packager 把签名失败仅当成告警。没有改成全用途信任、没有放宽证书校验，也没有升级 osx-sign。

S1 隔离门禁通过后才接入 S2。上述 PoC 属于 S1 测试入口，不能替代 S2 最终生产包、真实 GitHub feed 或干净用户环境验收。

## 3. 自动化与最终打包检查

| 命令 / 检查 | 结果与范围 |
|---|---|
| `npm run check` | typecheck、lint、308 key i18n、文档索引校验及 38 文件、396 测试通过；覆盖既有 Desktop 回归与新增服务、IPC、renderer、签名和元数据。 |
| `python3 -m unittest discover -s tests/workflows -p 'test_*.py' -v` | 26 测试通过，包含原有 18 项和新增 8 项更新资产/秘密隔离/发布门禁回归；不是实际 GitHub 发布记录。 |
| `npm exec vitest run tests/unit/git-runner.test.ts tests/unit/update-service.test.ts tests/unit/ipc-handlers.test.ts` | 3 文件、41 测试通过；实际覆盖 Git 存活、整个 IPC 操作、队列/状态写入与原子安装互斥。 |
| `npm run package:macos -- --skip-ci --skip-check --arch arm64` | local arm64 `.app` 打包、Bundle ID/版本/架构/codesign 校验通过；未安装真实 ReqWS。受限网络首次 DNS 失败，获准下载锁定 Electron 后重试。 |
| 最终生产 bundle 的隔离 GUI smoke | 从最终 `.app` 的 ASAR 复制不变的 main/preload/renderer/adapter bundle，仅在临时副本增加 userData 隔离 bootstrap、独立 Bundle ID 并重新 ad-hoc 签名；实际启动设置页显示版本 0.1.2、本地构建禁用原因及 disabled 检查按钮，界面布局正常。实际 updater adapter 成功实例化/释放，无更新请求，应用正常退出。 |
| ASAR 与 diff 检查 | 最终 ASAR 共 13 个目录/文件条目，无 node_modules、测试/脚本/插件源码或私钥文件名；隔离副本的生产 bundle 与最终 ASAR 逐字节相同。`git diff --check` 通过。 |

最终 GUI smoke 根为 `/private/tmp/reqws-production-smoke-LIgR8Y`，独立 userData 路径由结果文件确认；结果和 ASAR 目录复制到上述忽略证据目录。首次临时副本更名导致 Electron 找不到原名称的 Helper，恢复副本的 CFBundleName 后通过；没有改动生产包来适配测试。截图在本次任务记录中。该 smoke 验证本地包的真实主进程/preload/renderer 集成及 updater 依赖，不证明正式证书或生产 feed 的 U6/U9。

现有插件生产代码和编译配置未修改，本地没有重跑无关 IDE 验收；CI/Release 的完整插件门禁仍然保留。

## 4. 长期身份与 GitHub Environment 初始化

维护者提供 `reqws-signing-setup-kit.zip` 并授权按手册分阶段配置，确认不存在其他旧身份或已分发的长期签名版本。只读预检确认 `fredgnr` 已登录且具有目标公开仓库管理权限；本机材料目录、钥匙串、main、未合并 PR 及远端配置均没有旧身份冲突。按维护者额外授权安装 OpenSSL 3.6.4 及其 `ca-certificates` 依赖，关闭 Homebrew 自动更新，没有升级其他已安装工具。

| 阶段 | 实际结果 |
|---|---|
| 长期身份 | `ReqWS Personal Code Signing`，RSA 4096、SHA-256、仅 Code Signing EKU，自签名根证书直接签名；有效期为 2026-09-19 08:32:37 UTC 至 2036-09-16 08:32:37 UTC。初始化使用仓库外专用目录，目录 0700、文件 0600；备份验收后已清理本地材料。 |
| 密码学验证 | 加密 PEM 与证书公私钥匹配，DER/PEM 相同，P12 完整性和唯一证书匹配、用途与有效期检查通过。保留 OpenSSL 3 的 AES-256-CBC / SHA-256、100000 次迭代封装，没有采用兼容降级。 |
| macOS 签名探针 | 临时钥匙串导入真实 P12，签署两个不执行的临时 Mach-O；抽取证书与 DER 相同，B 满足 A 的实际 DR，同 Bundle ID 的 ad-hoc 身份被拒绝。不是 ReqWS/Electron/Squirrel 的正式候选测试。 |
| 清理 | 本次新增用户 Code Signing 信任已撤销，临时钥匙串及探针目录已删除；独立读回确认钥匙串搜索列表恢复原值。本机未保留长期身份的新增信任。 |
| Environment 保护 | 新建 `macos-release`，唯一 reviewer 为 `fredgnr`，允许本人审批，没有 wait timer；唯一允许规则为 `type=tag,name=v*`。网页保存并确认管理员 bypass 未勾选，API 读回为 false。 |
| 凭据上传 | 确认没有正在运行或等待审批的 workflow、没有同名旧 Secrets/pin 后，通过 stdin 上传 `MAC_SIGNING_P12_BASE64`、`MAC_SIGNING_P12_PASSWORD`，最后写入 `MAC_SIGNING_CERT_SHA256`。上传 P12 与签名探针输入绑定一致；写入全部成功，Secret 名称存在，公开 pin 读回与 DER 一致，保护规则未改变。没有读取远端 Secret 明文。 |
| 公开证书 | 仅公开 DER 复制到 `build/certificates/reqws-signing.cer`；实际调用生产 `validateReleaseCertificate` 通过，复制前后字节相同。公开 CER、证书 README 与私密文件忽略规则随实施源码纳入同一提交。 |
| 私有仓库备份 | 维护者后续明确要求全部密码和证书改存 `fredgnr/reqws-secret`，不保留本地私密材料。核实仓库私有、具管理权限、初始为空且无 Actions 工作流后，将加密 P12、原加密 PEM、两份密码、DER/PEM 证书、配置、身份元数据及说明共 9 文件提交到 `reqws-desktop/macos-signing/`。从远端逐文件读回核对，并使用远端密码验证 P12 完整性及证书、PEM 私钥与证书匹配，全部通过；没有创建本地私有仓库 clone。 |
| 本地备份清理 | 原先按维护者指定建立的 `/Users/fred/Desktop/Secret/reqws-desktop` 副本，以及本次 `~/.reqws-signing/reqws-desktop` 初始化材料均已删除；保留原有 `Desktop/Secret` 空目录和 ReqWS 源码所需的公开 CER。没有删除用户其他文件。 |

执行时处理了两处中断：首次生成在 `openssl req` 处失败，尚未创建证书；没有重建私钥，用原加密私钥补齐证书和 P12 后完整验证通过，首次失败原因未能从原脚本保留的诊断确定。原探针把内联 DR 作为 `-R` 后的独立参数，macOS 将其解释成文件路径并报 `invalid requirement specification`；按本机 `codesign --help` 修正为单一参数 `-R=<表达式>`，正向及 ad-hoc 负向才通过。解压副本的脚本及附录同步修正，原下载 ZIP 未改动；没有放宽 DR。

私有备份对应提交为 [`35d805f`](https://github.com/fredgnr/reqws-secret/commit/35d805f337d3d92b71c80a4d291362bcf4a538fe)。维护者选择将密码和加密私钥存入同一私有凭据仓库，因此仓库访问权等同于完整签名身份访问权；该选择替代了手册的独立密码管理器安排，不能描述为密码独立保管或 GitHub 之外的备份。

清理前将不含秘密的配置、探针、远端恢复验证和清理结果保存在忽略目录 `dist/macos-signing-setup-2026-09-19/`。证书 pin 通过 DER 与 Environment Variable 核对，不在源码文档粘贴摘要清单；私钥/P12/密码只进入指定 Environment Secrets 和获授权的私有凭据仓库，没有进入聊天、ReqWS 源码、Artifact 或 Release。

配置结论为 `CONFIGURED_CI_CONSUMPTION_PENDING`，备份结论为 `PRIVATE_REPOSITORY_BACKUP_VERIFIED`，本地材料清理完成。GitHub 配置成功只证明写入及公开字段读回，不证明 runner 已解密并签名。

后续维护入口为项目级 [reqws-signing-maintenance](../../../.agents/skills/reqws-signing-maintenance/SKILL.md)，没有安装到全局技能目录。技能区分只读核对、同身份包装密码/Secrets 维护和真实身份迁移；技术方案中原固定 home 目录、独立密码管理器和首次创建环境的操作片段已改为当前的临时恢复/私有备份流程。开发指南、Agent 技能路由和相关索引同步更新。

技能创建期间执行了三个独立非执行场景演练：公开证书到期审计、同候选部分上传恢复、旧 DR 约束下的新身份迁移，均保持了预期范围；据此明确 Secret 响应未知时的恢复依据和确切 P12 探针证据的复用条件。修订后单独复测中断恢复案例通过；技能结构、UI metadata、参考链接、项目级位置及文档/diff 检查通过。场景定义保存在技能的 `evals/evals.json`，不含真实秘密；演练输出保存在忽略目录 `dist/reqws-signing-skill-eval-2026-09-19/`。此次没有重新读取私钥、修改 Secrets、生成证书、改变钥匙串或执行发布；这些演练不能替代真实凭据维护、CI 或自更新验收。

## 5. v0.1.3 发布失败与 P12 导入修复

PR #11 合并后，维护者授权将 `v0.1.3` 指向合并提交 `cb25cbb` 并推送。[Release run 35435196381](https://github.com/fredgnr/reqws-desktop/actions/runs/35435196381) 的版本校验、完整项目检查和插件打包通过；[macOS package job](https://github.com/fredgnr/reqws-desktop/actions/runs/35435196381/job/105876578509) 在签名步骤失败，兜底清理成功，publish 被跳过，未生成 `v0.1.3` Release。

旧 wrapper 隐藏全部异常，日志只有统一失败消息，无法直接识别具体系统命令。核对成功的长期身份探针发现它显式传入 `security import -f pkcs12`，而正式 wrapper 依赖自动格式识别。在 macOS 15.7.3 arm64 使用一次性 RSA 4096 / OpenSSL 3 P12（AES-256-CBC、SHA-256 MAC、100000 次迭代）复现：相同文件和密码，省略格式时返回 `MAC verification failed during PKCS12 import (wrong password?)`，显式指定 `pkcs12` 后成功导入，读回证书相同。该可复现差异解释了正式脚本与成功探针的行为差异；旧 runner 日志本身不包含原生错误，修复后的正式 runner 签名仍待新候选验证。

修复显式选择 P12 格式，继续核对唯一证书与公开 DER；固定阶段诊断覆盖凭据、证书、钥匙串、P12 导入、信任、打包及清理，不输出原始错误或秘密。新增原生回归调用生产导入函数，验证正确密码导入后存在匹配的证书/私钥对、错误密码被拒绝，并恢复搜索列表及删除临时钥匙串；测试只使用一次性材料，不读取真实备份/Secrets，不改信任。没有重新生成长期身份、改变 P12 封装或覆盖发布标签。

修复候选本地 `npm run check` 通过 39 个文件、400 项测试；工作流 Python 回归 26 项通过。原生导入回归共 2 项，临时移除格式参数时正确密码用例失败、错误密码用例通过；恢复参数后两项均通过。真实 Node CLI 启动与上下文拒绝、固定阶段诊断和清理失败重试也有回归覆盖。官方 macOS 15 runner 默认 OpenSSL 为 1.1，因此 CI 与 Release 项目检查显式准备已有或安装缺失的 `openssl@3`，不依赖默认 PATH 的版本；本地不自动安装工具。

本节的原生导入回归不等于完整生产包、GitHub runner 管理员信任或真实自更新验收。截至该修复候选提交时，`v0.1.3` 仍指向原提交；重跑原标签不会使用修复。后续维护者明确授权的 squash 合并与标签重建及新报错见 §7，不将该例外当成常规重用已发布版本的流程。

## 6. Release 分阶段日志

在 P12 修复候选上补充 `[release]` 日志：各 job 记录公开 run/ref/commit/runner 上下文；签名、信任、临时材料清理以及打包、ZIP 解压复验、元数据生成记录开始、结果与耗时。打包子进程在长期 Secrets 已移除的环境下实时输出 Forge 日志，不再将其全部缓存到结束。资产验证列出精确允许资产的名称/字节数；publish 记录创建草稿、上传、资产清单检查、下载字节复验、公开和失败清理。

签名日志只接收固定阶段和系统命令退出码，不打印密码、P12、原始异常或 argv/stderr；上下文采用字段 allowlist 和 JSON 控制字符转义。回归覆盖操作失败先于清理、清理失败后重试、秘密哨兵不进入日志，以及模拟发布成功、上传失败、清理失败、下载字节损坏和已有 Release 五种路径，确认保持原退出码、验证门禁及草稿归属约束。没有使用真实发布凭据或触发 tag/Release。

本轮 `npm run check` 通过 40 个文件、402 项测试；工作流 Python 回归 28 项通过，文档和 diff 检查通过。上述发布路径使用本地替代 `gh` 和一次性资产，属于日志及控制流回归，不替代正式 Release 验收。

## 7. 签后证书抽取修复（2026-09-20）

维护者随后授权将 PR #12 squash 合入 `main`（`589b26a`），删除并重建 `v0.1.3` 指向该提交，触发 [Release run 35436742088](https://github.com/fredgnr/reqws-desktop/actions/runs/35436742088)。[macOS package job](https://github.com/fredgnr/reqws-desktop/actions/runs/35436742088/job/105935891198) 的签后校验报告 `codesign --display --extract-certificates <prefix> <app>` 把临时证书输出前缀当作输入文件，报 `No such file or directory`。诊断时 job 仍运行且独立清理步骤未完成，不能由本地修复推断该 runner 已清理。

改为 `--extract-certificates=<prefix>` 单一参数，继续读取叶证书 `${prefix}0` 并逐项核对应用、嵌套 App 和 Mach-O 的 pin。保留原生严格验签、Hardened Runtime、ad-hoc 拒绝和 finally 目录清理。

新增原生回归从系统 `/usr/bin/codesign` 复制已有签名到带空格的临时嵌套路径，使用生产抽取函数和实际 Node/`codesign` 提取 DER；直接断言文件存在、为 X.509 DER 且副本与原件叶证书一致。临时恢复旧参数时，该测试复现同一输入路径错误。完整 `verifyReleaseSignature` 的六项模拟系统命令回归覆盖四个签名目标的遍历、错误嵌套身份、ad-hoc、缺少 Runtime、无叶证书输出、原生验签失败，以及成功/失败后的目录清理。

修复候选本地 `npm run check` 通过 42 个文件、409 项测试；工作流 Python 回归 28 项通过，文档和 diff 检查通过。新增的 1 项原生测试和 6 项完整校验回归均实际执行，没有跳过。

原生抽取测试不需要新身份、签名或信任；受限沙箱的 `codesign` 即使返回 0 也可能不写证书，因此必须以实际输出为准。前置可行性探针使用的一次性身份未能完成签名，临时钥匙串和材料已清理、搜索列表已恢复，没有将该探针计入通过证据。本轮不读取真实私钥/Secrets、不更改发布标签；生产 runner 完整签名、清理和发布仍需修复合入后的正式候选验证。

## 8. 一次性 runner 的信任撤销修复（2026-09-20）

PR #15 squash 合入 `main`（`c524a42`）后，维护者授权重建 `v0.1.3` 并继续观察。[Release run 35458968692](https://github.com/fredgnr/reqws-desktop/actions/runs/35458968692) 的项目检查和插件打包通过；macOS 实时日志确认 Forge 签名、完整包校验、ZIP 创建/解压复验、更新元数据和 checksums 全部成功，随后停留在 `stage=remove-trust status=started`，没有进入上传/发布。该证据确认了 P12 与证书抽取修复有效，不代表该 run 完整成功或已完成清理。

针对 GitHub-hosted macOS 删除最后一条管理员信任记录可能等待交互授权的问题，生产 wrapper 改为将本轮证书的 Code Signing 信任设为明确 `deny`，导出管理员 trust settings 并检查相同 identity 下只有预期拒绝规则，然后删除钥匙串、P12 和临时目录。拒绝记录留到一次性 runner 回收，不宣称数据库为空；不更改 authorizationdb/SIP，不扩大证书用途。每个系统命令最多 60 秒，提权命令在 root 进程内设置 alarm；超时仍尝试其余私密材料清理，公开日志支持 `always()` 重试。

新增回归覆盖原生离线拒绝记录、错误 policy/信任授予/额外例外拒绝、系统命令真实超时、参数不经 shell、错误不泄漏秘密，以及读回失败后的清理重试。另有仅在 GitHub-hosted macOS 执行的一次性证书回归：先验证信任有效，再调用生产撤销函数并读回，最后确认系统拒绝该证书。本地不伪造 CI 环境、不执行管理员信任变更；该项必须以新 PR 的真实 CI 结果为合入门禁。

本地 `npm run check` 通过 43 个文件、412 项测试，另有上述 1 项 CI 专属测试按条件跳过；工作流 Python 回归 31 项通过，类型、lint、i18n、文档与 diff 检查通过。完整 runner 清理及发布结果需由本轮修复候选的 CI/Release 继续验证。

## 9. 剩余验收与交接

- 本次源码提交与推送只交付实施候选，不包含正式发布；私有凭据备份、源码提交和真实发布验收是不同交付结果。
- 真实 runner 执行签名 wrapper、Hardened Runtime 生产包和解压后证书/DR 校验，确认临时管理员信任清理；缺项或错身份必须失败，不能以 mock 代替。
- 经授权发布未使用版本号的 R1/R2，在维护者实际安装位置完成 GitHub 匿名检查、下载、安装、重启和业务数据保留（U6），保留对应 commit/run/Release 证据。
- 在没有私钥的干净用户环境验证首次启动、实际所需信任及后续更新（U9）。本机隔离 PoC 使用临时信任，不证明干净用户无需信任。
- 针对同一最终正式候选补充原生安全负向及真实网络/系统失败路径，按 U1–U9 给出最终发布结论。任何失败都不能通过关闭 Gatekeeper 或放宽 DR 解决。

当前实现及身份配置支持继续受控验收；未发布、未修改历史资产，也未宣称面向任意 Mac 的安全自更新已完成。使用说明、开发指南、自更新索引和现有 Release 需求/设计/交付契约已同步。
