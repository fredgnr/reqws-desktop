---
name: reqws-signing-maintenance
description: Maintain ReqWS macOS signing certificates, private keys, P12 passwords, private backups, and macos-release credentials. Use for identity audits, same-identity credential updates, or certificate/key migration; ordinary updater development does not require credential operations.
---

# ReqWS 签名凭据维护

维护已经存在的 ReqWS 签名身份，按任务范围完成核对、凭据更新或身份迁移，验证远端恢复并清理本地临时材料。技能本身不包含真实证书、密码、私钥或固定指纹。

## 项目约定

从当前 ReqWS 检出读取 `AGENTS.md` 和[自更新技术方案 §3、§4、§10](../../../docs/changes/macos-self-update/technical-design.md)，按需参考[实际配置记录](../../../docs/changes/macos-self-update/implementation-2026-09-19.md)。不要把历史记录中的临时路径、commit 或“已通过”当成当前候选证据。

| 对象 | 当前约定 |
|---|---|
| 应用源码 / Bundle ID | `fredgnr/reqws-desktop` / `com.reqws.desktop` |
| Environment | `macos-release` |
| 私有备份 | `fredgnr/reqws-secret` 的 `reqws-desktop/macos-signing/` |
| 公开 DER | 源码 `build/certificates/reqws-signing.cer` |
| 公开 pin | Environment Variable `MAC_SIGNING_CERT_SHA256` |
| Secrets | `MAC_SIGNING_P12_BASE64`、`MAC_SIGNING_P12_PASSWORD` |

维护者选择将加密 P12、加密 PEM、各自密码及证书集中保存在指定私有凭据仓库；不要擅自改用密码管理器、其他仓库、Repository Secrets 或永久本地备份。仓库访问权包含完整签名身份访问权。仅公开 DER 留在应用源码，操作用的临时材料完成后清理。

## 先识别更新类型

| 用户目标 | 执行方式 |
|---|---|
| 检查有效期、核对配置、审查维护方案 | 只读公开证书、pin 和配置；只有检查私钥/P12 可恢复性确有需要时才取回秘密材料。不要触发生成、签名、上传或信任变更。 |
| 修复或重传 Secrets、修改 P12/PEM 包装密码 | 复用同一证书和私钥。更新密码/封装不能改变 DER、pin、密钥或有效期；按[同身份维护](references/maintenance.md#同身份维护)执行。 |
| 续签证书、换证书、轮换/丢失/泄露私钥 | 属于身份迁移；即使 CN 或私钥不变，重发证书也可能破坏旧客户端 DR。按[身份迁移](references/maintenance.md#身份迁移)处理，不用覆盖 pin 冒充续用。 |

若“更新证书/密钥”没有说明哪一种，先做只读核对，提出具体选择后等待用户决定；不要先生成替代身份。明确的轮换请求可准备新候选，不能把创建技能、审查文档或修复上传问题当作轮换授权。

## 执行边界

- 依据当前任务授权操作。恢复/修改凭据不自动授权改账号权限、放宽环境保护、安装工具、提交源码、发布 tag/Release 或替换真实 App。已有清晰授权继续执行，不在每次验证或同阶段重试时重复确认。
- 实际维护前读取[操作细节](references/maintenance.md)中对应部分。核对当前活动身份的应用 DER、Environment pin、备份证书及身份记录；不一致时停止写入，说明各公开身份来源，不能自行选择其中一方覆盖另一方。新迁移候选另行核对，不与旧活动身份混用。
- 秘密仅在受控脚本内存、管道或临时 0700 目录/0600 文件中流转。不得读入模型上下文、打印原始 API/blob 内容、显示密码、记录带秘密的 argv/环境，或将其放入技能、源码、日志、测试 fixture、Artifact/Release。不为恢复凭据创建持久私有仓库 clone。
- 新材料先完成验证和私有备份读回恢复，再在无签名 job 使用/等待审批期间切换 Secrets。多项写入不是原子事务；失败就停止消费新配置，按同一候选恢复，不能重新发证重试。
- 本地恢复自已验证的远端材料即使操作失败也要清理临时副本。若新生成材料尚未成功备份而成为唯一副本，停止后续操作，告知精确临时目录并请求恢复安排；不得默默长期保留或删除唯一副本。

## 验证和交接

分别报告：证书/密码学核对、macOS 原生签名与 DR、远端配置、私有备份恢复、本地清理；未执行的项目明确标注。Secret 名称存在不证明其明文正确，本机探针和静态签名不证明 CI 或真实自更新通过。

有文档影响时使用项目的 [reqws-documentation](../reqws-documentation/SKILL.md)：更新有效期/证书说明、维护流程、实施证据及最近索引，保留历史验收范围。指纹从实际 DER 和环境读取，不在技能里硬编码。仅文档或公开 CER 调整运行 `npm run docs:check` 和相关校验；改了签名代码则按仓库要求跑受影响测试与 `npm run check`。

最终给出公开证书状态、备份 commit/路径、更新过的 Secret 名称、验证和清理结果及剩余门禁；不输出秘密，也不把未运行的正式 CI/更新验收写成 GO。
